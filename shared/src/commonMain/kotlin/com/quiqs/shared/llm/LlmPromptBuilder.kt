@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared.llm

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

fun Q.toSnapshot(): QSnapshot = QSnapshot(id = id.toString(), text = text)

private val promptJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
}

private val snapshotsSerializer = ListSerializer(QSnapshot.serializer())

class LlmPromptBuilder(
    private val clock: () -> Instant,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val systemInstruction: String get() = SYSTEM

    fun build(userPrompt: String, existing: List<Q>): String =
        buildBase(existing) {
            appendLine("User prompt:")
            appendLine(userPrompt)
            appendLine()
        }

    fun buildForAudio(existing: List<Q>): String =
        buildBase(existing) {
            appendLine("The user's prompt is provided as an audio attachment. Transcribe it silently and treat the transcription as the user prompt.")
            appendLine()
        }

    private inline fun buildBase(existing: List<Q>, userSection: StringBuilder.() -> Unit): String {
        val now = clock()
        val offset = timeZone.offsetAt(now)
        val snapshots = existing.map { it.toSnapshot() }
        val snapshotsJson = promptJson.encodeToString(snapshotsSerializer, snapshots)
        return buildString {
            appendLine("Current time: $now")
            appendLine("Time zone: $timeZone ($offset)")
            appendLine()
            appendLine("Existing Qs (id + text only; tags and alarms not shown):")
            appendLine(snapshotsJson)
            appendLine()
            userSection()
        }
    }

    companion object {
        private val SYSTEM = """
            You are quiQs's backend. A Q is a note with tags and alarms.
            Output exactly one JSON command.

            Commands:
              {"type":"create","text":"...","tags":[],"alarms":[]}
              {"type":"update","id":"<uuid>","text":"...","tags":[],"alarms":[]}
              {"type":"delete","id":"<uuid>"}
              {"type":"answer","text":"..."}

            Alarms:
              {"type":"oneOffTime","at":"<iso8601>"}
              {"type":"recurringTime","rule":"<RRULE>","until":"<iso8601|null>"}
              {"type":"geofence","latitude":<num>,"longitude":<num>,"radiusMeters":<num>,"activeUntil":"<iso8601|null>"}

            Rules:
            - Resolve relative times with the given now+tz.
            - Emit "at" in the given tz as ISO-8601 with offset, not UTC. Example: user says "5pm" with offset 03:00 → "17:00:00-03:00".
            - Use "answer" for questions about Qs.
            - Reference only ids shown; on update, omit unchanged fields.

            Content rules:
            - Do not translate the user's language in "text".
            - "text" is the note content only; strip wrappers like "remind me to", "cue me to", "me lembra de".

            Examples:
              "Cue me at 5pm to pick up Stella"
              → {"type":"create","text":"pick up Stella","tags":[],"alarms":[{"type":"oneOffTime","at":"<resolved>"}]}

              "Me lembra de ligar pro médico amanhã às 9"
              → {"type":"create","text":"ligar pro médico","tags":[],"alarms":[{"type":"oneOffTime","at":"<resolved>"}]}
        """.trimIndent()
    }
}
