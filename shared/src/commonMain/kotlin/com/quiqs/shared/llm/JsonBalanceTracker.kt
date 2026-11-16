package com.quiqs.shared.llm

/**
 * Streaming JSON-object boundary detector.
 *
 * Lets callers feed model output chunk-by-chunk via [consume] and cut streaming off as
 * soon as the top-level `{ … }` object has been fully emitted — the model often rambles
 * (natural-language commentary, extra whitespace) after the JSON command, and those
 * trailing tokens are wasted decode.
 *
 * Tracks brace depth while respecting JSON strings and `\`-escape sequences. Not
 * thread-safe; use one instance per conversation.
 */
internal class JsonBalanceTracker {
    private var depth = 0
    private var started = false
    private var inString = false
    private var escape = false

    /**
     * Feed the next piece of streamed text.
     * @return `true` once the top-level object has closed and no more tokens are needed.
     */
    fun consume(chunk: String): Boolean {
        for (c in chunk) {
            if (escape) { escape = false; continue }
            when {
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> { depth++; started = true }
                !inString && c == '}' -> {
                    depth--
                    if (started && depth == 0) return true
                }
            }
        }
        return false
    }
}
