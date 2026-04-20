package com.quiqs.shared.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile

private class LiteRtLmEngine(modelPath: String) : LlmEngine {

    private val engine: Engine = Engine(
        EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(numOfThreads = Runtime.getRuntime().availableProcessors()),
            audioBackend = Backend.CPU(),
        ),
    ).also { it.initialize() }

    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        audioPath: String?,
        onChunk: (String) -> Unit,
    ): String {
        val config = ConversationConfig(
            systemInstruction = systemInstruction?.let { Contents.of(Content.Text(it)) },
        )
        return engine.createConversation(config).use { conversation ->
            val contents = if (audioPath != null) {
                Contents.of(Content.AudioFile(audioPath), Content.Text(prompt))
            } else {
                Contents.of(Content.Text(prompt))
            }
            val buffer = StringBuilder()
            val tracker = JsonBalanceTracker()
            conversation.sendMessageAsync(contents)
                .transformWhile { chunk ->
                    val piece = chunk.toString()
                    emit(piece)
                    !tracker.consume(piece)
                }
                .collect {
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

actual object LlmEngineFactory {
    actual fun create(modelPath: String): LlmEngine = LiteRtLmEngine(modelPath)
}
