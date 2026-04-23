@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared.llm

import com.quiqs.shared.Alarm
import com.quiqs.shared.Q
import kotlinx.datetime.Instant
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
)

/** Convert a domain [Q] to the wire snapshot. */
fun Q.toFullSnapshot(): QFullSnapshot = QFullSnapshot(
    id = id.toString(),
    text = text,
    tags = tags,
    alarms = alarms,
)

private val promptJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
}

/**
 * Builds the system and user prompts fed into [LlmEngine.generate] for each UI mode.
 *
 * System prompts are split by mode (create / edit / query) rather than a single
 * monolithic instruction — each mode only gets the rules and examples relevant to it,
 * keeping prefill cost well under the model's 4096-token context window.
 *
 * @property clock Injectable time source — tests can freeze time without stubbing [kotlinx.datetime.Clock].
 * @property timeZone Zone used to format the alarm offsets in the user's locale.
 */
class LlmPromptBuilder(
    private val clock: () -> Instant,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    /** System instruction for create-mode prompts. */
    val systemForCreate: String get() = SYSTEM_CREATE

    /** System instruction for edit-mode prompts. */
    val systemForEdit: String get() = SYSTEM_EDIT

    /** System instruction for query-mode prompts (includes the tool contract). */
    val systemForQuery: String get() = SYSTEM_QUERY

    /** Build the user prompt for creating a Q from typed text. */
    fun buildForCreate(userPrompt: String): String =
        buildBase {
            appendLine("User prompt:")
            appendLine(userPrompt)
        }

    /** Build the user prompt for creating a Q from audio (model transcribes silently). */
    fun buildForCreateAudio(): String =
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

    /** Build the user prompt for querying Qs from typed text. */
    fun buildForQuery(userPrompt: String): String =
        buildBase {
            appendLine("User prompt:")
            appendLine(userPrompt)
        }

    /** Build the user prompt for querying Qs from audio. */
    fun buildForQueryAudio(): String =
        buildBase {
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
              {"type":"recurringTime","rule":"<RRULE>","until":"<iso8601|null>"}
              {"type":"geofence","latitude":<num>,"longitude":<num>,"radiusMeters":<num>,"activeUntil":"<iso8601|null>"}

            Rules:
            - Resolve relative times with the given now+tz.
            - Emit "at" in the given tz as ISO-8601 with offset, not UTC. Example: user says "5pm" with offset 03:00 → "17:00:00-03:00".
        """.trimIndent()

        private val SYSTEM_CREATE = """
            $BASE

            Command:
              {"type":"create","text":"...","tags":[],"alarms":[]}

            $ALARMS_AND_TIME_RULES

            Content rules:
            - Do not translate the user's language in "text".
            - "text" is the note content only; strip wrappers like "remind me to", "cue me to", "me lembra de".

            Examples:
              "Cue me at 5pm to pick up Stella"
              → {"type":"create","text":"pick up Stella","tags":[],"alarms":[{"type":"oneOffTime","at":"<resolved>"}]}

              "Me lembra de ligar pro médico amanhã às 9"
              → {"type":"create","text":"ligar pro médico","tags":[],"alarms":[{"type":"oneOffTime","at":"<resolved>"}]}
        """.trimIndent()

        private val SYSTEM_EDIT = """
            $BASE

            Commands:
              {"type":"update","id":"<uuid>","text":"...","tags":[],"alarms":[]}
              {"type":"delete","id":"<uuid>"}

            $ALARMS_AND_TIME_RULES

            Content rules:
            - Do not translate the user's language in "text".
            - Use the id from "Q being edited".
            - On update, omit unchanged fields.
        """.trimIndent()

        private val SYSTEM_QUERY = """
            $BASE

            You have tools to look up the user's Qs:
              search_qs(query): find Qs by case-insensitive substring in text or tags
              list_all_qs(): list all Qs; use when search is unhelpful

            Call tools to fetch data, then respond with exactly:
              {"type":"answer","text":"..."}

            Reference only ids returned by tools.
        """.trimIndent()
    }
}
