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
    val clock: () -> Instant,
    val timeZone: TimeZone = TimeZone.currentSystemDefault(),
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
        return buildString {
            appendLine("Current time: $localNow$offset")
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
            Alarms:
              {"type":"oneOffTime","at":"<iso8601>"}
              {"type":"recurringTime","rule":"<RRULE body>","dtStart":"<iso8601>","until":"<iso8601|null>","count":<int|null>}
              {"type":"geofence","latitude":<num>,"longitude":<num>,"radiusMeters":<num>,"activeUntil":"<iso8601|null>"}
            - Resolve relative times from "Current time". Write iso8601 with the user's offset, never UTC ("5pm" at offset -03:00 -> "17:00:00-03:00").
            - Recurring: dtStart (first firing) is required; only its time of day matters when the rule has BYDAY/BYMONTHDAY, the app moves the date to the first matching day. rule = RRULE body only, no DTSTART/UNTIL/COUNT. until and count are mutually exclusive; both null = forever.
              Parts: FREQ (MINUTELY|HOURLY|DAILY|WEEKLY|MONTHLY|YEARLY), INTERVAL, BYDAY (MO..SU; 2SU = 2nd Sunday, -1FR = last Friday), BYMONTH, BYMONTHDAY, BYHOUR, BYMINUTE.
        """.trimIndent()

        private val PRIORITY_RULES = """
            Priority: "high"|"medium"|"low", default "medium". "urgent/asap/critical/important" -> high; "whenever/no rush/low priority" -> low.
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

            Output one command, chosen by the user's intent.

            Create a new Q:
              {"type":"create","text":"...","tags":[],"alarms":[],"priority":"medium"}

            Filter/sort the existing list (omit fields not mentioned; {} clears the filter):
              {"type":"filterSort","spec":{"text":<string|null>,"tags":[<string>],"priorities":["high"|"medium"|"low"],
               "alarm":"any|none|any_set|recurring|one_off|geofence","alarmWindow":"any|today|tomorrow|this_week|next_week",
               "completed":"any|only_open|only_done","sortBy":"tags|priority|created_at|updated_at|alarm","sortDir":"asc|desc"}}

            Intent: create = a NEW task/reminder for the user ("remind me to...", "add a Q to...").
            filterSort = show/hide/filter/sort/order the EXISTING list. A bare noun phrase ("groceries") -> filterSort.

            $ALARMS_AND_TIME_RULES

            $PRIORITY_RULES

            Filter shortcuts: "urgent"/"high priority" -> priorities ["high"]; "done" -> completed only_done; "open"/"todo" -> only_open;
            "no alarms" -> alarm none; "with alarms" -> any_set; "recurring" -> recurring; "one-off" -> one_off;
            "today"/"tomorrow"/"this week"/"next week" -> alarmWindow; "upcoming"/"next alarm" -> sortBy alarm asc;
            "newest first" -> created_at desc; "oldest first" -> created_at asc; "recently edited" -> updated_at desc;
            "by priority" -> priority asc; "by tag" -> tags asc.

            Create rules: keep the user's language; "text" is the note only (drop "remind me to", "me lembra de"); capitalize the first word.

            Examples:
              "Cue me at 5pm to pick up Stella"
              -> {"type":"create","text":"Pick up Stella","tags":[],"alarms":[{"type":"oneOffTime","at":"<resolved>"}],"priority":"medium"}
              "Every Tuesday at 9:30pm take out the trash"
              -> {"type":"create","text":"Take out the trash","tags":[],"alarms":[{"type":"recurringTime","rule":"FREQ=WEEKLY;BYDAY=TU","dtStart":"<today at 21:30>","until":null,"count":null}],"priority":"medium"}
              "show only high priority" -> {"type":"filterSort","spec":{"priorities":["high"]}}
              "what do I have next week" -> {"type":"filterSort","spec":{"alarmWindow":"next_week"}}
              "clear filters" -> {"type":"filterSort","spec":{}}
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
