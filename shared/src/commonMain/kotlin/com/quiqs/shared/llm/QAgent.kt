package com.quiqs.shared.llm

import com.quiqs.shared.QRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class QAgent(
    private val engine: LlmEngine,
    private val repository: QRepository,
    private val promptBuilder: LlmPromptBuilder,
    private val parser: LlmCommandParser = LlmCommandParser(),
    private val dispatcher: QCommandDispatcher = QCommandDispatcher(repository),
) {
    suspend fun handle(userPrompt: String, onChunk: (String) -> Unit = {}): QAgentOutcome =
        run(promptBuilder.build(userPrompt, repository.getAll()), audioPath = null, onChunk = onChunk)

    suspend fun handleVoice(audioPath: String, onChunk: (String) -> Unit = {}): QAgentOutcome =
        run(promptBuilder.buildForAudio(repository.getAll()), audioPath = audioPath, onChunk = onChunk)

    private suspend fun run(prompt: String, audioPath: String?, onChunk: (String) -> Unit): QAgentOutcome =
        withContext(Dispatchers.Default) {
            val raw = engine.generate(
                prompt = prompt,
                systemInstruction = promptBuilder.systemInstruction,
                audioPath = audioPath,
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
