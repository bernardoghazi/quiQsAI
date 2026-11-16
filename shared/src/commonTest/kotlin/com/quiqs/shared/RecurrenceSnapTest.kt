package com.quiqs.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class RecurrenceSnapTest {
    private val tz = TimeZone.of("America/Sao_Paulo") // UTC-03, no DST in 2026
    private val monday = Instant.parse("2026-11-16T15:22:00-03:00")

    private fun alarm(rule: String, start: String, until: String? = null) =
        Alarm.RecurringTime(rule, Instant.parse(start), until?.let(Instant::parse))

    private fun Instant.local() = toLocalDateTime(tz)

    @Test
    fun thursdayStartForWednesdayRuleSnapsToComingWednesday() {
        // The real bug: asked on Monday Nov 16 for "every Wednesday 5pm", model said Thu Nov 19.
        val snapped = alarm("FREQ=WEEKLY;INTERVAL=1;BYDAY=WE", "2026-11-19T20:00:00Z").snappedToRule(monday, tz)
        val local = snapped.dtStart.local()
        assertEquals(DayOfWeek.WEDNESDAY, local.dayOfWeek)
        assertEquals("2026-11-18", local.date.toString())
        assertEquals(17, local.hour)
        assertEquals(0, local.minute)
    }

    @Test
    fun tuesdayStartForWednesdayRuleSnapsForward() {
        val snapped = alarm("FREQ=WEEKLY;BYDAY=WE", "2026-11-17T20:00:00Z").snappedToRule(monday, tz)
        assertEquals("2026-11-18", snapped.dtStart.local().date.toString())
    }

    @Test
    fun todayIsKeptWhenTimeIsStillAhead() {
        // Monday 3:22pm, "every Monday at 5pm" → today.
        val snapped = alarm("FREQ=WEEKLY;BYDAY=MO", "2026-11-23T20:00:00Z").snappedToRule(monday, tz)
        assertEquals("2026-11-16", snapped.dtStart.local().date.toString())
    }

    @Test
    fun todayIsSkippedWhenTimeAlreadyPassed() {
        // Monday 3:22pm, "every Monday at 9am" → next Monday.
        val snapped = alarm("FREQ=WEEKLY;BYDAY=MO", "2026-11-16T12:00:00Z").snappedToRule(monday, tz)
        assertEquals("2026-11-23", snapped.dtStart.local().date.toString())
    }

    @Test
    fun deliberateLaterStartIsRespected() {
        // "Starting in January, every Wednesday" → search begins at the model's date.
        val snapped = alarm("FREQ=WEEKLY;BYDAY=WE", "2027-01-05T20:00:00Z").snappedToRule(monday, tz)
        val local = snapped.dtStart.local()
        assertEquals("2027-01-06", local.date.toString())
        assertEquals(17, local.hour)
    }

    @Test
    fun monthlyOrdinalSnapsToSecondSunday() {
        val snapped = alarm("FREQ=MONTHLY;BYDAY=2SU", "2026-11-17T12:00:00Z").snappedToRule(monday, tz)
        assertEquals("2026-12-13", snapped.dtStart.local().date.toString())
    }

    @Test
    fun monthDayRuleSnapsToThatDay() {
        val snapped = alarm("FREQ=MONTHLY;BYMONTHDAY=1", "2026-11-20T12:00:00Z").snappedToRule(monday, tz)
        assertEquals("2026-12-01", snapped.dtStart.local().date.toString())
    }

    @Test
    fun rulesWithoutDayConstraintsAreUntouched() {
        val a = alarm("FREQ=DAILY", "2026-11-17T20:00:00Z")
        assertSame(a, a.snappedToRule(monday, tz))
        val h = alarm("FREQ=HOURLY;INTERVAL=2", "2026-11-17T20:00:00Z")
        assertSame(h, h.snappedToRule(monday, tz))
    }

    @Test
    fun unparseableRuleIsUntouched() {
        val a = alarm("FREQ=SOMETIMES;BYDAY=WE", "2026-11-17T20:00:00Z")
        assertSame(a, a.snappedToRule(monday, tz))
    }

    @Test
    fun snapPastUntilLeavesAlarmUnchanged() {
        val a = alarm("FREQ=WEEKLY;BYDAY=WE", "2026-11-19T20:00:00Z", until = "2026-11-17T00:00:00Z")
        assertSame(a, a.snappedToRule(monday, tz))
    }

    @Test
    fun snappedAlarmFiresOnItsFirstDay() {
        val snapped = alarm("FREQ=WEEKLY;BYDAY=WE", "2026-11-19T20:00:00Z").snappedToRule(monday, tz)
        val next = nextRecurringInstant(snapped, monday, tz)
        assertEquals(snapped.dtStart, next)
    }
}
