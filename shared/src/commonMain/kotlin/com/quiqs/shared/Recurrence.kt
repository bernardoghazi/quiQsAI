package com.quiqs.shared

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Computes the next firing instant strictly after [after] for [alarm], in [tz]. Returns
 * `null` if the rule can't be parsed, the recurrence has already ended
 * ([Alarm.RecurringTime.until] passed or [Alarm.RecurringTime.count] exhausted), or no
 * occurrence falls within the search horizon.
 *
 * Supported RRULE parts: FREQ (MINUTELY | HOURLY | DAILY | WEEKLY | MONTHLY | YEARLY),
 * INTERVAL, BYDAY (with optional ordinal prefix like `2SU` or `-1FR` for MONTHLY/YEARLY),
 * BYMONTH, BYMONTHDAY, BYHOUR, BYMINUTE. DTSTART, UNTIL, and COUNT are carried on
 * [Alarm.RecurringTime] fields, not inside the rule string. When BYHOUR/BYMINUTE are
 * absent (calendar frequencies), occurrences fire at the dtStart's local hour:minute.
 */
fun nextRecurringInstant(alarm: Alarm.RecurringTime, after: Instant, tz: TimeZone): Instant? {
    val parts = parseRule(alarm.rule) ?: return null
    val freq = parts.freq ?: return null
    val interval = parts.interval.coerceAtLeast(1)
    val dtStart = alarm.dtStart
    val until = alarm.until
    val maxCount = alarm.count
    if (until != null && until <= after) return null

    val occurrences = when (freq) {
        Freq.MINUTELY -> subDayOccurrences(dtStart, (interval).minutes, parts, tz)
        Freq.HOURLY -> subDayOccurrences(dtStart, (interval).hours, parts, tz)
        else -> calendarOccurrences(dtStart, freq, interval, parts, tz)
    }

    var index = 0
    for (candidate in occurrences) {
        index++
        if (maxCount != null && index > maxCount) return null
        if (until != null && candidate > until) return null
        if (candidate > after) return candidate
    }
    return null
}

/**
 * Every occurrence of [alarm] inside the half-open instant range `[rangeStart, rangeEnd)`,
 * in chronological order. Walks the alarm's occurrence sequence from `dtStart` exactly
 * once and emits the slice that falls in the window — far cheaper than calling
 * [nextRecurringInstant] in a loop (which re-walks from `dtStart` per call, O(n²) for
 * dense rules like `FREQ=MINUTELY`).
 *
 * Returns an empty list if the rule can't be parsed, the recurrence has already ended
 * by `rangeStart`, or no occurrence falls in the window.
 */
fun occurrencesOf(
    alarm: Alarm.RecurringTime,
    rangeStart: Instant,
    rangeEnd: Instant,
    tz: TimeZone,
): List<Instant> {
    if (rangeEnd <= rangeStart) return emptyList()
    val parts = parseRule(alarm.rule) ?: return emptyList()
    val freq = parts.freq ?: return emptyList()
    val interval = parts.interval.coerceAtLeast(1)
    val dtStart = alarm.dtStart
    val until = alarm.until
    val maxCount = alarm.count
    if (rangeEnd <= dtStart) return emptyList()
    if (until != null && until < rangeStart) return emptyList()

    val occurrences = when (freq) {
        Freq.MINUTELY -> subDayOccurrences(dtStart, (interval).minutes, parts, tz)
        Freq.HOURLY -> subDayOccurrences(dtStart, (interval).hours, parts, tz)
        else -> calendarOccurrences(dtStart, freq, interval, parts, tz)
    }

    val out = mutableListOf<Instant>()
    var index = 0
    for (candidate in occurrences) {
        index++
        if (maxCount != null && index > maxCount) break
        if (until != null && candidate > until) break
        if (candidate >= rangeEnd) break
        if (candidate >= rangeStart) out += candidate
    }
    return out
}

private const val SEARCH_HORIZON_DAYS = 366

private enum class Freq { MINUTELY, HOURLY, DAILY, WEEKLY, MONTHLY, YEARLY }

private data class NthDay(val ordinal: Int?, val day: DayOfWeek)

private data class RuleParts(
    val freq: Freq?,
    val interval: Int,
    val byDay: Set<NthDay>,
    val byMonthDay: Set<Int>,
    val byMonth: Set<Int>,
    val byHour: List<Int>?,
    val byMinute: List<Int>?,
)

private fun calendarOccurrences(
    dtStart: Instant,
    freq: Freq,
    interval: Int,
    parts: RuleParts,
    tz: TimeZone,
): Sequence<Instant> = sequence {
    val anchorLocal = dtStart.toLocalDateTime(tz)
    val anchorDate = anchorLocal.date
    val hours = (parts.byHour ?: listOf(anchorLocal.hour)).distinct().sorted()
    val minutes = (parts.byMinute ?: listOf(anchorLocal.minute)).distinct().sorted()
    var date = anchorDate
    repeat(SEARCH_HORIZON_DAYS) {
        if (date.matchesCalendar(freq, interval, parts, anchorDate)) {
            for (h in hours) for (m in minutes) {
                yield(date.atTime(h, m).toInstant(tz))
            }
        }
        date = date.plus(1, DateTimeUnit.DAY)
    }
}

