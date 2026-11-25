package com.quiqs.client

import com.quiqs.shared.llm.QAgent

/**
 * Result of attempting to obtain a [QAgent].
 *
 * Kept as a sealed type rather than a nullable return so we can surface *why* the LLM
 * isn't available (missing model file, unsupported platform, initialisation error)
 * without extra out-params.
 */
sealed interface AgentStatus {
    /** A working agent is ready to use. */
    data class Available(val agent: QAgent) : AgentStatus

    /** No agent — [reason] is a user-facing explanation. */
    data class Unavailable(val reason: String) : AgentStatus
}

/**
 * Lazy factory for a [QAgent]. Implementations are responsible for locating the model
 * file, loading the engine, and caching it across calls (loading is expensive).
 */
fun interface AgentProvider {
    suspend fun get(): AgentStatus
}

/** Stub provider used by targets where the LLM isn't wired up (e.g. desktop, iOS). */
val NoAgent: AgentProvider = AgentProvider {
    AgentStatus.Unavailable("No on-device LLM is wired up on this platform.")
}
