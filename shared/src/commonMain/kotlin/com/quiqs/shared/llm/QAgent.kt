package com.quiqs.shared.llm

import com.quiqs.shared.QRepository

class QAgent(
    private val engine: LlmEngine,
    private val repository: QRepository,
    private val promptBuilder: LlmPromptBuilder,
    private val parser: LlmCommandParser = LlmCommandParser(),
    private val dispatcher: QCommandDispatcher = QCommandDispatcher(repository),
) {
    suspend fun handle(userPrompt: String): QAgentOutcome {
        val existing = repository.getAll()
        val prompt = promptBuilder.build(userPrompt, existing)
        val raw = engine.generate(prompt)
        val command = parser.parse(raw).getOrElse { err ->
            return QAgentOutcome.ParseFailed(raw, err.message ?: "parse error")
        }
        val result = dispatcher.dispatch(command)
        return QAgentOutcome.Handled(command, result, raw)
    }
}

sealed interface QAgentOutcome {
    data class Handled(val command: QCommand, val result: QCommandResult, val raw: String) : QAgentOutcome
    data class ParseFailed(val raw: String, val reason: String) : QAgentOutcome
}
