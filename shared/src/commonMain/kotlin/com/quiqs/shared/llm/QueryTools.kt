package com.quiqs.shared.llm

import com.quiqs.shared.QRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val toolJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
}

private val fullSnapshotsSerializer = ListSerializer(QFullSnapshot.serializer())

fun queryToolsFor(repository: QRepository): List<LlmTool> = listOf(
    LlmTool(
        name = "search_qs",
        description = "Search Qs by case-insensitive substring in text or tags. Returns id, text, tags, and alarms for all matching Qs.",
        parametersJsonSchema = """{"type":"object","properties":{"query":{"type":"string","description":"Case-insensitive substring to match"}},"required":["query"]}""",
        execute = { paramsJson ->
            val params = toolJson.parseToJsonElement(paramsJson).jsonObject
            val query = params["query"]?.jsonPrimitive?.content.orEmpty()
            val results = repository.getAll().filter { q ->
                q.text.contains(query, ignoreCase = true) || q.tags.any { it.contains(query, ignoreCase = true) }
            }.map { it.toFullSnapshot() }
            toolJson.encodeToString(fullSnapshotsSerializer, results)
        },
    ),
    LlmTool(
        name = "list_all_qs",
        description = "List all Qs. Use when a keyword search is unhelpful (e.g., 'how many Qs do I have?').",
        parametersJsonSchema = """{"type":"object","properties":{}}""",
        execute = { _ ->
            val results = repository.getAll().map { it.toFullSnapshot() }
            toolJson.encodeToString(fullSnapshotsSerializer, results)
        },
    ),
)
