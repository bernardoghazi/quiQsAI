package com.quiqs.shared.llm

import kotlinx.serialization.json.Json

private val parserJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    isLenient = true
}

/**
 * Extracts and decodes a [QCommand] from raw LLM output.
 *
 * The model ideally emits a single JSON object, but in practice output can include
 * leading text, a ```json fence, or trailing commentary. [parse] tolerates those by
 * locating the first balanced `{ … }` block and decoding it.
 */
class LlmCommandParser {

    /**
     * Parse [raw] into a [QCommand], or return a [Result.failure] with a human-readable
     * reason (no JSON found, or JSON that does not match any command variant).
     */
    fun parse(raw: String): Result<QCommand> {
        val payload = extractJsonObject(raw)
            ?: return Result.failure(IllegalArgumentException("No JSON object found in LLM output"))
        return runCatching { parserJson.decodeFromString(QCommand.serializer(), payload) }
    }

    /**
     * Finds the first top-level `{ … }` block in [raw], respecting string literals and
     * escape sequences so braces inside JSON strings don't confuse the balance counter.
     * Returns the substring, or `null` if no balanced object exists.
     */
    private fun extractJsonObject(raw: String): String? {
        val trimmed = raw.trim().removeFencing()
        val start = trimmed.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until trimmed.length) {
            val c = trimmed[i]
            if (escape) { escape = false; continue }
            when {
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return trimmed.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Strips a leading ``` fence (with optional language tag) and the matching trailing fence. */
    private fun String.removeFencing(): String {
        if (!startsWith("```")) return this
        val firstNewline = indexOf('\n')
        if (firstNewline < 0) return this
        val withoutOpen = substring(firstNewline + 1)
        val closeIdx = withoutOpen.lastIndexOf("```")
        return if (closeIdx >= 0) withoutOpen.substring(0, closeIdx).trim() else withoutOpen
    }
}
