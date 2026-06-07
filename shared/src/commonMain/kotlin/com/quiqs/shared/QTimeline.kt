package com.quiqs.shared

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

/**
 * A single firing of a [Q]'s alarm on the calendar. Recurring alarms can produce many
 * occurrences for the same Q within a window — each gets its own entry.
 *
 * @property at The wall-clock instant when this occurrence fires.
 * @property q The Q the occurrence belongs to. Carries the rest of the metadata
 *           (text, tags, priority, completed) that timeline UI needs to render.
 */
data class QOccurrence(val at: Instant, val q: Q)

/**
 * Returns every alarm occurrence falling on the calendar [date] in [tz], sorted by time.
 *
 * Per-alarm rules:
 *  - [Alarm.OneOffTime]: included iff `at` lies in `[startOfDay, startOfNextDay)`.
 *  - [Alarm.RecurringTime]: every occurrence inside `[startOfDay, startOfNextDay)` is
 *    materialised as its own [QOccurrence]; bounded by the rule's `until`/`count`.
 *  - [Alarm.Geofence]: skipped — no time anchor.
 *
 * Qs with no alarms (or only geofence alarms) contribute nothing to the day timeline.
 */
fun List<Q>.occurrencesOn(date: LocalDate, tz: TimeZone): List<QOccurrence> {
    val startOfDay = date.atStartOfDayIn(tz)
    val startOfNextDay = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)
    val out = mutableListOf<QOccurrence>()
    for (q in this) {
        for (alarm in q.alarms) {
            when (alarm) {
                is Alarm.OneOffTime -> {
                    if (alarm.at >= startOfDay && alarm.at < startOfNextDay) {
                        out += QOccurrence(alarm.at, q)
                    }
                }
                is Alarm.RecurringTime -> {
                    // Single walk of the recurrence sequence — calling
                    // [nextRecurringInstant] in a loop would re-walk from `dtStart` on
                    // every call (O(n²) for dense rules like FREQ=MINUTELY) and freeze
                    // the UI on busy timelines.
                    for (instant in occurrencesOf(alarm, startOfDay, startOfNextDay, tz)) {
                        out += QOccurrence(instant, q)
                    }
                }
                is Alarm.Geofence -> Unit
            }
        }
    }
    return out.sortedBy { it.at }
}
