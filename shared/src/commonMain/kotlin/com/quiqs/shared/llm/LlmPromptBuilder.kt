@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared.llm

import com.quiqs.shared.Alarm
import com.quiqs.shared.Priority
import com.quiqs.shared.Q
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi

/**
 * Serialisation shape used when passing a [Q] into the model (in edit prompts) or
 * returning it from a query tool. Drops the bookkeeping fields `createdAt` / `updatedAt`
 * that the model doesn't need.
 */
@Serializable
data class QFullSnapshot(
    val id: String,
    val text: String,
    val tags: List<String>,
    val alarms: List<Alarm>,
    val priority: Priority,
    val completed: Boolean,
)

/** Convert a domain [Q] to the wire snapshot. */
fun Q.toFullSnapshot(): QFullSnapshot = QFullSnapshot(
    id = id.toString(),
    text = text,
    tags = tags,
    alarms = alarms,
    priority = priority,
    completed = completed,
)

private val promptJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
}

/**
 * Builds the system and user prompts fed into [LlmEngine.generate] for each UI mode.
 *
 * System prompts are split by mode (new-or-filter / edit / query) rather than a single
 * monolithic instruction — each mode only gets the rules and examples relevant to it,
 * keeping prefill cost well under the model's 4096-token context window.
 *
 * @property clock Injectable time source — tests can freeze time without stubbing [kotlin.time.Clock].
 * @property timeZone Zone used to format the alarm offsets in the user's locale.
 */
