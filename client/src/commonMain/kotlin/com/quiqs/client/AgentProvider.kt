package com.quiqs.client

import com.quiqs.shared.llm.QAgent

sealed interface AgentStatus {
    data class Available(val agent: QAgent) : AgentStatus
    data class Unavailable(val reason: String) : AgentStatus
}

fun interface AgentProvider {
    suspend fun get(): AgentStatus
}

val NoAgent: AgentProvider = AgentProvider {
    AgentStatus.Unavailable("No on-device LLM is wired up on this platform.")
}
