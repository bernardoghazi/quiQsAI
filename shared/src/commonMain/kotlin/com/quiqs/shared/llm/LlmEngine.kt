package com.quiqs.shared.llm

interface LlmEngine {
    suspend fun generate(
        prompt: String,
        systemInstruction: String? = null,
        audioPath: String? = null,
        tools: List<LlmTool> = emptyList(),
        onChunk: (String) -> Unit = {},
    ): String

    fun close()
}

expect object LlmEngineFactory {
    fun create(modelPath: String, cacheDir: String? = null): LlmEngine
}
