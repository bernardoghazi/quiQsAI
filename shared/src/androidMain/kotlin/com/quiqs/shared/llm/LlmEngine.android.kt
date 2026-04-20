package com.quiqs.shared.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile

private class LiteRtLmEngine(modelPath: String, cacheDir: String?) : LlmEngine {

    private val engine: Engine = run {
        fun build(backend: Backend) = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                audioBackend = Backend.CPU(),
                maxNumTokens = 4096,
                cacheDir = cacheDir,
            ),
        ).also { it.initialize() }
        try {
            build(Backend.GPU()).also { android.util.Log.i("LlmEngine", "Using GPU backend") }
        } catch (t: Throwable) {
            android.util.Log.w("LlmEngine", "GPU backend unavailable (${t.message}); falling back to CPU")
            build(Backend.CPU())
        }
    }

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
    actual fun create(modelPath: String, cacheDir: String?): LlmEngine =
        LiteRtLmEngine(modelPath, cacheDir)
}
