package com.quiqs.shared.llm

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.collect

private class LiteRtLmEngine(modelPath: String) : LlmEngine {

    private val engine: Engine = Engine(EngineConfig(modelPath = modelPath)).also { it.initialize() }

    override suspend fun generate(prompt: String, audioPath: String?): String =
        engine.createConversation().use { conversation ->
            val contents = if (audioPath != null) {
                Contents.of(Content.AudioFile(audioPath), Content.Text(prompt))
            } else {
                Contents.of(Content.Text(prompt))
            }
            val buffer = StringBuilder()
            conversation.sendMessageAsync(contents).collect { chunk ->
                buffer.append(chunk.toString())
            }
            buffer.toString()
        }

    override fun close() {
        engine.close()
    }
}

actual object LlmEngineFactory {
    actual fun create(modelPath: String): LlmEngine = LiteRtLmEngine(modelPath)
}
