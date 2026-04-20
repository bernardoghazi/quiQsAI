package com.quiqs.shared.llm

internal class JsonBalanceTracker {
    private var depth = 0
    private var started = false
    private var inString = false
    private var escape = false

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
