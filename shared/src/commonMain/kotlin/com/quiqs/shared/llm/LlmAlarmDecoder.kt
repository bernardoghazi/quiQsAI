package com.quiqs.shared.llm

import com.quiqs.shared.Alarm
import com.quiqs.shared.snappedToRule
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns the compact alarm notation the LLM is asked to emit into real [Alarm]s.
 *
 * Writing a full ISO timestamp or an RRULE costs the model many slow output tokens and
 * it often gets the calendar wrong (wrong weekday, wrong UTC offset). Instead it says
 * *what the user said* and this class does the date math:
 *
 * ```
 * one-off:   {"when":"today|tomorrow|mon..sun|YYYY-MM-DD","time":"HH:mm"}
 *            {"in":"30m"}  (also "2h", "3d", "1h30m")
 * recurring: {"every":"minute|hour|day|week|month|year","interval":2,
 *             "days":["mon","wed"],"monthDays":[1,15],"months":[6],
 *             "time":"17:00","until":"YYYY-MM-DD","count":10}
 * ```
 *
 * Details:
 *  - `time` is local 24 h `HH:mm` (`5pm` is tolerated) and defaults to 09:00.
 *  - `days` entries may carry an ordinal for monthly/yearly rules: `2sun`, `-1fri`.
 *  - `days` also accepts `weekdays` (Mon–Fri) and `weekend` (Sat, Sun).
 *  - In a monthly/yearly rule a plain weekday ("sun") is rejected: it would silently mean
 *    "every Sunday". Use an ordinal ("2sun") or `monthDays`.
 *  - For `minute`/`hour` rules `time` marks any slot of the pattern; the first firing is
 *    the earliest slot after now (`{"every":"hour","interval":12,"time":"20:00"}` at 15:22
 *    starts at 20:00, and at 21:00 starts at 08:00 tomorrow).
 *  - A weekly rule with no `days` repeats on the weekday of its first firing; a monthly
 *    one on that day of the month; a yearly one on that date.
 *  - The first firing is the first matching day at or after today whose time is still
 *    ahead (see [snappedToRule]). `until` is inclusive: end of that local day.
 *  - Objects that carry a `"type"` are the full wire form of [Alarm] and are decoded
 *    as-is, so older output and echoes of existing Qs keep working.
 *
 * Malformed compact alarms throw [IllegalArgumentException] rather than being dropped,
 * so a bad model answer surfaces as a parse failure instead of a Q silently missing its
 * alarm.
 *
 * @param clock Source of "now"; injectable for tests.
 * @param timeZone Zone the user's words are interpreted in.
 */
