@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared.llm

import com.quiqs.shared.Alarm
import com.quiqs.shared.Q
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi

@Serializable
data class QSnapshot(
    val id: String,
    val text: String,
    val tags: List<String> = emptyList(),
    val alarms: List<Alarm> = emptyList(),
)

fun Q.toSnapshot(): QSnapshot = QSnapshot(
    id = id.toString(),
    text = text,
    tags = tags,
    alarms = alarms,
)

private val promptJson = Json {
    prettyPrint = true
    classDiscriminator = "type"
    encodeDefaults = true
}

private val snapshotsSerializer = ListSerializer(QSnapshot.serializer())

class LlmPromptBuilder(
    private val clock: () -> Instant,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    fun build(userPrompt: String, existing: List<Q>): String {
        val now = clock()
        val snapshots = existing.map { it.toSnapshot() }
        val snapshotsJson = promptJson.encodeToString(snapshotsSerializer, snapshots)
        return buildString {
            appendLine(SYSTEM)
            appendLine()
            appendLine("Current time: $now")
            appendLine("Time zone: $timeZone")
            appendLine()
            appendLine("Existing Qs (JSON):")
            appendLine(snapshotsJson)
            appendLine()
            appendLine("User prompt:")
            appendLine(userPrompt)
            appendLine()
            append(OUTPUT_INSTRUCTIONS)
        }
    }

    companion object {
        private val SYSTEM = """
            You are the backend for a personal reminder app called quiQs.
            A "Q" is a short note that may have tags and alarms attached.
            Your job: given the user's prompt, output exactly one JSON command
            that creates, updates, deletes, or answers a question about Qs.

            Command schema (one of):
              { "type": "create", "text": "...", "tags": [..], "alarms": [..] }
              { "type": "update", "id": "<uuid>", "text": "...", "tags": [..], "alarms": [..] }
              { "type": "delete", "id": "<uuid>" }
              { "type": "answer", "text": "..." }

            Alarm schema (one of):
              { "type": "oneOffTime", "at": "<ISO-8601 instant>" }
              { "type": "recurringTime", "rule": "<RFC5545 RRULE>", "until": "<ISO-8601 instant or null>" }
              { "type": "geofence", "latitude": <double>, "longitude": <double>, "radiusMeters": <double>, "activeUntil": "<ISO-8601 instant or null>" }

            Rules:
              - Resolve all relative times (e.g. "tomorrow at noon") to absolute ISO-8601 instants using the provided current time and time zone.
              - Use "answer" when the user is asking a question about their Qs rather than requesting a change.
              - Only reference existing Qs by the ids shown above.
              - Omit fields you are not changing on "update"; fields left out will keep their current value.
        """.trimIndent()

        private val OUTPUT_INSTRUCTIONS = """
            Respond with exactly one JSON object matching the command schema. No prose, no markdown fences.
        """.trimIndent()
    }
}
