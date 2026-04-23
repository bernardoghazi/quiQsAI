package com.quiqs.shared.llm

/**
 * Platform-agnostic handle to a running on-device LLM session.
 *
 * Implementations wrap a native inference runtime (LiteRT-LM on Android/JVM, placeholder
 * on iOS) and are created by [LlmEngineFactory]. Engines are heavyweight — load the model
 * once at app start, share, and [close] on shutdown.
 */
interface LlmEngine {
    /**
     * Generate a response to [prompt] under the given [systemInstruction] and tool set.
     *
     * @param prompt User prompt, already decorated by [LlmPromptBuilder] (time, tz,
     *   existing-Q context, etc.).
     * @param systemInstruction Role-level instruction applied for the whole conversation.
     *   Passing `null` leaves the model's default behaviour.
     * @param audioPath When non-null, an audio file attached alongside the text prompt —
     *   Gemma 4 E2B is multimodal, so the model transcribes and reasons over it.
     * @param tools Function-calling tools the model may invoke mid-turn. Empty list
     *   disables tool use entirely.
     * @param onChunk Streaming callback, invoked for each decoded piece of the response
     *   as soon as it becomes available. Useful for UI streaming and TTFT measurement.
     * @return The full, concatenated model response.
     */
    suspend fun generate(
        prompt: String,
        systemInstruction: String? = null,
        audioPath: String? = null,
        tools: List<LlmTool> = emptyList(),
        onChunk: (String) -> Unit = {},
    ): String

    /** Release model weights and backing buffers. Engines are unusable after this call. */
    fun close()
}

/**
 * `expect` factory implemented per platform — see `LlmEngine.android.kt` (LiteRT-LM on
 * Android), `LlmEngine.jvm.kt` (LiteRT-LM on desktop JVM), and `LlmEngine.ios.kt`
 * (currently unimplemented stub).
 */
expect object LlmEngineFactory {
    /**
     * Loads the model at [modelPath] and initialises the backing runtime.
     *
     * @param modelPath Absolute path to the `.litertlm` weights bundle.
     * @param cacheDir Runtime scratch directory; pass `null` to let the implementation
     *   pick a default.
     */
    fun create(modelPath: String, cacheDir: String? = null): LlmEngine
}
