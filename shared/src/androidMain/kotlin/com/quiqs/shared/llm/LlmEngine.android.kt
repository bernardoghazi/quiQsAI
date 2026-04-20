package com.quiqs.shared.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private class LiteRtLmEngine(modelPath: String) : LlmEngine {

    private val engine: Engine = Engine(
        EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(numOfThreads = Runtime.getRuntime().availableProcessors()),
            audioBackend = Backend.CPU(),
        ),
    ).also { it.initialize() }

    private val mutex = Mutex()
    private var conversation: Conversation? = null
    private var currentSystemInstruction: String? = null

    private fun ensureConversation(systemInstruction: String?): Conversation {
        val existing = conversation
        if (existing != null && systemInstruction == currentSystemInstruction) return existing
        existing?.close()
        val config = ConversationConfig(
            systemInstruction = systemInstruction?.let { Contents.of(Content.Text(it)) },
        )
        val created = engine.createConversation(config)
        conversation = created
        currentSystemInstruction = systemInstruction
        return created
    }

    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        audioPath: String?,
        onChunk: (String) -> Unit,
    ): String = mutex.withLock {
        val conv = ensureConversation(systemInstruction)
        val contents = if (audioPath != null) {
            Contents.of(Content.AudioFile(audioPath), Content.Text(prompt))
        } else {
            Contents.of(Content.Text(prompt))
        }
        val buffer = StringBuilder()
        val tracker = JsonBalanceTracker()
        conv.sendMessageAsync(contents)
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

    override fun close() {
        conversation?.close()
        conversation = null
        engine.close()
    }
}

actual object LlmEngineFactory {
    actual fun create(modelPath: String): LlmEngine = LiteRtLmEngine(modelPath)
}
