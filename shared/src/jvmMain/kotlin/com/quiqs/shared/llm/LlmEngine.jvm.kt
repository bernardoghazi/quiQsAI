package com.quiqs.shared.llm

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.collect

/**
 * Desktop-JVM [LlmEngine] backed by LiteRT-LM.
 *
 * Kept minimal on purpose — tool use isn't wired up here and the audio backend uses
 * defaults. The Android actual is where the production configuration lives; this one
 * exists so desktop builds and tests compile against the same interface.
 */
private class LiteRtLmEngine(modelPath: String, cacheDir: String?) : LlmEngine {

    private val engine: Engine = Engine(
        EngineConfig(modelPath = modelPath, cacheDir = cacheDir),
    ).also { it.initialize() }

    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        audioPath: String?,
        tools: List<LlmTool>,
        onChunk: (String) -> Unit,
    ): String = engine.createConversation().use { conversation ->
        val contents = if (audioPath != null) {
            Contents.of(Content.AudioFile(audioPath), Content.Text(prompt))
        } else {
            Contents.of(Content.Text(prompt))
        }
        val buffer = StringBuilder()
        conversation.sendMessageAsync(contents).collect { chunk ->
            val piece = chunk.toString()
            buffer.append(piece)
            onChunk(piece)
        }
        buffer.toString()
    }

    override fun close() {
        engine.close()
    }
}

actual object LlmEngineFactory {
    actual fun create(modelPath: String, cacheDir: String?): LlmEngine =
        LiteRtLmEngine(modelPath, cacheDir)
}
