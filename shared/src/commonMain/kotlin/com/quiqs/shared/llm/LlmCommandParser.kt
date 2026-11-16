package com.quiqs.shared.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
 *
 * @param alarmDecoder When set, compact alarms in a command's `alarms` array
 *   (`{"when":"tomorrow","time":"07:00"}`, …) are resolved into full [com.quiqs.shared.Alarm]s
 *   before decoding. Without it only the full wire form is accepted.
 */
class LlmCommandParser(private val alarmDecoder: LlmAlarmDecoder? = null) {

    /**
     * Parse [raw] into a [QCommand], or return a [Result.failure] with a human-readable
     * reason (no JSON found, or JSON that does not match any command variant).
     */
    fun parse(raw: String): Result<QCommand> {
        val payload = extractJsonObject(raw) ?: repairJsonObject(raw)
            ?: return Result.failure(IllegalArgumentException("No JSON object found in LLM output"))
        return runCatching {
            val element = parserJson.parseToJsonElement(payload)
            val resolved = if (alarmDecoder != null && element is JsonObject) {
                alarmDecoder.resolveAlarmsIn(element)
            } else {
                element
            }
            parserJson.decodeFromJsonElement(QCommand.serializer(), resolved)
        }
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

    /**
     * Last resort for slightly malformed output, which small models produce now and then
     * (e.g. `…"count":1]}` where a `}` was meant). Walks the text from the first `{`
     * tracking open brackets: a closer that doesn't match the innermost opener is replaced
     * by the one that does, and anything still open at the end is closed. Returns `null`
     * when there is no `{` at all. Output that was cut off mid-value will still fail to
     * decode afterwards, which is the right outcome.
     */
    private fun repairJsonObject(raw: String): String? {
        val text = raw.trim().removeFencing()
        val start = text.indexOf('{')
        if (start < 0) return null
        val closers = ArrayDeque<Char>()
        val out = StringBuilder()
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (escape) { escape = false; out.append(c); continue }
            if (inString) {
                out.append(c)
                if (c == '\\') escape = true else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> { inString = true; out.append(c) }
                '{' -> { closers.addLast('}'); out.append(c) }
                '[' -> { closers.addLast(']'); out.append(c) }
                '}', ']' -> {
                    if (closers.isEmpty()) return out.toString()
                    out.append(closers.removeLast())
                    if (closers.isEmpty()) return out.toString()
                }
                else -> out.append(c)
            }
        }
        if (inString) out.append('"')
        while (closers.isNotEmpty()) out.append(closers.removeLast())
        return out.toString()
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