class LlmAlarmDecoder(
    private val clock: () -> Instant,
    private val timeZone: TimeZone,
) {
    /**
     * Decodes a JSON array of alarms (compact or full form). Returns `null` if
     * [rawJson] isn't valid JSON or any entry is malformed.
     */
    fun decodeList(rawJson: String): List<Alarm>? =
        runCatching { decodeArray(json.parseToJsonElement(rawJson).jsonArray) }.getOrNull()

    /**
     * Returns [command] with its `alarms` array (if any) rewritten into the full wire
     * form, ready for the regular [QCommand] serializer.
     */
    fun resolveAlarmsIn(command: JsonObject): JsonObject {
        val alarms = command["alarms"] as? JsonArray ?: return command
        val resolved = decodeArray(alarms)
        val encoded: JsonElement = json.encodeToJsonElement(ListSerializer(Alarm.serializer()), resolved)
        return JsonObject(command + ("alarms" to encoded))
    }

    private fun decodeArray(array: JsonArray): List<Alarm> =
        array.map { it.jsonObject }
            // The model sometimes drops a command-level field ("priority", "tags"…) into the
            // alarms array. That is not an alarm and is already (or not) set where it belongs.
            .filterNot { o -> o.isNotEmpty() && o.keys.all { it in COMMAND_FIELDS } }
            .map(::decodeOne)

    private fun decodeOne(o: JsonObject): Alarm = when {
        "type" in o -> json.decodeFromJsonElement(Alarm.serializer(), o)
        "every" in o -> recurring(o)
        "in" in o -> relative(o)
        "when" in o || "time" in o -> absolute(o)
        else -> throw IllegalArgumentException("Unrecognised alarm: $o")
    }

    // --- one-off ---

    private fun absolute(o: JsonObject): Alarm {
        val now = clock()
        val (hour, minute) = parseTime(o.string("time"))
        val day = o.string("when")?.lowercase()?.trim() ?: "today"
        val date = resolveDay(day, hour, minute, now)
        return Alarm.OneOffTime(at = date.atTime(hour, minute).toInstant(timeZone))
    }

    private fun relative(o: JsonObject): Alarm {
        val text = o.string("in") ?: throw IllegalArgumentException("Alarm 'in' is empty")
        val parts = DURATION_PART.findAll(text.lowercase()).toList()
        require(parts.isNotEmpty()) { "Can't read duration '$text' (use e.g. 30m, 2h, 1d)" }
        var total = Duration.ZERO
        for (p in parts) {
            val n = p.groupValues[1].toLong()
            total += when (p.groupValues[2]) {
                "d" -> n.days
                "h" -> n.hours
                else -> n.minutes
            }
        }
        val target = clock() + total
        return Alarm.OneOffTime(at = Instant.fromEpochSeconds(target.epochSeconds / 60 * 60))
    }

    // --- recurring ---

    private fun recurring(o: JsonObject): Alarm {
        val now = clock()
        val every = parseEvery(o.string("every"))
        val freq = every.freq
        val interval = (o.int("interval") ?: every.interval ?: 1).coerceAtLeast(1)
        val (hour, minute) = parseTime(o.string("time"))
        val hasTime = o.string("time") != null

        val byDay = (o["days"] as? JsonArray).orEmpty().flatMap { toByDays(it.jsonPrimitive.content) }.toMutableList()
        val byMonthDay = (o["monthDays"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content.toInt() }.toMutableList()
        val byMonth = (o["months"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content.toInt() }.toMutableList()

        if (byDay.isEmpty()) byDay += every.days

        val anchor: Instant
        if (freq == "MINUTELY" || freq == "HOURLY") {
            val step = if (freq == "MINUTELY") interval.minutes else interval.hours
            anchor = if (hasTime) {
                firstSlotAfterNow(hour, minute, step, now)
            } else {
                Instant.fromEpochSeconds((now + step).epochSeconds / 60 * 60)
            }
        } else {
            anchor = nextLocal(hour, minute, now)
            val first = anchor.toLocalDateTime(timeZone).date
            // The occurrence engine treats an unconstrained WEEKLY/MONTHLY/YEARLY rule as
            // "every day of the cycle", so spell out the day that RFC 5545 implies.
            when (freq) {
                "WEEKLY" -> if (byDay.isEmpty()) byDay += weekdayCode(first.dayOfWeek)
                "MONTHLY" -> if (byDay.isEmpty() && byMonthDay.isEmpty()) byMonthDay += first.day
                "YEARLY" -> {
                    if (byMonth.isEmpty()) byMonth += first.month.number
                    if (byDay.isEmpty() && byMonthDay.isEmpty()) byMonthDay += first.day
                }
            }
        }

        if ((freq == "MONTHLY" || freq == "YEARLY") && byMonthDay.isEmpty() &&
            byDay.any { !it.first().let { c -> c.isDigit() || c == '-' } }
        ) {
            throw IllegalArgumentException(
                "Monthly/yearly 'days' need an ordinal like 2sun or -1fri (use monthDays for dates)",
            )
        }

        val rule = buildString {
            append("FREQ=").append(freq)
            if (interval > 1) append(";INTERVAL=").append(interval)
            if (byDay.isNotEmpty()) append(";BYDAY=").append(byDay.joinToString(","))
            if (byMonthDay.isNotEmpty()) append(";BYMONTHDAY=").append(byMonthDay.joinToString(","))
            if (byMonth.isNotEmpty()) append(";BYMONTH=").append(byMonth.joinToString(","))
        }

        val until = o.string("until")?.let { endOfDay(notInThePast(parseDate(it))) }
        // `until` and `count` are mutually exclusive; `until` wins if the model sends both.
        val count = if (until == null) o.int("count")?.takeIf { it > 0 } else null

        return Alarm.RecurringTime(rule = rule, dtStart = anchor, until = until, count = count)
            .snappedToRule(now, timeZone)
    }

    // --- helpers ---

    /** Today at [hour]:[minute] if that is still ahead of [now], otherwise tomorrow. */
    private fun nextLocal(hour: Int, minute: Int, now: Instant): Instant {
        val today = now.toLocalDateTime(timeZone).date
        val todayAt = today.atTime(hour, minute).toInstant(timeZone)
        return if (todayAt > now) todayAt else today.plus(1, DateTimeUnit.DAY).atTime(hour, minute).toInstant(timeZone)
    }

    /**
     * The earliest slot of the pattern `hour:minute + k * step` (k any integer) that is
     * after [now]. Lets "8am and 8pm" be written as one 12-hourly alarm at either time.
     */
    private fun firstSlotAfterNow(hour: Int, minute: Int, step: Duration, now: Instant): Instant {
        var slot = now.toLocalDateTime(timeZone).date.atTime(hour, minute).toInstant(timeZone)
        if (slot > now) {
            while (slot - step > now) slot -= step
        } else {
            while (slot <= now) slot += step
        }
        return slot
    }

    private fun resolveDay(word: String, hour: Int, minute: Int, now: Instant): LocalDate {
        val today = now.toLocalDateTime(timeZone).date
        when (word) {
            "today", "tonight", "hoje" -> return today
            "tomorrow", "amanhã", "amanha" -> return today.plus(1, DateTimeUnit.DAY)
        }
        weekdayOf(word)?.let { target ->
            // Next such weekday; today counts only if the time is still ahead.
            for (i in 0..7) {
                val d = today.plus(i, DateTimeUnit.DAY)
                if (d.dayOfWeek == target && d.atTime(hour, minute).toInstant(timeZone) > now) return d
            }
        }
        return notInThePast(parseDate(word))
    }

    /**
     * A date the model wrote that is already behind us almost always has the wrong year
     * ("end of January" said in November → 2026-01-31 instead of 2027). Moves it forward by
     * whole years until it is today or later, at most twice.
     */
    private fun notInThePast(date: LocalDate): LocalDate {
        val today = clock().toLocalDateTime(timeZone).date
        var d = date
        repeat(2) { if (d < today) d = d.plus(1, DateTimeUnit.YEAR) }
        return d
    }

    private fun parseDate(text: String): LocalDate =
        runCatching { LocalDate.parse(text.trim()) }.getOrElse {
            throw IllegalArgumentException("Can't read day '$text' (use today, tomorrow, a weekday, or YYYY-MM-DD)")
        }

    private fun endOfDay(date: LocalDate): Instant = date.atTime(23, 59, 59).toInstant(timeZone)

    private fun parseTime(text: String?): Pair<Int, Int> {
        if (text == null) return DEFAULT_TIME
        val m = TIME.matchEntire(text.trim().lowercase())
            ?: throw IllegalArgumentException("Can't read time '$text' (use HH:mm)")
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        when (m.groupValues[3]) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        require(hour in 0..23 && minute in 0..59) { "Time out of range: '$text'" }
        return hour to minute
    }

    /** What an `every` value means: a frequency, plus an interval and weekdays if it implies them. */
    private class Every(val freq: String, val interval: Int? = null, val days: List<String> = emptyList())

    /**
     * Reads `every`. Besides the plain frequencies it tolerates the shorthands the model
     * actually produces: `weekdays`, `weekend`, a weekday name (`monday`), and a count
     * plus unit (`3d`, `2w`, `12h`).
     */
    private fun parseEvery(text: String?): Every {
        val t = text?.lowercase()?.trim() ?: throw IllegalArgumentException("Alarm 'every' is empty")
        when (t) {
            "weekday", "weekdays" -> return Every("WEEKLY", days = listOf("MO", "TU", "WE", "TH", "FR"))
            "weekend", "weekends" -> return Every("WEEKLY", days = listOf("SA", "SU"))
        }
        freqOrNull(t)?.let { return Every(it) }
        COUNT_UNIT.matchEntire(t)?.let { m ->
            val unit = when (m.groupValues[2]) {
                "m", "min", "mins" -> "MINUTELY"
                "h", "hr", "hrs" -> "HOURLY"
                "d" -> "DAILY"
                "w", "wk", "wks" -> "WEEKLY"
                "mo", "mos" -> "MONTHLY"
                "y", "yr", "yrs" -> "YEARLY"
                else -> null
            }
            if (unit != null) return Every(unit, interval = m.groupValues[1].toInt())
        }
        // A weekday name, but only a real prefix of one: "month" must not mean Monday.
        if (t.length >= 3 && WEEKDAY_NAMES.any { it.startsWith(t) }) {
            weekdayOf(t)?.let { return Every("WEEKLY", days = listOf(weekdayCode(it))) }
        }
        throw IllegalArgumentException("Unknown 'every' value '$text'")
    }

    private fun freqOrNull(t: String): String? = when (t) {
        "minute", "minutes", "minutely", "minuto", "minutos" -> "MINUTELY"
        "hour", "hours", "hourly", "hora", "horas" -> "HOURLY"
        "day", "days", "daily", "dia", "dias", "diário", "diario" -> "DAILY"
        "week", "weeks", "weekly", "semana", "semanas", "semanal" -> "WEEKLY"
        "month", "months", "monthly", "mês", "mes", "meses", "mensal" -> "MONTHLY"
        "year", "years", "yearly", "ano", "anos", "anual" -> "YEARLY"
        else -> null
    }

    /** `mon` → `MO`, `2sun` → `2SU`, `-1fri` → `-1FR`; `weekdays` and `weekend` expand. */
    private fun toByDays(token: String): List<String> = when (token.trim().lowercase()) {
        "weekday", "weekdays" -> listOf("MO", "TU", "WE", "TH", "FR")
        "weekend", "weekends" -> listOf("SA", "SU")
        else -> listOf(toByDay(token))
    }

    /** `mon` → `MO`, `2sun` → `2SU`, `-1fri` → `-1FR`. */
    private fun toByDay(token: String): String {
        val m = BYDAY_TOKEN.matchEntire(token.trim().lowercase())
            ?: throw IllegalArgumentException("Can't read day '$token'")
        val day = weekdayOf(m.groupValues[2])
            ?: throw IllegalArgumentException("Unknown weekday '$token'")
        return m.groupValues[1] + weekdayCode(day)
    }

    /** English weekday names and abbreviations, plus Portuguese (segunda, terça, quarta…). */
    private fun weekdayOf(word: String): DayOfWeek? {
        // Brazilian "2a feira" … "6a feira" (also 2ª, 2º, "6a-feira"): Monday … Friday.
        ORDINAL_FEIRA.matchEntire(word.trim().lowercase())?.let {
            return DayOfWeek(it.groupValues[1].toInt() - 1)
        }
        return weekdayByPrefix(word)
    }

    private fun weekdayByPrefix(word: String): DayOfWeek? = when (word.take(3)) {
        "mon", "seg" -> DayOfWeek.MONDAY
        "tue", "ter" -> DayOfWeek.TUESDAY
        "wed", "qua" -> DayOfWeek.WEDNESDAY
        "thu", "qui" -> DayOfWeek.THURSDAY
        "fri", "sex" -> DayOfWeek.FRIDAY
        "sat", "sab", "sáb" -> DayOfWeek.SATURDAY
        "sun", "dom" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun weekdayCode(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "MO"
        DayOfWeek.TUESDAY -> "TU"
        DayOfWeek.WEDNESDAY -> "WE"
        DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"
        DayOfWeek.SATURDAY -> "SA"
        DayOfWeek.SUNDAY -> "SU"
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            isLenient = true
        }
        val COMMAND_FIELDS = setOf("priority", "tags", "text", "completed")
        val DEFAULT_TIME = 9 to 0
        val TIME = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")
        val DURATION_PART = Regex("""(\d+)\s*(d|h|m)""")
        val ORDINAL_FEIRA = Regex("""([2-6])\s*[aªº]?[\s-]*feira""")
        val BYDAY_TOKEN = Regex("""(-?\d+)?([^\d\s-]+)""")
        val COUNT_UNIT = Regex("""(\d+)\s*([^\d\s]+)""")
        val WEEKDAY_NAMES = listOf(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "segunda", "terça", "terca", "quarta", "quinta", "sexta", "sábado", "sabado", "domingo",
        )
    }
}
