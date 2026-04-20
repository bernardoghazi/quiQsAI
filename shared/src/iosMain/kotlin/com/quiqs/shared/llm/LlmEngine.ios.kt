package com.quiqs.shared.llm

actual object LlmEngineFactory {
    actual fun create(modelPath: String): LlmEngine =
        throw NotImplementedError("LiteRT-LM iOS binding not wired up yet")
}