class LlmPromptBuilder(
    private val clock: () -> Instant,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    /** System instruction for the combined "new Q or filter+sort" mode. */
    val systemForNewOrFilter: String get() = SYSTEM_NEW_OR_FILTER

    /** System instruction for edit-mode prompts. */
    val systemForEdit: String get() = SYSTEM_EDIT

    /** System instruction for query-mode prompts (includes the tool contract). */
    val systemForQuery: String get() = SYSTEM_QUERY

    /** Build a user prompt that just wraps typed input (used by query and new-or-filter). */
    fun buildPromptText(userPrompt: String): String =
        buildBase {
            appendLine("User prompt:")
            appendLine(userPrompt)
        }

    /** Build a user prompt that defers to an audio attachment (used by query and new-or-filter). */
    fun buildPromptAudio(): String =
        buildBase {
            appendLine(AUDIO_INSTRUCTION)
        }

    /** Build the user prompt for editing [q] from typed text. */
    fun buildForEdit(q: Q, userPrompt: String): String =
        buildBase {
            appendLine("Q being edited:")
            appendLine(promptJson.encodeToString(QFullSnapshot.serializer(), q.toFullSnapshot()))
            appendLine()
            appendLine("User prompt:")
            appendLine(userPrompt)
        }

    /** Build the user prompt for editing [q] from audio. */
    fun buildForEditAudio(q: Q): String =
        buildBase {
            appendLine("Q being edited:")
            appendLine(promptJson.encodeToString(QFullSnapshot.serializer(), q.toFullSnapshot()))
            appendLine()
            appendLine(AUDIO_INSTRUCTION)
        }

    /**
     * Shared prelude that every user prompt starts with: current time and the
     * device's time zone offset, so the model can resolve relative times like "5pm"
     * into absolute ISO-8601 strings.
     */
    private inline fun buildBase(userSection: StringBuilder.() -> Unit): String {
        val now = clock()
        val offset = timeZone.offsetAt(now)
        val localNow = now.toLocalDateTime(timeZone)
        // Format the prelude time at second precision (no nanoseconds). LocalDateTime's
        // default toString includes any sub-second component the clock happens to carry,
        // and the model would mirror that into the alarms it emits — producing instants
        // padded with fractional-second digits the downstream serializer chokes on.
        val isoNow = buildString {
            append(localNow.date)
            append('T')
            append(localNow.hour.toString().padStart(2, '0'))
            append(':')
            append(localNow.minute.toString().padStart(2, '0'))
            append(':')
            append(localNow.second.toString().padStart(2, '0'))
        }
        return buildString {
            appendLine("Current time: $isoNow$offset")
            appendLine("Time zone: $timeZone")
            appendLine()
            userSection()
        }
    }

    companion object {
        private val BASE = """
            You are quiQs's backend. A Q is a note with tags and alarms.
            Output exactly one JSON command.
        """.trimIndent()

        private const val AUDIO_INSTRUCTION =
            "The user's prompt is provided as an audio attachment. Transcribe it silently and treat the transcription as the user prompt."

        private val ALARMS_AND_TIME_RULES = """
            Alarms (every date-time is a string exactly like "2026-05-17T17:00:00-03:00"):
              {"type":"oneOffTime","at":"2026-05-17T17:00:00-03:00"}
              {"type":"recurringTime","rule":"FREQ=DAILY","dtStart":"2026-05-17T08:00:00-03:00","until":"2026-12-31T23:00:00-03:00","count":null}
              {"type":"geofence","latitude":<num>,"longitude":<num>,"radiusMeters":<num>,"activeUntil":"2026-12-31T23:00:00-03:00"}

            Time format (STRICT — copy this layout character-for-character):
            - Each date-time is EXACTLY 25 characters in this order:
              `YYYY-MM-DD` (4-digit year, hyphen, 2-digit month, hyphen, 2-digit day)
              then literal `T`
              then `HH:MM:SS` (2-digit hour 00–23, colon, 2-digit minute, colon, `00` for seconds)
              then offset `±HH:MM` from now+tz (e.g. `-03:00`, `+05:30`; never `Z` / UTC).
            - Hyphens and colons are REQUIRED separators. Do not concatenate digits.
            - Do not add fractional seconds, nanoseconds, or any extra digits anywhere.

            Wrong (NEVER emit):
              "20260518T160000-0300"      (missing hyphens, colons)
              "202020518T160000-0300"     (year has too many digits)
              "2026-05-18T17:00:00.000-03:00"  (fractional seconds)
              "17:00:00-03:00"            (missing date)
              "2026-05-18T17:00:00Z"      (UTC — must use local offset)
            Right (always like these):
              "2026-05-18T17:00:00-03:00"
              "2026-12-31T09:30:00+05:30"

            Time resolution:
            - Resolve relative times against the "Current time" provided in the user prompt.
            - Example: Current time is "2026-05-17T08:30:00-03:00". User says "5pm today" → "2026-05-17T17:00:00-03:00".
            - Example: Current time is "2026-05-17T08:30:00-03:00". User says "tomorrow at 9am" → "2026-05-18T09:00:00-03:00".

            Recurring alarm rules:
            - `dtStart` is REQUIRED — the first firing of the rule. The rule expands from there.
            - `until` and `count` are mutually exclusive: set at most one. Both null means recur forever.
            - The `rule` is the RRULE body only. Do NOT put DTSTART, UNTIL, or COUNT inside it.
            - Supported RRULE parts: FREQ (MINUTELY|HOURLY|DAILY|WEEKLY|MONTHLY|YEARLY),
              INTERVAL, BYDAY (MO/TU/WE/TH/FR/SA/SU; for MONTHLY/YEARLY a leading int picks
              the Nth weekday, e.g. `2SU`=2nd Sunday, `-1FR`=last Friday), BYMONTH,
              BYMONTHDAY, BYHOUR, BYMINUTE. If BYHOUR/BYMINUTE are omitted, occurrences fire
              at dtStart's local hour:minute.

            Recurring rule examples (omit dtStart/until/count from the rule itself):
              "every 90 min from 8am to 10pm on weekdays" →
                rule "FREQ=MINUTELY;INTERVAL=90;BYHOUR=8,9,10,11,12,13,14,15,16,17,18,19,20,21;BYDAY=MO,TU,WE,TH,FR"
              "every 3 hours from 8am to 10pm" →
                rule "FREQ=HOURLY;INTERVAL=3;BYHOUR=8,9,10,11,12,13,14,15,16,17,18,19,20,21"
              "every other week on Mon/Wed/Fri" → rule "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR"
              "the 15th of every 2 months"      → rule "FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=15"
              "second Sunday every 2 months"    → rule "FREQ=MONTHLY;INTERVAL=2;BYDAY=2SU"
              "Nov 20 every 2 years"            → rule "FREQ=YEARLY;INTERVAL=2;BYMONTH=11;BYMONTHDAY=20"
              "daily, ending after 10 times"    → rule "FREQ=DAILY", count 10
        """.trimIndent()

        private val PRIORITY_RULES = """
            Priority: "high" | "medium" | "low". Default "medium" if the user doesn't signal urgency.
            - "urgent", "asap", "critical", "important" → "high"
            - "whenever", "low priority", "no rush" → "low"
        """.trimIndent()

        private val SYSTEM_EDIT = """
            $BASE

            Commands:
              {"type":"update","id":"<uuid>","text":"...","tags":[],"alarms":[],"priority":"high|medium|low","completed":true|false}
              {"type":"delete","id":"<uuid>"}

            $ALARMS_AND_TIME_RULES

            $PRIORITY_RULES

            Completed:
            - Set "completed":true when the user signals the Q is done ("done", "I did it", "mark complete", "já fiz").
            - Set "completed":false to reopen ("undo", "not done yet").

            Content rules:
            - Do not translate the user's language in "text".
            - Use the id from "Q being edited".
            - On update, omit unchanged fields.
        """.trimIndent()

        private val SYSTEM_NEW_OR_FILTER = """
            $BASE

            Output exactly one of two commands based on user intent.

            To create a new Q:
              {"type":"create","text":"...","tags":[],"alarms":[],"priority":"medium"}

            To filter/sort the existing Q list:
              {"type":"filterSort","spec":{
                "text": <string|null>,
                "tags": [<string>...],
                "priorities": [<"high"|"medium"|"low">...],
                "alarm": <"any"|"none"|"any_set"|"recurring"|"one_off"|"geofence">,
                "alarmWindow": <"any"|"today"|"tomorrow"|"this_week"|"next_week">,
                "completed": <"any"|"only_open"|"only_done">,
                "sortBy": <"tags"|"priority"|"created_at"|"updated_at"|"alarm">,
                "sortDir": <"asc"|"desc">
              }}

            Intent disambiguation (decide first, then emit):
            - Create when the user names a NEW task/note/reminder, often with a time, place,
              or action verb directed at themselves. Cues: "remind me to...", "add/create a Q to...".
            - FilterSort when the user describes how to slice or order the EXISTING list.
              Cues: "show", "hide", "filter", "sort by", "newest first", "high priority only".
            - If ambiguous (e.g. a bare noun phrase like "groceries"), prefer "filterSort" —
              creating an empty-detail Q is worse than narrowing the list.

            $ALARMS_AND_TIME_RULES

            $PRIORITY_RULES

            FilterSort field meaning:
            - text: case-insensitive substring matched against Q text.
            - tags: only Qs whose tags contain at least one (case-insensitive).
            - priorities: only Qs whose priority is in the list.
            - alarm: filter by alarm shape (any=no filter, none=no alarms, any_set=has alarms,
              recurring/one_off/geofence=has that kind).
            - alarmWindow: keep only Qs whose next alarm fires within a relative window —
              today/tomorrow/this_week/next_week. Stored as a token, re-resolved against
              the current day on every evaluation, so the result stays "always right".
              Weeks start Sunday. any=no window filter.
            - completed: only_open hides done Qs; only_done shows only done Qs; any=both.
            FilterSort defaults: text=null, tags=[], priorities=[], alarm="any",
            alarmWindow="any", completed="only_open", sortBy="created_at", sortDir="desc".
            Omit fields the user did not mention. Empty spec {} clears any active filter.

            FilterSort mapping shortcuts:
            - "high priority" / "urgent" → priorities=["high"]
            - "completed" / "done" → completed="only_done"; "open"/"todo" → "only_open"
            - "no alarms" → alarm="none"; "with alarms" → alarm="any_set"
            - "recurring" → alarm="recurring"; "one-off" → alarm="one_off"
            - "today" / "today's Qs" / "due today" → alarmWindow="today"
            - "tomorrow" / "tomorrow's Qs" → alarmWindow="tomorrow"
            - "this week" → alarmWindow="this_week"
            - "next week" → alarmWindow="next_week"
            - "next alarm" / "upcoming" / "soonest" → sortBy="alarm", sortDir="asc"
            - "newest first" → sortBy="created_at", sortDir="desc"
            - "oldest first" → sortBy="created_at", sortDir="asc"
            - "recently edited" → sortBy="updated_at", sortDir="desc"
            - "by priority" → sortBy="priority", sortDir="asc" (high first)
            - "by tag" / "group by tag" → sortBy="tags", sortDir="asc"
            - "clear" / "reset filters" → emit empty spec {}

            Create content rules:
            - Do not translate the user's language in "text".
            - "text" is the note content only; strip wrappers like "remind me to", "cue me to",
              "me lembra de".
            - Capitalize the first word.

            Examples (assume Current time is "2026-05-17T08:30:00-03:00"):
              "Cue me at 5pm to pick up Stella"
              → {"type":"create","text":"Pick up Stella","tags":[],"alarms":[{"type":"oneOffTime","at":"2026-05-17T17:00:00-03:00"}],"priority":"medium"}

              "Urgent: call the doctor"
              → {"type":"create","text":"Call the doctor","tags":[],"alarms":[],"priority":"high"}

              "Remind me to drink water every 2 hours from 8am to 8pm on weekdays"
              → {"type":"create","text":"Drink water","tags":[],"alarms":[{"type":"recurringTime","rule":"FREQ=HOURLY;INTERVAL=2;BYHOUR=8,9,10,11,12,13,14,15,16,17,18,19,20;BYDAY=MO,TU,WE,TH,FR","dtStart":"2026-05-17T08:00:00-03:00","until":null,"count":null}],"priority":"medium"}

              "show only high priority"
              → {"type":"filterSort","spec":{"priorities":["high"]}}

              "sort by next alarm"
              → {"type":"filterSort","spec":{"sortBy":"alarm","sortDir":"asc"}}

              "hide done tasks"
              → {"type":"filterSort","spec":{"completed":"only_open"}}

              "show me today's Qs"
              → {"type":"filterSort","spec":{"alarmWindow":"today"}}

              "what do I have next week"
              → {"type":"filterSort","spec":{"alarmWindow":"next_week"}}

              "clear filters"
              → {"type":"filterSort","spec":{}}
        """.trimIndent()

        private val SYSTEM_QUERY = """
            $BASE

            You have tools to look up the user's Qs:
              search_qs(query): find Qs by case-insensitive substring in text or tags
              list_all_qs(): list all Qs; use when search is unhelpful or the question is about priority/counts/completion

            Each returned Q has: id, text, tags, alarms, priority ("high"|"medium"|"low"), completed (true|false).

            Call tools to fetch data, then respond with exactly:
              {"type":"answer","text":"..."}

            Reference only ids returned by tools.
        """.trimIndent()
    }
}
