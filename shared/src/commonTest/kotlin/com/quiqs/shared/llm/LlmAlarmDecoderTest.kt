package com.quiqs.shared.llm

import com.quiqs.shared.Alarm
import com.quiqs.shared.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import com.quiqs.shared.nextRecurringInstant
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** "Now" is Monday 2026-11-16 15:22 in São Paulo (UTC-03, no DST). */
class LlmAlarmDecoderTest {
    private val tz = TimeZone.of("America/Sao_Paulo")
    private val now = Instant.parse("2026-11-16T15:22:00-03:00")
    private val decoder = LlmAlarmDecoder({ now }, tz)

    private fun one(json: String): Alarm = decoder.decodeList("[$json]")!!.single()

    private fun oneOff(json: String) = assertIs<Alarm.OneOffTime>(one(json))
    private fun recurring(json: String) = assertIs<Alarm.RecurringTime>(one(json))

    private fun at(iso: String) = Instant.parse(iso)

    // --- one-off ---

    @Test fun tomorrowAtSeven() =
        assertEquals(at("2026-11-17T07:00:00-03:00"), oneOff("""{"when":"tomorrow","time":"07:00"}""").at)

    @Test fun todayAtFiveInTheAfternoon() =
        assertEquals(at("2026-11-16T17:00:00-03:00"), oneOff("""{"when":"today","time":"17:00"}""").at)

    @Test fun weekdayLaterThisWeek() =
        assertEquals(at("2026-11-18T17:00:00-03:00"), oneOff("""{"when":"wed","time":"17:00"}""").at)

    @Test fun sameWeekdayLaterTodayMeansToday() =
        assertEquals(at("2026-11-16T17:00:00-03:00"), oneOff("""{"when":"mon","time":"17:00"}""").at)

    @Test fun sameWeekdayAlreadyPassedMeansNextWeek() =
        assertEquals(at("2026-11-23T09:00:00-03:00"), oneOff("""{"when":"monday","time":"09:00"}""").at)

    @Test fun explicitDate() =
        assertEquals(at("2026-12-24T20:30:00-03:00"), oneOff("""{"when":"2026-12-24","time":"20:30"}""").at)

    @Test fun missingTimeDefaultsToNine() =
        assertEquals(at("2026-11-17T09:00:00-03:00"), oneOff("""{"when":"tomorrow"}""").at)

    @Test fun amPmIsTolerated() =
        assertEquals(at("2026-11-17T17:00:00-03:00"), oneOff("""{"when":"tomorrow","time":"5pm"}""").at)

    @Test fun relativeMinutes() =
        assertEquals(at("2026-11-16T15:52:00-03:00"), oneOff("""{"in":"30m"}""").at)

    @Test fun relativeCompoundDuration() =
        assertEquals(at("2026-11-16T16:52:00-03:00"), oneOff("""{"in":"1h30m"}""").at)

    // --- recurring ---

    @Test fun multiDayWeekly() {
        val a = recurring("""{"every":"week","days":["mon","wed","fri"],"time":"17:00"}""")
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", a.rule)
        assertEquals(at("2026-11-16T17:00:00-03:00"), a.dtStart) // today, still ahead
        assertEquals(null, a.until)
        assertEquals(null, a.count)
    }

    @Test fun weeklyStartIsSnappedToTheFirstMatchingDay() {
        val a = recurring("""{"every":"week","days":["tue","thu"],"time":"08:00"}""")
        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH", a.rule)
        assertEquals(at("2026-11-17T08:00:00-03:00"), a.dtStart)
    }

    @Test fun dailyTimeAlreadyPassedStartsTomorrow() {
        val a = recurring("""{"every":"day","time":"07:00"}""")
        assertEquals("FREQ=DAILY", a.rule)
        assertEquals(at("2026-11-17T07:00:00-03:00"), a.dtStart)
    }

    @Test fun weeklyWithoutDaysUsesTheFirstFiringWeekday() {
        val a = recurring("""{"every":"week","time":"17:00"}""")
        assertEquals("FREQ=WEEKLY;BYDAY=MO", a.rule)
    }

    @Test fun everyTwoWeeksUntilInclusiveEndOfDay() {
        val a = recurring("""{"every":"week","interval":2,"days":["tue","thu"],"time":"09:00","until":"2027-01-31"}""")
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH", a.rule)
        assertEquals(at("2027-01-31T23:59:59-03:00"), a.until)
    }

    @Test fun countIsKeptAndDroppedWhenUntilIsPresent() {
        assertEquals(5, recurring("""{"every":"day","time":"07:00","count":5}""").count)
        val both = recurring("""{"every":"day","time":"07:00","count":5,"until":"2026-12-01"}""")
        assertEquals(null, both.count)
        assertEquals(at("2026-12-01T23:59:59-03:00"), both.until)
    }

    @Test fun secondSundayOfTheMonth() {
        val a = recurring("""{"every":"month","days":["2sun"],"time":"10:00"}""")
        assertEquals("FREQ=MONTHLY;BYDAY=2SU", a.rule)
        assertEquals(at("2026-12-13T10:00:00-03:00"), a.dtStart)
    }

