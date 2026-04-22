@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared.llm

import com.quiqs.shared.QRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class QAgent(
    private val engine: LlmEngine,
    private val repository: QRepository,
    private val promptBuilder: LlmPromptBuilder,
    private val parser: LlmCommandParser = LlmCommandParser(),
    private val dispatcher: QCommandDispatcher = QCommandDispatcher(repository),
) {
    suspend fun createFromText(prompt: String, onChunk: (String) -> Unit = {}): QAgentOutcome =
        run(
            prompt = promptBuilder.buildForCreate(prompt),
            systemInstruction = promptBuilder.systemForCreate,
            audioPath = null,
            onChunk = onChunk,
        )

    suspend fun createFromVoice(audioPath: String, onChunk: (String) -> Unit = {}): QAgentOutcome =
        run(
            prompt = promptBuilder.buildForCreateAudio(),
            systemInstruction = promptBuilder.systemForCreate,
            audioPath = audioPath,
            onChunk = onChunk,
        )

    suspend fun editFromText(qId: Uuid, prompt: String, onChunk: (String) -> Unit = {}): QAgentOutcome {
        val q = repository.getById(qId)
            ?: return QAgentOutcome.ParseFailed(raw = "", reason = "No Q with id $qId")
        return run(
            prompt = promptBuilder.buildForEdit(q, prompt),
            systemInstruction = promptBuilder.systemForEdit,
            audioPath = null,
            onChunk = onChunk,
        )
    }

    suspend fun editFromVoice(qId: Uuid, audioPath: String, onChunk: (String) -> Unit = {}): QAgentOutcome {
        val q = repository.getById(qId)
            ?: return QAgentOutcome.ParseFailed(raw = "", reason = "No Q with id $qId")
        return run(
            prompt = promptBuilder.buildForEditAudio(q),
            systemInstruction = promptBuilder.systemForEdit,
            audioPath = audioPath,
            onChunk = onChunk,
        )
    }

    suspend fun queryFromText(prompt: String, onChunk: (String) -> Unit = {}): QAgentOutcome =
        run(
            prompt = promptBuilder.buildForQuery(prompt),
            systemInstruction = promptBuilder.systemForQuery,
            audioPath = null,
            tools = queryToolsFor(repository),
            onChunk = onChunk,
        )

    suspend fun queryFromVoice(audioPath: String, onChunk: (String) -> Unit = {}): QAgentOutcome =
        run(
            prompt = promptBuilder.buildForQueryAudio(),
            systemInstruction = promptBuilder.systemForQuery,
            audioPath = audioPath,
            tools = queryToolsFor(repository),
            onChunk = onChunk,
        )

    private suspend fun run(
        prompt: String,
        systemInstruction: String,
        audioPath: String?,
        tools: List<LlmTool> = emptyList(),
        onChunk: (String) -> Unit,
    ): QAgentOutcome =
        withContext(Dispatchers.Default) {
            val raw = engine.generate(
                prompt = prompt,
                systemInstruction = systemInstruction,
                audioPath = audioPath,
                tools = tools,
                onChunk = onChunk,
            )
            val command = parser.parse(raw).getOrElse { err ->
                return@withContext QAgentOutcome.ParseFailed(raw, err.message ?: "parse error")
            }
            val result = dispatcher.dispatch(command)
            QAgentOutcome.Handled(command, result, raw)
        }
}

sealed interface QAgentOutcome {
    data class Handled(val command: QCommand, val result: QCommandResult, val raw: String) : QAgentOutcome
    data class ParseFailed(val raw: String, val reason: String) : QAgentOutcome
}
