package com.quiqs.client

import com.quiqs.shared.llm.QAgent

fun interface AgentProvider {
    suspend fun get(): QAgent?
}

val NoAgent: AgentProvider = AgentProvider { null }
