@file:OptIn(ExperimentalApi::class)

package com.quiqs.shared.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.TimeSource

private val toolDescriptionJson = Json { encodeDefaults = true }

/**
 * Android [LlmEngine] backed by LiteRT-LM running Gemma 4 E2B on the device's GPU.
 *
 * Engine configuration:
 *  - GPU text backend + CPU audio backend (E2B is multimodal).
 *  - Deterministic sampling (`topK=1, topP=1.0, temperature=0.0`) so identical prompts
 *    yield identical JSON commands — important for parse reliability.
 *  - `maxNumTokens = 4096`, which is why [LlmPromptBuilder] splits system prompts per
 *    mode instead of stuffing everything up front.
 */
private class LiteRtLmEngine(modelPath: String, cacheDir: String?) : LlmEngine {

    @Volatile
    override var lastMetrics: LlmMetrics? = null
        private set

    private val engine: Engine = Engine(
        EngineConfig(
            modelPath = modelPath,
            backend = Backend.GPU(),
            audioBackend = Backend.CPU(),
            maxNumTokens = 4096,
            cacheDir = cacheDir,
        ),
    ).also {
        // Turns on the runtime's per-conversation prefill/decode counters read in
        // [generate]. Must be set before the conversation is created.
        ExperimentalFlags.enableBenchmark = true
        it.initialize()
    }

    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        audioPath: String?,
        tools: List<LlmTool>,
        onChunk: (String) -> Unit,
    ): String {
        val config = ConversationConfig(
            systemInstruction = systemInstruction?.let { Contents.of(Content.Text(it)) },
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            tools = tools.map { tool(it.toOpenApiTool()) },
        )
        return engine.createConversation(config).use { conversation ->
            val contents = if (audioPath != null) {
                Contents.of(Content.AudioFile(audioPath), Content.Text(prompt))
            } else {
                Contents.of(Content.Text(prompt))
            }
            val buffer = StringBuilder()
            val tracker = JsonBalanceTracker()
            val start = TimeSource.Monotonic.markNow()
            var ttftMs = -1L
            var chunks = 0
            // Stop collecting as soon as the top-level JSON object closes — trailing
            // model commentary is wasted decode and delays user-visible results.
            conversation.sendMessageAsync(contents).transformWhile { chunk ->
                    val piece = chunk.toString()
                    emit(piece)
                    !tracker.consume(piece)
                }.collect {
                    if (ttftMs < 0) ttftMs = start.elapsedNow().inWholeMilliseconds
                    chunks++
                    buffer.append(it)
                    onChunk(it)
                }
            val totalMs = start.elapsedNow().inWholeMilliseconds
            val bench = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
            lastMetrics = LlmMetrics(
                hadAudio = audioPath != null,
                systemChars = systemInstruction?.length ?: 0,
                promptChars = prompt.length,
                wallTtftMs = ttftMs.coerceAtLeast(0),
                wallTotalMs = totalMs,
                chunks = chunks,
                prefillTokens = bench?.lastPrefillTokenCount,
                prefillTokensPerSec = bench?.lastPrefillTokensPerSecond,
                decodeTokens = bench?.lastDecodeTokenCount,
                decodeTokensPerSec = bench?.lastDecodeTokensPerSecond,
            )
            buffer.toString()
        }
    }

    override fun close() {
        engine.close()
    }
}

/**
 * Bridges a common-module [LlmTool] to LiteRT-LM's [OpenApiTool].
 *
 * The LiteRT-LM API takes a single JSON description string containing name, description
 * and the parameter schema. [runBlocking] bridges the suspend `execute` contract to the
 * synchronous [OpenApiTool.execute] callback the runtime uses when dispatching tool
 * calls automatically.
 */
private fun LlmTool.toOpenApiTool(): OpenApiTool {
    val tool = this
    val descriptionJson = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("parameters", toolDescriptionJson.parseToJsonElement(tool.parametersJsonSchema))
    }.toString()
    return object : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = descriptionJson
        override fun execute(paramsJsonString: String): String =
            runBlocking { tool.execute(paramsJsonString) }
    }
}

actual object LlmEngineFactory {
    actual fun create(modelPath: String, cacheDir: String?): LlmEngine = LiteRtLmEngine(modelPath, cacheDir)
}