private fun subDayOccurrences(
    dtStart: Instant,
    step: Duration,
    parts: RuleParts,
    tz: TimeZone,
): Sequence<Instant> = sequence {
    if (step <= Duration.ZERO) return@sequence
    val horizon = dtStart + SEARCH_HORIZON_DAYS.days
    var t = dtStart
    while (t <= horizon) {
        val local = t.toLocalDateTime(tz)
        if (local.matchesSubDayFilters(parts)) {
            yield(t)
        }
        t += step
    }
}

private fun LocalDate.matchesCalendar(
    freq: Freq,
    interval: Int,
    parts: RuleParts,
    anchor: LocalDate,
): Boolean {
    val onCycle = when (freq) {
        Freq.DAILY -> anchor.daysUntil(this) % interval == 0
        Freq.WEEKLY -> {
            val a = anchor.weekStart()
            val t = this.weekStart()
            (a.daysUntil(t) / 7) % interval == 0
        }
        Freq.MONTHLY -> monthsBetween(anchor, this) % interval == 0
        Freq.YEARLY -> (year - anchor.year) % interval == 0
        Freq.MINUTELY, Freq.HOURLY -> error("sub-day freq handled separately")
    }
    if (!onCycle) return false

    if (parts.byMonth.isNotEmpty() && month.number !in parts.byMonth) return false
    if (parts.byMonthDay.isNotEmpty() && day !in parts.byMonthDay) return false
    if (parts.byDay.isNotEmpty() && parts.byDay.none { it.matches(this, freq) }) return false
    return true
}

private fun LocalDateTime.matchesSubDayFilters(parts: RuleParts): Boolean {
    if (parts.byMonth.isNotEmpty() && month.number !in parts.byMonth) return false
    if (parts.byMonthDay.isNotEmpty() && day !in parts.byMonthDay) return false
    if (parts.byHour != null && hour !in parts.byHour) return false
    if (parts.byMinute != null && minute !in parts.byMinute) return false
    if (parts.byDay.isNotEmpty() && parts.byDay.none { it.day == dayOfWeek }) return false
    return true
}

private fun NthDay.matches(date: LocalDate, freq: Freq): Boolean {
    if (date.dayOfWeek != day) return false
    if (ordinal == null) return true
    return when (freq) {
        Freq.MONTHLY, Freq.YEARLY -> {
            if (ordinal > 0) (date.day - 1) / 7 + 1 == ordinal
            else (date.lengthOfMonth() - date.day) / 7 + 1 == -ordinal
        }
        // Per RFC 5545 the ordinal is ignored for WEEKLY/DAILY.
        else -> true
    }
}

private fun LocalDate.weekStart(): LocalDate =
    minus(dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

private fun monthsBetween(a: LocalDate, b: LocalDate): Int =
    (b.year - a.year) * 12 + (b.month.number - a.month.number)

private fun LocalDate.lengthOfMonth(): Int {
    val first = LocalDate(year, month, 1)
    return first.daysUntil(first.plus(1, DateTimeUnit.MONTH))
}

private fun parseRule(rule: String): RuleParts? {
    var freq: Freq? = null
    var interval = 1
    var byDay = emptySet<NthDay>()
    var byMonthDay = emptySet<Int>()
    var byMonth = emptySet<Int>()
    var byHour: List<Int>? = null
    var byMinute: List<Int>? = null
    for (part in rule.split(';')) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val key = part.substring(0, eq).trim().uppercase()
        val value = part.substring(eq + 1).trim()
        when (key) {
            "FREQ" -> freq = when (value.uppercase()) {
                "MINUTELY" -> Freq.MINUTELY
                "HOURLY" -> Freq.HOURLY
                "DAILY" -> Freq.DAILY
                "WEEKLY" -> Freq.WEEKLY
                "MONTHLY" -> Freq.MONTHLY
                "YEARLY" -> Freq.YEARLY
                else -> return null
            }
            "INTERVAL" -> value.toIntOrNull()?.let { interval = it.coerceAtLeast(1) }
            "BYDAY" -> byDay = value.split(',').mapNotNull { it.toNthDay() }.toSet()
            "BYMONTHDAY" -> byMonthDay = value.split(',').mapNotNull { it.toIntOrNull() }.toSet()
            "BYMONTH" -> byMonth = value.split(',').mapNotNull { it.toIntOrNull() }.toSet()
            "BYHOUR" -> byHour = value.split(',').mapNotNull { it.toIntOrNull() }
            "BYMINUTE" -> byMinute = value.split(',').mapNotNull { it.toIntOrNull() }
            // UNTIL, COUNT, DTSTART live on Alarm.RecurringTime fields, not in the rule.
        }
    }
    return RuleParts(freq, interval, byDay, byMonthDay, byMonth, byHour, byMinute)
}

private fun String.toNthDay(): NthDay? {
    val trimmed = trim().uppercase()
    if (trimmed.length < 2) return null
    val day = trimmed.takeLast(2).toDayOfWeek() ?: return null
    val ordinalStr = trimmed.dropLast(2)
    val ordinal = if (ordinalStr.isEmpty()) null else ordinalStr.toIntOrNull() ?: return null
    return NthDay(ordinal, day)
}

private fun String.toDayOfWeek(): DayOfWeek? = when (uppercase()) {
    "MO" -> DayOfWeek.MONDAY
    "TU" -> DayOfWeek.TUESDAY
    "WE" -> DayOfWeek.WEDNESDAY
    "TH" -> DayOfWeek.THURSDAY
    "FR" -> DayOfWeek.FRIDAY
    "SA" -> DayOfWeek.SATURDAY
    "SU" -> DayOfWeek.SUNDAY
    else -> null
}
