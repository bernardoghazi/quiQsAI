package com.quiqs.shared.llm

interface LlmEngine {
    suspend fun generate(
        prompt: String,
        audioPath: String? = null,
        onChunk: (String) -> Unit = {},
    ): String

    fun close()
}

expect object LlmEngineFactory {
    fun create(modelPath: String): LlmEngine
}
