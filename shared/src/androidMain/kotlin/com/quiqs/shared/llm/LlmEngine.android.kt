package com.quiqs.shared.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val toolDescriptionJson = Json { encodeDefaults = true }

private class LiteRtLmEngine(modelPath: String, cacheDir: String?) : LlmEngine {

    private val engine: Engine = Engine(
        EngineConfig(
            modelPath = modelPath,
            backend = Backend.GPU(),
            audioBackend = Backend.CPU(),
            maxNumTokens = 4096,
            cacheDir = cacheDir,
        ),
    ).also { it.initialize() }

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
            conversation.sendMessageAsync(contents).transformWhile { chunk ->
                    val piece = chunk.toString()
                    emit(piece)
                    !tracker.consume(piece)
                }.collect {
                    buffer.append(it)
                    onChunk(it)
                }
            buffer.toString()
        }
    }

    override fun close() {
        engine.close()
    }
}

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
