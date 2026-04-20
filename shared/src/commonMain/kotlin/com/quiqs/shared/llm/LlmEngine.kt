package com.quiqs.shared.llm

interface LlmEngine {
    suspend fun generate(prompt: String): String
    fun close()
}

expect object LlmEngineFactory {
    fun create(modelPath: String): LlmEngine
}
