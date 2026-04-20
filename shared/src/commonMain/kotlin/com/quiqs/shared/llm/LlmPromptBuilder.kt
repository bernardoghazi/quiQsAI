@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared.llm

import com.quiqs.shared.Q
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
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
        val snapshots = existing.map { it.toSnapshot() }
        val snapshotsJson = promptJson.encodeToString(snapshotsSerializer, snapshots)
        return buildString {
            appendLine("Current time: $now")
            appendLine("Time zone: $timeZone")
            appendLine()
            appendLine("Existing Qs (id + text only; tags and alarms not shown):")
            appendLine(snapshotsJson)
            appendLine()
            userSection()
        }
    }

    companion object {
        private val SYSTEM = """
            You are quiQs's backend. Call exactly one tool to respond.
            A Q is a short note that may have tags and alarms.
            Each alarm is an object with "type" of oneOffTime (at: iso8601), recurringTime (rule: RRULE, until: iso8601 or null), or geofence (latitude, longitude, radiusMeters, activeUntil).
            Resolve relative times to absolute iso8601 using the provided now+tz.
            Reference only Q ids shown; on update, pass only fields that change.
            Use the answer tool for questions about Qs rather than changes.
        """.trimIndent()
    }
}
