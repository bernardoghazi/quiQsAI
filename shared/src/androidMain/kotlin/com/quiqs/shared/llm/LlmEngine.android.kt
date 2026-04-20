package com.quiqs.shared.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ToolCall

private class LiteRtLmEngine(modelPath: String, cacheDir: String?) : LlmEngine {

    private val engine: Engine = Engine(
        EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(),
            audioBackend = Backend.CPU(),
            maxNumTokens = 4096,
            cacheDir = cacheDir,
        ),
    ).also { it.initialize() }

    @OptIn(ExperimentalApi::class)
    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        audioPath: String?,
        onChunk: (String) -> Unit,
    ): String {
        val config = ConversationConfig(
            systemInstruction = systemInstruction?.let { Contents.of(Content.Text(it)) },
            tools = QCommandTools.providers,
            automaticToolCalling = false,
        )
        ExperimentalFlags.enableConversationConstrainedDecoding = true
        val conversation = try {
            engine.createConversation(config)
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
        return conversation.use { conv ->
            val contents = if (audioPath != null) {
                Contents.of(Content.AudioFile(audioPath), Content.Text(prompt))
            } else {
                Contents.of(Content.Text(prompt))
            }
            val textBuffer = StringBuilder()
            var lastToolCall: ToolCall? = null
            conv.sendMessageAsync(contents).collect { message ->
                val call = message.toolCalls.lastOrNull()
                if (call != null) {
                    lastToolCall = call
                } else {
                    val piece = message.toString()
                    textBuffer.append(piece)
                    onChunk(piece)
                }
            }
            val call = lastToolCall
            if (call != null) {
                val json = QCommandTools.toCommandJson(call)
                onChunk(json)
                json
            } else {
                textBuffer.toString()
            }
        }
    }

    override fun close() {
        engine.close()
    }
}

actual object LlmEngineFactory {
    actual fun create(modelPath: String, cacheDir: String?): LlmEngine = LiteRtLmEngine(modelPath, cacheDir)
}
