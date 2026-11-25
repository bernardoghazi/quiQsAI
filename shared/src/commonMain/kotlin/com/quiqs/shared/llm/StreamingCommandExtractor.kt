package com.quiqs.shared.llm

import com.quiqs.shared.Alarm
import com.quiqs.shared.Priority
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Incrementally extracts top-level fields from a streaming JSON object.
 *
 * Each call to [accept] appends a chunk of LLM output to an internal buffer and
 * re-scans for newly-completed top-level fields. When a field's value is fully
 * present (string closing quote, balanced array/object, scalar terminated by `,` or
 * `}`), the matching [StreamingFieldUpdate] is emitted exactly once.
 *
 * The extractor is forgiving: malformed values (e.g. partial timestamps mid-stream)
 * just don't emit — the post-stream parser still runs and applies its own repair
 * passes. Unknown keys are ignored.
 *
 * @param alarmDecoder When set, `alarms` values may use the compact notation and are
 *   resolved through it; otherwise only the full wire form is understood.
 */
class StreamingCommandExtractor(private val alarmDecoder: LlmAlarmDecoder? = null) {

    private val buffer = StringBuilder()
    private val emitted = mutableSetOf<String>()

    fun accept(chunk: String, onUpdate: (StreamingFieldUpdate) -> Unit) {
        buffer.append(chunk)
        scan(buffer.toString(), onUpdate)
    }

    /**
     * Walks [s] once and emits any newly-completed top-level field. Cost is O(n) per
     * call; n is small (LLM output is a few hundred chars), and we avoid re-emitting
     * the same field by tracking [emitted].
     */
    private fun scan(s: String, onUpdate: (StreamingFieldUpdate) -> Unit) {
        val openBrace = s.indexOf('{')
        if (openBrace < 0) return
        var i = openBrace + 1
        // Move past optional whitespace.
        while (i < s.length && s[i].isWhitespace()) i++
        while (i < s.length) {
            if (s[i] == '}') return
            if (s[i] == ',') { i++; continue }
            if (s[i].isWhitespace()) { i++; continue }
            // Expect a `"key"` here.
            if (s[i] != '"') {
                // Unrecognized — bail. Either the stream is incomplete or the format
                // is off; subsequent chunks may straighten it out.
                return
            }
            val keyEnd = findStringEnd(s, i) ?: return
            val key = s.substring(i + 1, keyEnd)
            i = keyEnd + 1
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length || s[i] != ':') return
            i++
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) return
            val valueEnd = findValueEnd(s, i) ?: return
            val rawValue = s.substring(i, valueEnd)
            if (key !in emitted) {
                decodeAndEmit(key, rawValue)?.let {
                    emitted += key
                    onUpdate(it)
                }
            }
            i = valueEnd
        }
    }

    /** Returns the index of the closing `"` for a string starting at [start] (where s[start] == `"`). */
    private fun findStringEnd(s: String, start: Int): Int? {
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == '"') return i
            i++
        }
        return null
    }

    /** Returns the index just past the end of a JSON value starting at [start], or null if incomplete. */
    private fun findValueEnd(s: String, start: Int): Int? = when (s[start]) {
        '"' -> findStringEnd(s, start)?.let { it + 1 }
        '{' -> findBalancedEnd(s, start, '{', '}')
        '[' -> findBalancedEnd(s, start, '[', ']')
        else -> findScalarEnd(s, start)
    }

    private fun findBalancedEnd(s: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        var i = start
        var inString = false
        var escape = false
        while (i < s.length) {
            val c = s[i]
            if (escape) { escape = false; i++; continue }
            if (inString) {
                if (c == '\\') escape = true
                else if (c == '"') inString = false
                i++; continue
            }
            when (c) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return null
    }

    private fun findScalarEnd(s: String, start: Int): Int? {
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (c == ',' || c == '}' || c == ']' || c.isWhitespace()) return i
            i++
        }
        return null
    }

    private fun decodeAndEmit(key: String, rawValue: String): StreamingFieldUpdate? = when (key) {
        "type" -> decodeString(rawValue)?.let(StreamingFieldUpdate::Type)
        "text" -> decodeString(rawValue)?.let(StreamingFieldUpdate::Text)
        "priority" -> decodeString(rawValue)?.let { StreamingFieldUpdate.Priority(Priority.fromWire(it)) }
        "completed" -> when (rawValue.trim()) {
            "true" -> StreamingFieldUpdate.Completed(true)
            "false" -> StreamingFieldUpdate.Completed(false)
            else -> null
        }
        "tags" -> runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), rawValue)
        }.getOrNull()?.let(StreamingFieldUpdate::Tags)
        "alarms" -> runCatching {
            alarmDecoder?.decodeList(rawValue)
                ?: json.decodeFromString(ListSerializer(Alarm.serializer()), rawValue)
        }.getOrNull()?.let(StreamingFieldUpdate::Alarms)
        else -> null
    }

    private fun decodeString(raw: String): String? {
        if (raw.length < 2 || raw[0] != '"' || raw[raw.length - 1] != '"') return null
        return runCatching {
            json.decodeFromString(String.serializer(), raw)
        }.getOrNull()
    }

    private companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            isLenient = true
        }
    }
}
