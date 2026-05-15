package com.quiqs.shared

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Pure projection spec applied over an in-memory list of [Q]s. Drives the list screen's
 * filter+sort UI and is also the wire shape the LLM emits for natural-language filter
 * requests (see `QCommand.FilterSort` and `LlmPromptBuilder.systemForNewOrFilter`).
 *
 * The defaults are the "no-op" spec — `applyTo` returns the input unchanged sorted by
 * creation date descending, which matches the previous list behaviour.
 */
@Serializable
data class QFilterSort(
    val text: String? = null,
    val tags: List<String> = emptyList(),
    val priorities: List<Priority> = emptyList(),
    val alarm: AlarmFilter = AlarmFilter.ANY,
    val alarmWindow: AlarmWindow = AlarmWindow.ANY,
    val completed: CompletedFilter = CompletedFilter.ANY,
    val sortBy: SortField = SortField.CREATED_AT,
    val sortDir: SortDir = SortDir.DESC,
) {
    @Serializable
    enum class AlarmFilter {
        @SerialName("any") ANY,
        @SerialName("none") NONE,
        @SerialName("any_set") ANY_SET,
        @SerialName("recurring") RECURRING,
        @SerialName("one_off") ONE_OFF,
        @SerialName("geofence") GEOFENCE,
    }

    /**
     * Relative time windows for alarm-date filtering. Stored as relative tokens — not
     * absolute instants — so `applyTo` re-resolves the bounds against the current
     * `now` + `tz` on every evaluation. That keeps a saved spec "always right" (today
     * always means *today*).
     */
    @Serializable
    enum class AlarmWindow {
        @SerialName("any") ANY,
        @SerialName("today") TODAY,
        @SerialName("tomorrow") TOMORROW,
        @SerialName("this_week") THIS_WEEK,
        @SerialName("next_week") NEXT_WEEK,
    }

    @Serializable
    enum class CompletedFilter {
        @SerialName("any") ANY,
        @SerialName("only_open") ONLY_OPEN,
        @SerialName("only_done") ONLY_DONE,
    }

    @Serializable
    enum class SortField {
        @SerialName("tags") TAGS,
        @SerialName("priority") PRIORITY,
        @SerialName("created_at") CREATED_AT,
        @SerialName("updated_at") UPDATED_AT,
        @SerialName("alarm") ALARM,
    }

    @Serializable
    enum class SortDir {
        @SerialName("asc") ASC,
        @SerialName("desc") DESC,
    }

    val isDefault: Boolean
        get() = text.isNullOrBlank() && tags.isEmpty() && priorities.isEmpty() &&
            alarm == AlarmFilter.ANY && alarmWindow == AlarmWindow.ANY &&
            completed == CompletedFilter.ANY &&
            sortBy == SortField.CREATED_AT && sortDir == SortDir.DESC
}

/**
 * Returns [qs] filtered and sorted per [this] spec. Time-aware sorts (alarm) use [now]
 * and [tz] to resolve the next firing instant — defaults are device-now in the device tz.
 */