    @Test fun lastFridayUsesNegativeOrdinal() =
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", recurring("""{"every":"month","days":["-1fri"]}""").rule)

    @Test fun monthDays() {
        val a = recurring("""{"every":"month","monthDays":[1,15],"time":"09:00"}""")
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=1,15", a.rule)
        assertEquals(at("2026-12-01T09:00:00-03:00"), a.dtStart)
    }

    @Test fun monthlyWithoutDayUsesTheStartDay() =
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=16", recurring("""{"every":"month","time":"17:00"}""").rule)

    @Test fun yearlyWithoutDetailUsesTheStartDate() =
        assertEquals("FREQ=YEARLY;BYMONTHDAY=16;BYMONTH=11",
            recurring("""{"every":"year","time":"17:00"}""").rule)

    @Test fun everyTwoHoursStartsOneStepFromNow() {
        val a = recurring("""{"every":"hour","interval":2}""")
        assertEquals("FREQ=HOURLY;INTERVAL=2", a.rule)
        assertEquals(at("2026-11-16T17:22:00-03:00"), a.dtStart)
    }

    @Test fun twelveHourlyStartsAtTonightsSlot() {
        // "every day at 8am and 8pm" at 15:22: next slot is 20:00 today, then 08:00 tomorrow.
        val a = recurring("""{"every":"hour","interval":12,"time":"20:00"}""")
        assertEquals("FREQ=HOURLY;INTERVAL=12", a.rule)
        assertEquals(at("2026-11-16T20:00:00-03:00"), a.dtStart)
        assertEquals(at("2026-11-17T08:00:00-03:00"), nextRecurringInstant(a, a.dtStart, tz))
    }

    @Test fun twelveHourlyIsTheSameWhicheverSlotIsNamed() {
        val a = recurring("""{"every":"hour","interval":12,"time":"08:00"}""")
        assertEquals(at("2026-11-16T20:00:00-03:00"), a.dtStart)
    }

    @Test fun twelveHourlyLateInTheDayStartsTomorrowMorning() {
        val late = LlmAlarmDecoder({ Instant.parse("2026-11-16T21:00:00-03:00") }, tz)
        val a = assertIs<Alarm.RecurringTime>(late.decodeList("""[{"every":"hour","interval":12,"time":"20:00"}]""")!!.single())
        assertEquals(at("2026-11-17T08:00:00-03:00"), a.dtStart)
    }

    @Test fun weekdaysAlias() {
        val a = recurring("""{"every":"week","days":["weekdays"],"time":"09:00"}""")
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", a.rule)
    }

    @Test fun weekendAlias() =
        assertEquals("FREQ=WEEKLY;BYDAY=SA,SU", recurring("""{"every":"week","days":["weekend"]}""").rule)

    @Test fun monthlyPlainWeekdayIsRejectedInsteadOfBecomingEverySunday() {
        assertEquals(null, decoder.decodeList("""[{"every":"month","days":["sun"],"time":"10:00"}]"""))
    }

    @Test fun monthlyPlainWeekdayIsFineWithAMonthDay() =
        assertEquals(
            "FREQ=MONTHLY;BYDAY=SU;BYMONTHDAY=8,9,10,11,12,13,14",
            recurring("""{"every":"month","days":["sun"],"monthDays":[8,9,10,11,12,13,14]}""").rule,
        )

    @Test fun everyWeekdaysShorthand() {
        val a = recurring("""{"every":"weekdays","time":"09:00"}""")
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", a.rule)
    }

    @Test fun everyCountAndUnitShorthand() {
        val a = recurring("""{"every":"3d","time":"19:00"}""")
        assertEquals("FREQ=DAILY;INTERVAL=3", a.rule)
        assertEquals(at("2026-11-16T19:00:00-03:00"), a.dtStart)
        assertEquals("FREQ=HOURLY;INTERVAL=12", recurring("""{"every":"12h"}""").rule)
    }

    @Test fun everyWeekdayNameShorthand() =
        assertEquals("FREQ=WEEKLY;BYDAY=MO", recurring("""{"every":"monday","time":"17:00"}""").rule)

    @Test fun everyMonthIsNotMistakenForMonday() =
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=16", recurring("""{"every":"month","time":"17:00"}""").rule)

    @Test fun untilInThePastMovesToNextYear() {
        val a = recurring("""{"every":"week","days":["tue"],"time":"08:00","until":"2026-01-31"}""")
        assertEquals(at("2027-01-31T23:59:59-03:00"), a.until)
    }

    @Test fun explicitDateInThePastMovesToNextYear() =
        assertEquals(at("2027-03-05T09:00:00-03:00"), oneOff("""{"when":"2026-03-05","time":"09:00"}""").at)

    @Test fun commandFieldsMisplacedInAlarmsAreIgnored() {
        val list = decoder.decodeList("""[{"when":"2026-12-20","time":"09:00"},{"priority":"high"}]""")!!
        assertEquals(1, list.size)
    }

    @Test fun portugueseDayWords() {
        assertEquals(at("2026-11-17T08:00:00-03:00"), oneOff("""{"when":"amanhã","time":"08:00"}""").at)
        assertEquals(at("2026-11-16T17:00:00-03:00"), oneOff("""{"when":"hoje","time":"17:00"}""").at)
        assertEquals(at("2026-11-20T07:00:00-03:00"), oneOff("""{"when":"sexta","time":"07:00"}""").at)
        assertEquals(at("2026-11-21T07:00:00-03:00"), oneOff("""{"when":"sábado","time":"07:00"}""").at)
    }

    @Test fun portugueseRecurrenceWords() {
        assertEquals("FREQ=DAILY", recurring("""{"every":"dia","time":"07:00"}""").rule)
        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH", recurring("""{"every":"semana","days":["terça","quinta"]}""").rule)
        assertEquals("FREQ=WEEKLY;BYDAY=SU", recurring("""{"every":"domingo","time":"18:00"}""").rule)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=16", recurring("""{"every":"mês","time":"17:00"}""").rule)
    }

    // --- compatibility and errors ---

    @Test fun fullWireFormStillDecodes() {
        val a = oneOff("""{"type":"oneOffTime","at":"2026-12-24T10:00:00-03:00"}""")
        assertEquals(at("2026-12-24T13:00:00Z"), a.at)
    }

    @Test fun malformedAlarmFailsInsteadOfBeingDropped() {
        assertEquals(null, decoder.decodeList("""[{"banana":1}]"""))
        assertEquals(null, decoder.decodeList("""[{"when":"someday","time":"09:00"}]"""))
        assertEquals(null, decoder.decodeList("""[{"every":"fortnight"}]"""))
    }

    // --- through the command parser ---

    @Test fun parserResolvesCompactAlarmsAndAppliesDefaults() {
        val cmd = LlmCommandParser(decoder).parse(
            """{"type":"create","text":"Take out the trash","alarms":[{"every":"week","days":["mon","wed","fri"],"time":"17:00"}]}""",
        ).getOrThrow()
        val create = assertIs<QCommand.Create>(cmd)
        assertEquals("Take out the trash", create.text)
        assertEquals(Priority.MEDIUM, create.priority)
        assertEquals(emptyList(), create.tags)
        val alarm = assertIs<Alarm.RecurringTime>(create.alarms.single())
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", alarm.rule)
    }

    @Test fun parserWithoutDecoderStillReadsTheFullForm() {
        val cmd = LlmCommandParser().parse(
            """{"type":"create","text":"x","alarms":[{"type":"oneOffTime","at":"2026-12-24T10:00:00-03:00"}]}""",
        ).getOrThrow()
        assertIs<Alarm.OneOffTime>(assertIs<QCommand.Create>(cmd).alarms.single())
    }

    @Test fun parserRepairsAMismatchedClosingBracket() {
        // Real model output: "]}" where "}]}" was meant, plus a stray count next to until.
        val cmd = LlmCommandParser(decoder).parse(
            """{"type":"create","text":"Water the plants","alarms":[{"every":"week","days":["tue","thu"],"time":"08:00","until":"2027-01-31","count":1]}""",
        ).getOrThrow()
        val alarm = assertIs<Alarm.RecurringTime>(assertIs<QCommand.Create>(cmd).alarms.single())
        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH", alarm.rule)
        assertEquals(null, alarm.count)
    }

    @Test fun parserRepairsAnUnclosedObject() {
        val cmd = LlmCommandParser(decoder).parse("""{"type":"create","text":"Buy milk"""").getOrThrow()
        assertEquals("Buy milk", assertIs<QCommand.Create>(cmd).text)
    }

    @Test fun parserReportsABadAlarmAsAFailure() {
        val r = LlmCommandParser(decoder).parse("""{"type":"create","text":"x","alarms":[{"every":"fortnight"}]}""")
        assertEquals(true, r.isFailure)
    }

    // --- streaming ---

    @Test fun streamingExtractorEmitsResolvedAlarms() {
        val updates = mutableListOf<StreamingFieldUpdate>()
        val extractor = StreamingCommandExtractor(decoder)
        listOf("""{"type":"create","text":"Pay rent","alarms":[{"every":"mon""", """th","monthDays":[5]}],"pri""").forEach {
            extractor.accept(it) { u -> updates += u }
        }
        val alarms = updates.filterIsInstance<StreamingFieldUpdate.Alarms>().single().value
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=5", assertIs<Alarm.RecurringTime>(alarms.single()).rule)
    }

    @Test fun unknownEveryValueThrowsFromDecodeOne() {
        assertFailsWith<IllegalArgumentException> {
            decoder.resolveAlarmsIn(kotlinx.serialization.json.buildJsonObject {
                put("alarms", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("every", kotlinx.serialization.json.JsonPrimitive("fortnight"))
                    })
                })
            })
        }
    }
}
