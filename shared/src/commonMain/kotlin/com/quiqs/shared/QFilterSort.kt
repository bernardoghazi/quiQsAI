package com.quiqs.shared

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
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
            alarm == AlarmFilter.ANY && completed == CompletedFilter.ANY &&
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
            is Alarm.RecurringTime -> nextRecurringInstant(a.rule, now, tz)
            is Alarm.Geofence -> null
        }
    }.minOrNull()