fun QFilterSort.applyTo(
    qs: List<Q>,
    now: Instant = Clock.System.now(),
    tz: TimeZone = TimeZone.currentSystemDefault(),
): List<Q> {
    var result = qs
    if (!text.isNullOrBlank()) {
        val needle = text.trim()
        result = result.filter { q ->
            q.text.contains(needle, ignoreCase = true) ||
                q.tags.any { it.contains(needle, ignoreCase = true) }
        }
    }
    if (tags.isNotEmpty()) {
        val needles = tags.map { it.lowercase() }.toSet()
        result = result.filter { q -> q.tags.any { it.lowercase() in needles } }
    }
    if (priorities.isNotEmpty()) {
        val pset = priorities.toSet()
        result = result.filter { it.priority in pset }
    }
    result = when (alarm) {
        QFilterSort.AlarmFilter.ANY -> result
        QFilterSort.AlarmFilter.NONE -> result.filter { it.alarms.isEmpty() }
        QFilterSort.AlarmFilter.ANY_SET -> result.filter { it.alarms.isNotEmpty() }
        QFilterSort.AlarmFilter.RECURRING -> result.filter { q -> q.alarms.any { it is Alarm.RecurringTime } }
        QFilterSort.AlarmFilter.ONE_OFF -> result.filter { q -> q.alarms.any { it is Alarm.OneOffTime } }
        QFilterSort.AlarmFilter.GEOFENCE -> result.filter { q -> q.alarms.any { it is Alarm.Geofence } }
    }
    val window = alarmWindow.boundsIn(now, tz)
    if (window != null) {
        result = result.filter { q ->
            val next = q.nextAlarmInstant(now, tz)
            next != null && next >= window.first && next < window.second
        }
    }
    result = when (completed) {
        QFilterSort.CompletedFilter.ANY -> result
        QFilterSort.CompletedFilter.ONLY_OPEN -> result.filter { !it.completed }
        QFilterSort.CompletedFilter.ONLY_DONE -> result.filter { it.completed }
    }

    val priorityOrder: (Priority) -> Int = { p ->
        when (p) { Priority.HIGH -> 0; Priority.MEDIUM -> 1; Priority.LOW -> 2 }
    }
    val nextInstant: (Q) -> Instant? = { q -> q.nextAlarmInstant(now, tz) }
    val ascending: Comparator<Q> = when (sortBy) {
        QFilterSort.SortField.TAGS -> compareBy { it.tags.firstOrNull()?.lowercase() ?: "" }
        QFilterSort.SortField.PRIORITY -> compareBy { priorityOrder(it.priority) }
        QFilterSort.SortField.CREATED_AT -> compareBy { it.createdAt }
        QFilterSort.SortField.UPDATED_AT -> compareBy { it.updatedAt }
        // nullsLast keeps Qs without an upcoming alarm at the bottom regardless of dir.
        QFilterSort.SortField.ALARM -> compareBy(nullsLast(), nextInstant)
    }
    return if (sortDir == QFilterSort.SortDir.DESC) result.sortedWith(ascending.reversed())
    else result.sortedWith(ascending)
}

/**
 * Earliest upcoming alarm instant for the Q after [now], or `null` if no alarm fires
 * (no alarms, all one-offs in the past, or recurring rules whose UNTIL has passed).
 */
private fun Q.nextAlarmInstant(now: Instant, tz: TimeZone): Instant? =
    alarms.mapNotNull { a ->
        when (a) {
            is Alarm.OneOffTime -> a.at.takeIf { it > now }
            is Alarm.RecurringTime -> nextRecurringInstant(a, now, tz)
            is Alarm.Geofence -> null
        }
    }.minOrNull()

/**
 * Resolves the relative window into a half-open `[start, endExclusive)` instant range
 * in [tz], or `null` for [QFilterSort.AlarmWindow.ANY] (no window filter).
 *
 * Day boundaries are calendar-day in [tz]. Weeks are Monday-start (ISO 8601).
 */
private fun QFilterSort.AlarmWindow.boundsIn(
    now: Instant,
    tz: TimeZone,
): Pair<Instant, Instant>? {
    if (this == QFilterSort.AlarmWindow.ANY) return null
    val today = now.toLocalDateTime(tz).date
    val (start, endExclusive) = when (this) {
        QFilterSort.AlarmWindow.TODAY ->
            today to today.plus(1, DateTimeUnit.DAY)
        QFilterSort.AlarmWindow.TOMORROW ->
            today.plus(1, DateTimeUnit.DAY) to today.plus(2, DateTimeUnit.DAY)
        QFilterSort.AlarmWindow.THIS_WEEK -> {
            val mondayOfThisWeek = today.minus(today.dayOfWeek.daysFromMonday(), DateTimeUnit.DAY)
            mondayOfThisWeek to mondayOfThisWeek.plus(7, DateTimeUnit.DAY)
        }
        QFilterSort.AlarmWindow.NEXT_WEEK -> {
            val mondayOfNextWeek = today
                .minus(today.dayOfWeek.daysFromMonday(), DateTimeUnit.DAY)
                .plus(7, DateTimeUnit.DAY)
            mondayOfNextWeek to mondayOfNextWeek.plus(7, DateTimeUnit.DAY)
        }
        QFilterSort.AlarmWindow.ANY -> error("unreachable")
    }
    return start.atStartOfDayIn(tz) to endExclusive.atStartOfDayIn(tz)
}

private fun DayOfWeek.daysFromMonday(): Int = isoDayNumber - 1
