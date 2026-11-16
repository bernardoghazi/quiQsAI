package com.quiqs.shared.llm

import kotlin.math.roundToInt

/**
 * Timing for one [LlmEngine.generate] call, captured to find where voice-prompt latency
 * goes on mid-range devices.
 *
 * Wall-clock fields are measured around the streaming collect, so they include audio
 * encoding (when [hadAudio]) and prefill in [wallTtftMs]. The token fields come from the
 * runtime's own benchmark counters when available (`null` otherwise). Because the engine
 * stops collecting as soon as the top-level JSON object closes, decode counts cover only
 * the tokens actually consumed.
 *
 * @property hadAudio Whether an audio attachment was part of the input.
 * @property systemChars Length of the system instruction, in characters.
 * @property promptChars Length of the user prompt, in characters.
 * @property wallTtftMs Time from the call to the first streamed chunk.
 * @property wallTotalMs Time from the call to the end of collection.
 * @property chunks Number of streamed chunks (roughly one per decoded token).
 * @property prefillTokens Input tokens processed in the last prefill (runtime-reported).
 * @property prefillTokensPerSec Prefill throughput (runtime-reported).
 * @property decodeTokens Output tokens decoded (runtime-reported).
 * @property decodeTokensPerSec Decode throughput (runtime-reported).
 */
data class LlmMetrics(
    val hadAudio: Boolean,
    val systemChars: Int,
    val promptChars: Int,
    val wallTtftMs: Long,
    val wallTotalMs: Long,
    val chunks: Int,
    val prefillTokens: Int? = null,
    val prefillTokensPerSec: Double? = null,
    val decodeTokens: Int? = null,
    val decodeTokensPerSec: Double? = null,
) {
    /** One-line human-readable summary, used for logs and the on-device debug dialog. */
    fun summary(): String = buildString {
        append("ttft=${wallTtftMs}ms total=${wallTotalMs}ms")
        append(" | prefill=")
        append(prefillTokens?.toString() ?: "?")
        append(" tok @ ")
        append(prefillTokensPerSec?.let { "${it.roundToInt()}" } ?: "?")
        // The runtime's decode counters read 0 when we stop collecting early, so report
        // chunks (≈ tokens) and the rate measured on the wall clock instead.
        val decodeMs = (wallTotalMs - wallTtftMs).coerceAtLeast(1)
        append(" tok/s | decode=$chunks chunks in ${decodeMs}ms (${(chunks * 1000.0 / decodeMs).roundToInt()}/s)")
        append(" | in: sys=${systemChars}ch prompt=${promptChars}ch audio=$hadAudio")
    }
}
