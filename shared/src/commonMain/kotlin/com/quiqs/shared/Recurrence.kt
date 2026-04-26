package com.quiqs.shared

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Computes the next firing instant strictly after [after] for an iCalendar RFC 5545 RRULE.
 * Returns `null` if the rule can't be parsed or no further occurrence exists (UNTIL passed).
 *
 * Supports a pragmatic subset that matches what the LLM emits: FREQ (DAILY|WEEKLY|MONTHLY|
 * YEARLY), BYDAY, BYMONTHDAY, BYMONTH, BYHOUR, BYMINUTE, UNTIL. INTERVAL>1 and COUNT are
 * ignored — without a DTSTART anchor in the rule we can't enforce them. If BYHOUR/BYMINUTE
 * are absent the rule fires at 09:00 local.
 */
fun nextRecurringInstant(rule: String, after: Instant, tz: TimeZone): Instant? {
    val parts = parseRule(rule) ?: return null
    val freq = parts.freq ?: return null
    if (parts.until != null && parts.until <= after) return null

    val byHours = parts.byHour ?: listOf(9)
    val byMinutes = parts.byMinute ?: listOf(0)
    val sortedHours = byHours.sorted()
    val sortedMinutes = byMinutes.sorted()

    var date: LocalDate = after.toLocalDateTime(tz).date
    repeat(SEARCH_HORIZON_DAYS) {
        if (date.matches(freq, parts)) {
            for (h in sortedHours) for (m in sortedMinutes) {
                val candidate = date.atTime(h, m).toInstant(tz)
                if (candidate > after) {
                    if (parts.until != null && candidate > parts.until) return null
                    return candidate
                }
            }
        }
        date = date.plus(1, DateTimeUnit.DAY)
    }
    return null
}

private const val SEARCH_HORIZON_DAYS = 366

private enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

private data class RuleParts(
    val freq: Freq?,
    val byDay: Set<DayOfWeek>,
    val byMonthDay: Set<Int>,
    val byMonth: Set<Int>,
    val byHour: List<Int>?,
    val byMinute: List<Int>?,
    val until: Instant?,
)

private fun LocalDate.matches(freq: Freq, parts: RuleParts): Boolean = when (freq) {
    Freq.DAILY -> true
    Freq.WEEKLY -> parts.byDay.isEmpty() || dayOfWeek in parts.byDay
    Freq.MONTHLY -> parts.byMonthDay.isEmpty() || day in parts.byMonthDay
    Freq.YEARLY ->
        (parts.byMonth.isEmpty() || month.number in parts.byMonth) &&
            (parts.byMonthDay.isEmpty() || day in parts.byMonthDay)
}

private fun parseRule(rule: String): RuleParts? {
    var freq: Freq? = null
    var byDay = emptySet<DayOfWeek>()
    var byMonthDay = emptySet<Int>()
    var byMonth = emptySet<Int>()
    var byHour: List<Int>? = null
    var byMinute: List<Int>? = null
    var until: Instant? = null
    for (part in rule.split(';')) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val key = part.substring(0, eq).trim().uppercase()
        val value = part.substring(eq + 1).trim()
        when (key) {
            "FREQ" -> freq = when (value.uppercase()) {
                "DAILY" -> Freq.DAILY
                "WEEKLY" -> Freq.WEEKLY
                "MONTHLY" -> Freq.MONTHLY
                "YEARLY" -> Freq.YEARLY
                else -> return null
            }
            "BYDAY" -> byDay = value.split(',').mapNotNull { it.toDayOfWeek() }.toSet()
            "BYMONTHDAY" -> byMonthDay = value.split(',').mapNotNull { it.toIntOrNull() }.toSet()
            "BYMONTH" -> byMonth = value.split(',').mapNotNull { it.toIntOrNull() }.toSet()
            "BYHOUR" -> byHour = value.split(',').mapNotNull { it.toIntOrNull() }
            "BYMINUTE" -> byMinute = value.split(',').mapNotNull { it.toIntOrNull() }
            "UNTIL" -> until = parseRruleUntil(value)
        }
    }
    return RuleParts(freq, byDay, byMonthDay, byMonth, byHour, byMinute, until)
}

private fun String.toDayOfWeek(): DayOfWeek? = when (takeLast(2).uppercase()) {
    "MO" -> DayOfWeek.MONDAY
    "TU" -> DayOfWeek.TUESDAY
    "WE" -> DayOfWeek.WEDNESDAY
    "TH" -> DayOfWeek.THURSDAY
    "FR" -> DayOfWeek.FRIDAY
    "SA" -> DayOfWeek.SATURDAY
    "SU" -> DayOfWeek.SUNDAY
    else -> null
}

// RFC 5545 UNTIL is in basic format: YYYYMMDD or YYYYMMDDTHHMMSSZ.
private fun parseRruleUntil(value: String): Instant? = runCatching {
    when {
        value.length == 8 -> LocalDate(
            year = value.substring(0, 4).toInt(),
            month = value.substring(4, 6).toInt(),
            day = value.substring(6, 8).toInt(),
        ).atTime(23, 59, 59).toInstant(TimeZone.UTC)
        value.length == 16 && value.endsWith('Z') -> LocalDateTime(
            year = value.substring(0, 4).toInt(),
            month = value.substring(4, 6).toInt(),
            day = value.substring(6, 8).toInt(),
            hour = value.substring(9, 11).toInt(),
            minute = value.substring(11, 13).toInt(),
            second = value.substring(13, 15).toInt(),
        ).toInstant(TimeZone.UTC)
        else -> null
    }
}.getOrNull()
