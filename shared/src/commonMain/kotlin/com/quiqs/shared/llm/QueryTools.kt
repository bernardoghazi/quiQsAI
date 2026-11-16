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

/**
 * Tool set exposed to query-mode prompts.
 *
 * The model calls these to fetch Qs on demand instead of receiving the full list up
 * front in the system prompt — the list can easily exceed the 4096-token context
 * window. Each tool logs entry and result size via `println` so `logcat` shows which
 * tool the model chose for a given prompt.
 *
 * Tools:
 *  - `search_qs(query)` — case-insensitive substring match against text and tags.
 *  - `list_all_qs()` — returns every Q; used for aggregate questions (e.g. counts).
 */
fun queryToolsFor(repository: QRepository): List<LlmTool> = listOf(
    LlmTool(
        name = "search_qs",
        description = "Search Qs by case-insensitive substring in text or tags. Returns id, text, tags, and alarms for all matching Qs.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "Case-insensitive substring to match"
                }
              },
              "required": ["query"]
            }
        """.trimIndent(),
        execute = { paramsJson ->
            println("[QueryTools] search_qs called with $paramsJson")
            val params = toolJson.parseToJsonElement(paramsJson).jsonObject
            val query = params["query"]?.jsonPrimitive?.content.orEmpty()
            val results = repository.getAll().filter { q ->
                q.text.contains(query, ignoreCase = true) || q.tags.any { it.contains(query, ignoreCase = true) }
            }.map { it.toFullSnapshot() }
            println("[QueryTools] search_qs returning ${results.size} Qs")
            toolJson.encodeToString(fullSnapshotsSerializer, results)
        },
    ),
    LlmTool(
        name = "list_all_qs",
        description = "List all Qs. Use when a keyword search is unhelpful (e.g., 'how many Qs do I have?').",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {}
            }
        """.trimIndent(),
        execute = { _ ->
            println("[QueryTools] list_all_qs called")
            val results = repository.getAll().map { it.toFullSnapshot() }
            println("[QueryTools] list_all_qs returning ${results.size} Qs")
            toolJson.encodeToString(fullSnapshotsSerializer, results)
        },
    ),
)
