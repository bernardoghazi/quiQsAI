package com.quiqs.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quiqs.client.AgentProvider
import com.quiqs.client.AgentStatus
import com.quiqs.client.audio.AudioRecorder
import com.quiqs.client.audio.RecorderProvider
import com.quiqs.client.audio.RecorderStatus
import com.quiqs.client.ui.components.QDraft
import com.quiqs.client.ui.components.VoicePromptState
import com.quiqs.shared.QFilterSort
import com.quiqs.shared.QRepository
import com.quiqs.shared.llm.QAgent
import com.quiqs.shared.llm.QAgentParseOutcome
import com.quiqs.shared.llm.QCommand
import com.quiqs.shared.llm.QCommandResult
import com.quiqs.shared.llm.StreamingFieldUpdate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds the list-screen FAB voice-prompt state machine.
 *
 * Compose obtains the instance via `viewModel { ... }`, so the recording/processing
 * lifecycle survives recomposition (and Android config changes) without leaking back
 * into the composable. Communication out:
 *  - [voiceState]: drives [com.quiqs.client.ui.components.QVoicePromptDialog]
 *  - [filterSortApplied]: one-shot effect emitted when the LLM returns a filter+sort
 *    command — the screen forwards each value into the app's spec holder.
 */
class QListViewModel(
    private val agentProvider: AgentProvider,
    private val recorderProvider: RecorderProvider,
    private val repository: QRepository,
) : ViewModel() {

    private val _voiceState = MutableStateFlow<VoicePromptState>(VoicePromptState.Idle)
    val voiceState: StateFlow<VoicePromptState> = _voiceState.asStateFlow()

    private val _filterSortApplied = Channel<QFilterSort>(capacity = 8, BufferOverflow.DROP_OLDEST)
    val filterSortApplied: Flow<QFilterSort> = _filterSortApplied.receiveAsFlow()

    // Driven by the streaming voice flow. While the LLM is emitting fields for a
    // `create` command, this holds an incrementally-filled draft; once the LLM
    // finishes, it still holds the final draft (with voice-note path folded in) so
    // the sheet can flush it into local state. The screen clears it back to null
    // when the sheet dismisses.
    private val _streamingDraft = MutableStateFlow<QDraft?>(null)
    val streamingDraft: StateFlow<QDraft?> = _streamingDraft.asStateFlow()

    // Separate lock state. True only while the LLM is still streaming; transitions
    // to false the moment the stream resolves to a parsed command. The sheet uses
    // this to keep Save disabled during streaming.
    private val _streamingActive = MutableStateFlow(false)
    val streamingActive: StateFlow<Boolean> = _streamingActive.asStateFlow()

    private val _openCreateSheet = Channel<Unit>(capacity = 1, BufferOverflow.DROP_OLDEST)
    val openCreateSheet: Flow<Unit> = _openCreateSheet.receiveAsFlow()

    private var agent: QAgent? = null
    private var agentError: String? = null
    private var activeRecorder: AudioRecorder? = null

    init {
        viewModelScope.launch {
            try {
                when (val r = agentProvider.get()) {
                    is AgentStatus.Available -> agent = r.agent
                    is AgentStatus.Unavailable -> agentError = r.reason
                }
            } catch (t: Throwable) {
                agentError = t.message ?: t::class.simpleName ?: "Failed to initialize LLM"
            }
        }
    }

    /**
     * Long-press fired on the FAB: open the dialog, acquire a recorder, start capture.
     * Audio start is suspend; a release that lands before [AudioRecorder.start] returns
     * is handled by [endLongPress] checking [activeRecorder].
     */
    fun beginLongPress() {
        if (agent == null) {
            _voiceState.value = VoicePromptState.Error(agentError ?: "LLM is still loading.")
            return
        }
        _voiceState.value = VoicePromptState.Recording
        viewModelScope.launch {
            when (val r = recorderProvider.get()) {
                is RecorderStatus.Available -> {
                    activeRecorder = r.recorder
                    runCatching { r.recorder.start() }.onFailure {
                        activeRecorder = null
                        _voiceState.value =
                            VoicePromptState.Error(it.message ?: "Failed to start recorder.")
                    }
                }

                is RecorderStatus.PermissionRequired ->
                    _voiceState.value = VoicePromptState.Error("Microphone permission denied.")

                is RecorderStatus.Unavailable ->
                    _voiceState.value = VoicePromptState.Error(r.reason)
            }
        }
    }

    /**
     * Long-press released: stop capture, run the LLM, and settle.
     *
     * The new-or-filter call runs in streaming mode. As soon as `"type":"create"`
     * lands in the JSON, [_streamingDraft] flips to an empty draft and the
     * [openCreateSheet] channel pings — the screen opens the sheet, which then
     * follows [_streamingDraft] for live-fill. Subsequent field updates merge into
     * the draft. When the LLM finishes, the captured voice note path is folded in
     * and [_streamingDraft] becomes a final "frozen" value (still non-null), then
     * cleared to null one step later so the sheet switches to local-edit mode —
     * Save remains the user's gesture.
     *
     * For non-create commands (filter/sort, answer, update, delete) the streaming
     * draft stays null throughout; we dispatch the command at the end exactly like
     * the previous non-streaming path did.
     */
    fun endLongPress() {
        viewModelScope.launch {
            val rec = activeRecorder ?: return@launch
            val path = runCatching { rec.stop() }.getOrElse { err ->
                activeRecorder = null
                _voiceState.value =
                    VoicePromptState.Error(err.message ?: "Failed to stop recorder.")
                return@launch
            }
            activeRecorder = null
            val a = agent ?: run {
                _voiceState.value = VoicePromptState.Error("LLM not available.")
                return@launch
            }
            _voiceState.value = VoicePromptState.Processing("")
            _streamingActive.value = true
            val outcome = runCatching {
                a.newOrFilterFromVoiceStreaming(
                    audioPath = path,
                    onChunk = { piece ->
                        _voiceState.update { current ->
                            if (current is VoicePromptState.Processing) {
                                VoicePromptState.Processing(current.streaming + piece)
                            } else {
                                current
                            }
                        }
                    },
                    onFieldUpdate = { update -> applyStreamingUpdate(update) },
                )
            }.getOrElse {
                _streamingActive.value = false
                _streamingDraft.value = null
                _voiceState.value =
                    VoicePromptState.Error("Error: ${it.message ?: it::class.simpleName}")
                return@launch
            }
            _voiceState.value = when (outcome) {
                is QAgentParseOutcome.Parsed -> {
                    when (val cmd = outcome.command) {
                        is QCommand.Create -> {
                            // Fold the captured WAV path into the final draft so the
                            // sheet shows the voice-note pill on review. Lock comes
                            // off afterward — the order matters only insofar as the
                            // sheet observes the draft non-null before Save unlocks.
                            _streamingDraft.update { draft ->
                                (draft ?: QDraft()).copy(voiceNotePath = path)
                            }
                            _streamingActive.value = false
                            // The sheet is the feedback channel for the create flow,
                            // so keep the voice dialog dismissed rather than briefly
                            // flashing "Done" on top of it.
                            VoicePromptState.Idle
                        }
                        else -> {
                            // Non-create: dispatch and surface the usual feedback.
                            _streamingActive.value = false
                            _streamingDraft.value = null
                            val result = a.dispatch(cmd)
                            val attachTo = when (result) {
                                is QCommandResult.Updated -> result.id
                                else -> null
                            }
                            if (attachTo != null) {
                                runCatching { repository.setVoiceNote(attachTo, path) }
                            }
                            if (result is QCommandResult.FilterSorted) {
                                _filterSortApplied.trySend(result.spec)
                            }
                            VoicePromptState.Done(result.message)
                        }
                    }
                }
                is QAgentParseOutcome.ParseFailed -> {
                    _streamingActive.value = false
                    _streamingDraft.value = null
                    VoicePromptState.Error("Couldn't parse LLM output: ${outcome.reason}")
                }
            }
        }
    }

    /**
     * Clears the streaming draft. Called by the screen when the user dismisses the
     * sheet after a streamed Create, so a subsequent voice flow starts from a clean
     * state.
     */
    fun clearStreamingDraft() {
        _streamingDraft.value = null
    }

    /**
     * Fold a single streaming-field update into [_streamingDraft]. The first `type`
     * update is what opens the sheet — when it lands and is "create", we seed an
     * empty draft and ping [_openCreateSheet]; for any other type we leave the
     * draft null so the sheet never opens (filter/answer/update/delete take the
     * existing dispatched-feedback path).
     */
    private fun applyStreamingUpdate(update: StreamingFieldUpdate) {
        when (update) {
            is StreamingFieldUpdate.Type -> {
                if (update.value == "create") {
                    _streamingDraft.value = QDraft()
                    _openCreateSheet.trySend(Unit)
                    // Dismiss the processing dialog now that the sheet is taking
                    // over — leaving it visible would stack on top of the modal.
                    _voiceState.value = VoicePromptState.Idle
                }
            }
            is StreamingFieldUpdate.Text ->
                _streamingDraft.update { it?.copy(text = update.value) }
            is StreamingFieldUpdate.Priority ->
                _streamingDraft.update { it?.copy(priority = update.value) }
            is StreamingFieldUpdate.Tags ->
                _streamingDraft.update { it?.copy(tags = update.value) }
            is StreamingFieldUpdate.Alarms ->
                _streamingDraft.update { it?.copy(alarms = update.value) }
            is StreamingFieldUpdate.Completed ->
                _streamingDraft.update { it?.copy(completed = update.value) }
        }
    }

    fun dismissDialog() {
        _voiceState.value = VoicePromptState.Idle
    }
}
