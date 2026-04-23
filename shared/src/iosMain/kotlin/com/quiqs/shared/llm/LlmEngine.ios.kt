package com.quiqs.shared.llm

/**
 * iOS [LlmEngineFactory] — not yet implemented.
 *
 * LiteRT-LM doesn't ship a Kotlin/Native iOS target, so on-device inference on iOS
 * needs a different runtime (e.g. MLC-LLM or a Core ML model) wired up via platform
 * interop. Calling [create] throws; the app routes iOS users to the non-LLM UI via
 * [com.quiqs.client.NoAgent] until this lands.
 */
actual object LlmEngineFactory {
    actual fun create(modelPath: String, cacheDir: String?): LlmEngine =
        throw NotImplementedError("LiteRT-LM iOS binding not wired up yet")
}
