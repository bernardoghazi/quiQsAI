package com.quiqs.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.quiqs.client.AgentProvider
import com.quiqs.client.AgentStatus
import com.quiqs.client.PromptMode
import com.quiqs.client.audio.AudioRecorder
import com.quiqs.client.audio.RecorderProvider
import com.quiqs.client.audio.RecorderStatus
import com.quiqs.shared.QFilterSort
import com.quiqs.shared.llm.QAgent
import com.quiqs.shared.llm.QAgentOutcome
import com.quiqs.shared.llm.QCommandResult
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.launch

/**
 * The LLM prompt screen shared by create/edit/query modes.
 *
 * Renders a single text field plus Send and Record buttons. On send, dispatches to the
 * appropriate `QAgent.*FromText` / `*FromVoice` method based on [mode]. While the model
 * streams, it shows the raw partial output in a monospace block and records timing
 * (TTFT, total duration, chunk count, decode rate) to display under the answer.
 *
 * @param agentProvider Lazy source of a [QAgent] — resolved once per composition.
 * @param recorderProvider Lazy source of an [AudioRecorder] — resolved on first tap of
 *   the Record button so the permission prompt doesn't fire on screen entry.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun QPromptScreen(
    agentProvider: AgentProvider,
    recorderProvider: RecorderProvider,
    mode: PromptMode,
    onBack: () -> Unit,
    onFilterSortApplied: (QFilterSort) -> Unit = {},
) {
    var agent by remember { mutableStateOf<QAgent?>(null) }
    var agentError by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recorderError by remember { mutableStateOf<String?>(null) }
    var activeRecorder by remember { mutableStateOf<AudioRecorder?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var rawOutput by remember { mutableStateOf<String?>(null) }
    var streaming by remember { mutableStateOf("") }
    var lastDuration by remember { mutableStateOf<Duration?>(null) }
    var timeToFirstChunk by remember { mutableStateOf<Duration?>(null) }
    var chunkCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(agentProvider) {
        try {
            when (val result = agentProvider.get()) {
                is AgentStatus.Available -> agent = result.agent
                is AgentStatus.Unavailable -> agentError = result.reason
            }
        } catch (t: Throwable) {
            agentError = t.message ?: t::class.simpleName ?: "Failed to initialize LLM"
        }
    }

    /**
     * Runs a single [QAgent] call with common UI bookkeeping: resets status, measures
     * TTFT/total duration, and surfaces either a success message or parse-failure
     * debugging output. [block] picks the mode-specific agent method to invoke.
     */
    suspend fun runOutcome(block: suspend QAgent.((String) -> Unit) -> QAgentOutcome) {
        val a = agent ?: return
        busy = true
        status = null
        rawOutput = null
        streaming = ""
        lastDuration = null
        timeToFirstChunk = null
        chunkCount = 0
        val mark = TimeSource.Monotonic.markNow()
        val onChunk: (String) -> Unit = { piece ->
            if (chunkCount == 0) timeToFirstChunk = mark.elapsedNow()
            chunkCount += 1
            streaming += piece
        }
        runCatching { a.block(onChunk) }
            .onSuccess { outcome ->
                when (outcome) {
                    is QAgentOutcome.Handled -> {
                        status = outcome.result.message
                        rawOutput = outcome.raw
                        // Filter/sort produces a spec, not a state mutation — hand it
                        // back to the caller and pop the screen so the list reflects it.
                        val res = outcome.result
                        if (res is QCommandResult.FilterSorted) {
                            onFilterSortApplied(res.spec)
                        }
                    }
                    is QAgentOutcome.ParseFailed -> {
                        status = "Couldn't parse LLM output: ${outcome.reason}"
                        rawOutput = outcome.raw
                    }
                }
            }
            .onFailure { status = "Error: ${it.message ?: it::class.simpleName}" }
        lastDuration = mark.elapsedNow()
        busy = false
    }

    val title = when (mode) {
        PromptMode.Create -> "Add Q"
        PromptMode.Query -> "Query Qs"
        PromptMode.FilterSort -> "Filter & sort"
        is PromptMode.Edit -> "Edit Q"
    }
    val placeholder = when (mode) {
        PromptMode.Create -> "What should I remind you of?"
        PromptMode.Query -> "Ask about your Qs"
        PromptMode.FilterSort -> "How should I filter/sort the list?"
        is PromptMode.Edit -> "Describe the change"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("<") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                agentError != null -> Text("LLM unavailable: $agentError")
                agent == null -> {
                    CircularProgressIndicator()
                    Text("Loading model…")
                }
                else -> {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text(placeholder) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy && !recording && prompt.isNotBlank(),
                            onClick = {
                                val p = prompt
                                scope.launch {
                                    runOutcome { cb ->
                                        when (mode) {
                                            PromptMode.Create -> createFromText(p, cb)
                                            PromptMode.Query -> queryFromText(p, cb)
                                            PromptMode.FilterSort -> filterSortFromText(p, cb)
                                            is PromptMode.Edit -> editFromText(mode.qId, p, cb)
                                        }
                                    }
                                }
                            },
                        ) { Text(if (busy) "Thinking…" else "Send") }

                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                if (!recording) {
                                    scope.launch {
                                        recorderError = null
                                        when (val r = recorderProvider.get()) {
                                            is RecorderStatus.Available -> {
                                                activeRecorder = r.recorder
                                                runCatching { r.recorder.start() }
                                                    .onSuccess { recording = true }
                                                    .onFailure {
                                                        recorderError = it.message ?: "Failed to start recorder"
                                                        activeRecorder = null
                                                    }
                                            }
                                            is RecorderStatus.PermissionRequired ->
                                                recorderError = "Microphone permission denied."
                                            is RecorderStatus.Unavailable ->
                                                recorderError = r.reason
                                        }
                                    }
                                } else {
                                    val rec = activeRecorder ?: return@OutlinedButton
                                    scope.launch {
                                        val path = runCatching { rec.stop() }.getOrElse { err ->
                                            recording = false
                                            activeRecorder = null
                                            recorderError = err.message ?: "Failed to stop recorder"
                                            return@launch
                                        }
                                        recording = false
                                        activeRecorder = null
                                        runOutcome { cb ->
                                            when (mode) {
                                                PromptMode.Create -> createFromVoice(path, cb)
                                                PromptMode.Query -> queryFromVoice(path, cb)
                                                PromptMode.FilterSort -> filterSortFromVoice(path, cb)
                                                is PromptMode.Edit -> editFromVoice(mode.qId, path, cb)
                                            }
                                        }
                                    }
                                }
                            },
                        ) { Text(if (recording) "Stop" else "Record") }
                    }

                    recorderError?.let { Text("Mic: $it") }
                    if (busy && streaming.isNotEmpty()) {
                        Text(text = streaming, fontFamily = FontFamily.Monospace)
                    }
                    lastDuration?.let { total ->
                        val ttft = timeToFirstChunk
                        // Decode rate = chunks after the first / time spent decoding
                        // them. Scaled ×10 then split to format as "N.N chunk/s" without
                        // relying on platform-specific float formatters.
                        val decode = if (ttft != null && chunkCount > 1) {
                            val decodeMs = (total - ttft).inWholeMilliseconds.coerceAtLeast(1)
                            val rateTimes10 = ((chunkCount - 1) * 10_000 / decodeMs).toInt()
                            val w = rateTimes10 / 10
                            val f = rateTimes10 % 10
                            " | decode $w.$f chunk/s"
                        } else ""
                        Text(
                            "Total ${total.inWholeMilliseconds} ms" +
                                (ttft?.let { " | TTFT ${it.inWholeMilliseconds} ms" } ?: "") +
                                " | chunks $chunkCount" +
                                decode,
                        )
                    }
                    status?.let { Text(it) }
                    rawOutput?.let {
                        Text(
                            text = it,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
