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
            Output exactly one JSON command, on a single line of compact JSON: no code fence, no indentation.
        """.trimIndent()

        private const val AUDIO_INSTRUCTION =
            "The user's prompt is provided as an audio attachment. Transcribe it silently and treat the transcription as the user prompt."

        private val ALARMS_AND_TIME_RULES = """
            Alarms (local time, 24h "HH:mm"; the app works out the dates, never compute them yourself):
              one-off:   (Portuguese "6a feira" = fri, "2a feira" = mon; never invent other words like "this_week") {"when":"today|tomorrow|mon|tue|wed|thu|fri|sat|sun|YYYY-MM-DD","time":"HH:mm"} or {"in":"30m|2h|3d"}
              recurring: {"every":"minute|hour|day|week|month|year","interval":<int>,"days":["mon","wed"],"monthDays":[1,15],"months":[6],"time":"HH:mm","until":"YYYY-MM-DD","count":<int>}
            - Use the day words the user said. "time" defaults to 09:00. Omit fields that don't apply.
            - Exactly ONE alarm per Q. With two times, the alarm is the FIRST time the user says; the second one is the event's own time and stays in "text".
            - Never change the hour the user said: "às 8" = 08:00 unless they say tarde/noite/pm; then add 12 to that hour exactly ("7 da noite" = 19:00, "5pm" = 17:00).
            - Several times a day at even spacing ("8am and 8pm") is ONE alarm: {"every":"hour","interval":12,"time":"20:00"}.
            - "days" also accepts "weekdays" (Mon-Fri) and "weekend". For a date of the month use "monthDays", not "days".
            - "days" can take an ordinal for monthly/yearly rules: "2sun" = 2nd Sunday, "-1fri" = last Friday.
            - "until" and "count" are mutually exclusive; neither = repeats forever.
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

            Create a new Q (leave out tags, alarms and priority when empty or default):
              {"type":"create","text":"...","tags":["..."],"alarms":[...],"priority":"high|low"}

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

            Create rules: keep the user's language; "text" is what to remember, with every detail the user gave (including times of the event itself) except the reminder time and wrapper words like "remind me to" / "me lembra de"; capitalize the first word.

            Examples:
              "Cue me at 5pm to pick up Stella"
              -> {"type":"create","text":"Pick up Stella","alarms":[{"when":"today","time":"17:00"}]}
              "In half an hour check the oven"
              -> {"type":"create","text":"Check the oven","alarms":[{"in":"30m"}]}
              "Every day at 7am take my medicine"
              -> {"type":"create","text":"Take my medicine","alarms":[{"every":"day","time":"07:00"}]}
              "Every Monday, Wednesday and Friday at 5pm take out the trash"
              -> {"type":"create","text":"Take out the trash","alarms":[{"every":"week","days":["mon","wed","fri"],"time":"17:00"}]}
              "Every weekday at 9am standup"
              -> {"type":"create","text":"Standup","alarms":[{"every":"week","days":["weekdays"],"time":"09:00"}]}
              "Every other Saturday at 10am until March 1st clean the garage"
              -> {"type":"create","text":"Clean the garage","alarms":[{"every":"week","interval":2,"days":["sat"],"time":"10:00","until":"2027-03-01"}]}
              "Take vitamins every day at 8am and 8pm"
              -> {"type":"create","text":"Take vitamins","alarms":[{"every":"hour","interval":12,"time":"20:00"}]}
              "Pay rent on the 5th of every month"
              -> {"type":"create","text":"Pay rent","alarms":[{"every":"month","monthDays":[5]}]}
              "Call mom on the second Sunday of every month at 10am"
              -> {"type":"create","text":"Call mom","alarms":[{"every":"month","days":["2sun"],"time":"10:00"}]}
              "Urgent: renew the passport on December 3rd"
              -> {"type":"create","text":"Renew the passport","alarms":[{"when":"2026-12-03"}],"priority":"high"}
              "show only high priority" -> {"type":"filterSort","spec":{"priorities":["high"]}}
              "what do I have next week" -> {"type":"filterSort","spec":{"alarmWindow":"next_week"}}
              "me lembra de ligar pro Zé na 6a feira às 7 da noite"
              -> {"type":"create","text":"Ligar pro Zé","alarms":[{"when":"fri","time":"19:00"}]}
              "amanhã às 7 me lembra da reunião às 10"
              -> {"type":"create","text":"Reunião às 10","alarms":[{"when":"tomorrow","time":"07:00"}]}
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
