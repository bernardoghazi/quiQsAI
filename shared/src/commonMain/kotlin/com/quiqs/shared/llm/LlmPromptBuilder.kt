@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared.llm

import com.quiqs.shared.Alarm
import com.quiqs.shared.Q
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi

@Serializable
data class QSnapshot(
    val id: String,
    val text: String,
)

@Serializable
data class QFullSnapshot(
    val id: String,
    val text: String,
    val tags: List<String>,
    val alarms: List<Alarm>,
)

fun Q.toSnapshot(): QSnapshot = QSnapshot(id = id.toString(), text = text)

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

private val snapshotsSerializer = ListSerializer(QSnapshot.serializer())

class LlmPromptBuilder(
    private val clock: () -> Instant,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val systemForCreate: String get() = SYSTEM_CREATE
    val systemForEdit: String get() = SYSTEM_EDIT
    val systemForQuery: String get() = SYSTEM_QUERY

    fun buildForCreate(userPrompt: String): String =
        buildBase {
            appendLine("User prompt:")
            appendLine(userPrompt)
        }

    fun buildForCreateAudio(): String =
        buildBase {
            appendLine(AUDIO_INSTRUCTION)
        }

    fun buildForEdit(q: Q, userPrompt: String): String =
        buildBase {
            appendLine("Q being edited:")
            appendLine(promptJson.encodeToString(QFullSnapshot.serializer(), q.toFullSnapshot()))
            appendLine()
            appendLine("User prompt:")
            appendLine(userPrompt)
        }

    fun buildForEditAudio(q: Q): String =
        buildBase {
            appendLine("Q being edited:")
            appendLine(promptJson.encodeToString(QFullSnapshot.serializer(), q.toFullSnapshot()))
            appendLine()
            appendLine(AUDIO_INSTRUCTION)
        }

    fun buildForQuery(existing: List<Q>, userPrompt: String): String =
        buildBase {
            appendQSnapshots(existing)
            appendLine("User prompt:")
            appendLine(userPrompt)
        }

    fun buildForQueryAudio(existing: List<Q>): String =
        buildBase {
            appendQSnapshots(existing)
            appendLine(AUDIO_INSTRUCTION)
        }

    private inline fun buildBase(userSection: StringBuilder.() -> Unit): String {
        val now = clock()
        val offset = timeZone.offsetAt(now)
        return buildString {
            appendLine("Current time: $now")
            appendLine("Time zone: $timeZone ($offset)")
            appendLine()
            userSection()
        }
    }

    private fun StringBuilder.appendQSnapshots(existing: List<Q>) {
        val snapshots = existing.map { it.toSnapshot() }
        appendLine("Existing Qs (id + text only; tags and alarms not shown):")
        appendLine(promptJson.encodeToString(snapshotsSerializer, snapshots))
        appendLine()
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

            Command:
              {"type":"answer","text":"..."}

            Answer questions about the user's existing Qs. Reference only ids shown.
        """.trimIndent()
    }
}
