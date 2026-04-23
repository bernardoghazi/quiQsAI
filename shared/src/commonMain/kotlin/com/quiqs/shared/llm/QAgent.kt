@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared.llm

import com.quiqs.shared.QRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Orchestrates the end-to-end LLM round trip for one user action.
 *
 * Pipeline: pick the right system + user prompt for the mode → call [engine].generate →
 * pass the raw output to [parser] → apply the resulting [QCommand] through [dispatcher].
 *
 * Exposes six top-level methods — one per `(mode × input channel)` cell (create, edit,
 * query) × (text, voice). Each wraps the same private [run] helper with the right prompt
 * and tool set.
 */
class QAgent(
    private val engine: LlmEngine,
    private val repository: QRepository,
    private val promptBuilder: LlmPromptBuilder,
    private val parser: LlmCommandParser = LlmCommandParser(),
    private val dispatcher: QCommandDispatcher = QCommandDispatcher(repository),
) {
    /** Create a new Q from a typed prompt. */
    suspend fun createFromText(prompt: String, onChunk: (String) -> Unit = {}): QAgentOutcome =
        run(
            prompt = promptBuilder.buildForCreate(prompt),
            systemInstruction = promptBuilder.systemForCreate,
            audioPath = null,
            onChunk = onChunk,
        )

    /** Create a new Q from a recorded audio prompt. */
    suspend fun createFromVoice(audioPath: String, onChunk: (String) -> Unit = {}): QAgentOutcome =
        run(
            prompt = promptBuilder.buildForCreateAudio(),
            systemInstruction = promptBuilder.systemForCreate,
            audioPath = audioPath,
            onChunk = onChunk,
        )

    /**
     * Edit an existing Q from a typed prompt. Fails fast with [QAgentOutcome.ParseFailed]
     * if [qId] does not resolve to a stored Q.
     */
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

    /** Edit an existing Q from a recorded audio prompt. See [editFromText]. */
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

    /**
     * Answer a user question about their Qs, fetching data on demand via the query
     * tools instead of stuffing the Q list into the system prompt.
     */
    suspend fun queryFromText(prompt: String, onChunk: (String) -> Unit = {}): QAgentOutcome =
        run(
            prompt = promptBuilder.buildForQuery(prompt),
            systemInstruction = promptBuilder.systemForQuery,
            audioPath = null,
            tools = queryToolsFor(repository),
            onChunk = onChunk,
        )

    /** Voice variant of [queryFromText]. */
    suspend fun queryFromVoice(audioPath: String, onChunk: (String) -> Unit = {}): QAgentOutcome =
        run(
            prompt = promptBuilder.buildForQueryAudio(),
            systemInstruction = promptBuilder.systemForQuery,
            audioPath = audioPath,
            tools = queryToolsFor(repository),
            onChunk = onChunk,
        )

    /**
     * Drives one end-to-end LLM turn off the main thread. Runs inference, parses the
     * output, and dispatches the resulting command. Parse failures are surfaced as
     * [QAgentOutcome.ParseFailed] — callers can show the raw output for debugging.
     */
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

/** Result of a single [QAgent] invocation. */
sealed interface QAgentOutcome {
    /** The model returned a valid [QCommand] and the dispatcher applied it. */
    data class Handled(val command: QCommand, val result: QCommandResult, val raw: String) : QAgentOutcome
    /** The model's output could not be parsed into a [QCommand]. [raw] preserves it for diagnostics. */
    data class ParseFailed(val raw: String, val reason: String) : QAgentOutcome
}
