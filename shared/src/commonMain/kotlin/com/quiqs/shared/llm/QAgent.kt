@file:OptIn(ExperimentalUuidApi::class)

package com.quiqs.shared.llm

import com.quiqs.shared.Alarm
import com.quiqs.shared.QRepository
import com.quiqs.shared.snappedToRule
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
 * Exposes one top-level method per `(mode × input channel)` cell across edit, query, and
 * the combined new-or-filter mode. Each wraps the same private [run] helper with the
 * right prompt and tool set.
 */
class QAgent(
    private val engine: LlmEngine,
    private val repository: QRepository,
    private val promptBuilder: LlmPromptBuilder,
    private val parser: LlmCommandParser = LlmCommandParser(),
    private val dispatcher: QCommandDispatcher = QCommandDispatcher(repository),
) {
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
            prompt = promptBuilder.buildPromptText(prompt),
            systemInstruction = promptBuilder.systemForQuery,
            audioPath = null,
            tools = queryToolsFor(repository),
            onChunk = onChunk,
        )

    /** Voice variant of [queryFromText]. */
    suspend fun queryFromVoice(audioPath: String, onChunk: (String) -> Unit = {}): QAgentOutcome =
        run(
            prompt = promptBuilder.buildPromptAudio(),
            systemInstruction = promptBuilder.systemForQuery,
            audioPath = audioPath,
            tools = queryToolsFor(repository),
            onChunk = onChunk,
        )

    /**
     * Combined mode: the model picks between creating a new Q and filtering/sorting the
     * existing list based on the user's intent. The dispatcher applies whichever command
     * the model emits, so callers should be ready to handle both [QCommand.Create] and
     * [QCommand.FilterSort] outcomes.
     */
    suspend fun newOrFilterFromText(prompt: String, onChunk: (String) -> Unit = {}): QAgentOutcome =
        run(
            prompt = promptBuilder.buildPromptText(prompt),
            systemInstruction = promptBuilder.systemForNewOrFilter,
            audioPath = null,
            onChunk = onChunk,
        )

    /** Voice variant of [newOrFilterFromText]. */
    suspend fun newOrFilterFromVoice(audioPath: String, onChunk: (String) -> Unit = {}): QAgentOutcome =
        run(
            prompt = promptBuilder.buildPromptAudio(),
            systemInstruction = promptBuilder.systemForNewOrFilter,
            audioPath = audioPath,
            onChunk = onChunk,
        )

    /**
     * Voice variant of new-or-filter that surfaces per-field updates as the JSON
     * streams in (via [onFieldUpdate]) and returns the parsed command *without*
     * dispatching it. The caller decides what to do with the result — typically
     * `Create` is left undispatched (the streaming UI lets the user review and
     * save manually) while other command variants are dispatched as usual.
     *
     * Use this in flows that show a live-filling sheet; for everything else, prefer
     * the dispatching [newOrFilterFromVoice].
     */
    suspend fun newOrFilterFromVoiceStreaming(
        audioPath: String,
        onChunk: (String) -> Unit = {},
        onFieldUpdate: (StreamingFieldUpdate) -> Unit = {},
    ): QAgentParseOutcome = parseStreaming(
        prompt = promptBuilder.buildPromptAudio(),
        audioPath = audioPath,
        onChunk = onChunk,
        onFieldUpdate = onFieldUpdate,
    )

    /**
     * Text variant of [newOrFilterFromVoiceStreaming], used when the voice prompt has
     * already been turned into text by a separate speech-to-text step. Same streaming
     * and no-dispatch contract.
     */
    suspend fun newOrFilterFromTextStreaming(
        prompt: String,
        onChunk: (String) -> Unit = {},
        onFieldUpdate: (StreamingFieldUpdate) -> Unit = {},
    ): QAgentParseOutcome = parseStreaming(
        prompt = promptBuilder.buildPromptText(prompt),
        audioPath = null,
        onChunk = onChunk,
        onFieldUpdate = onFieldUpdate,
    )

    /** Timing of the most recent engine call. See [LlmEngine.lastMetrics]. */
    val lastMetrics: LlmMetrics? get() = engine.lastMetrics

    private suspend fun parseStreaming(
        prompt: String,
        audioPath: String?,
        onChunk: (String) -> Unit,
        onFieldUpdate: (StreamingFieldUpdate) -> Unit,
    ): QAgentParseOutcome = withContext(Dispatchers.Default) {
        val extractor = StreamingCommandExtractor()
        val raw = engine.generate(
            prompt = prompt,
            systemInstruction = promptBuilder.systemForNewOrFilter,
            audioPath = audioPath,
            tools = emptyList(),
            onChunk = { piece ->
                extractor.accept(piece) { update -> onFieldUpdate(update.corrected()) }
                onChunk(piece)
            },
        )
        val command = parser.parse(raw).getOrElse { err ->
            return@withContext QAgentParseOutcome.ParseFailed(raw, err.message ?: "parse error")
        }
        QAgentParseOutcome.Parsed(command.corrected(), raw)
    }

    /**
     * Dispatch a command obtained via [newOrFilterFromVoiceStreaming]. Exposed so the
     * VM can run the dispatch step after deciding (based on the command's type)
     * whether to dispatch at all.
     */
    suspend fun dispatch(command: QCommand): QCommandResult = dispatcher.dispatch(command)

    /**
     * Fixes recurring alarms the model got wrong on the calendar (see
     * [snappedToRule]). Applied only to LLM output — alarms the user builds by hand
     * keep exactly the dates they picked.
     */
    private fun List<Alarm>.corrected(): List<Alarm> {
        val now = promptBuilder.clock()
        val tz = promptBuilder.timeZone
        return map { if (it is Alarm.RecurringTime) it.snappedToRule(now, tz) else it }
    }

    private fun QCommand.corrected(): QCommand = when (this) {
        is QCommand.Create -> copy(alarms = alarms.corrected())
        is QCommand.Update -> copy(alarms = alarms?.corrected())
        else -> this
    }

    private fun StreamingFieldUpdate.corrected(): StreamingFieldUpdate = when (this) {
        is StreamingFieldUpdate.Alarms -> StreamingFieldUpdate.Alarms(value.corrected())
        else -> this
    }

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
            }.corrected()
            val result = dispatcher.dispatch(command)
            QAgentOutcome.Handled(command, result, raw)
        }
}

/** Parse-only result of an LLM turn — no dispatch has run yet. */
sealed interface QAgentParseOutcome {
    /** The model returned a valid [QCommand]; caller decides whether to dispatch. */
    data class Parsed(val command: QCommand, val raw: String) : QAgentParseOutcome
    /** The model's output could not be parsed. [raw] preserves it for diagnostics. */
    data class ParseFailed(val raw: String, val reason: String) : QAgentParseOutcome
}

/** Result of a single [QAgent] invocation. */
sealed interface QAgentOutcome {
    /** The model returned a valid [QCommand] and the dispatcher applied it. */
    data class Handled(val command: QCommand, val result: QCommandResult, val raw: String) : QAgentOutcome
    /** The model's output could not be parsed into a [QCommand]. [raw] preserves it for diagnostics. */
    data class ParseFailed(val raw: String, val reason: String) : QAgentOutcome
}
