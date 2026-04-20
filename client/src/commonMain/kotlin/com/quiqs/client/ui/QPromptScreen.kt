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
import com.quiqs.client.audio.AudioRecorder
import com.quiqs.client.audio.RecorderProvider
import com.quiqs.client.audio.RecorderStatus
import com.quiqs.shared.llm.QAgent
import com.quiqs.shared.llm.QAgentOutcome
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QPromptScreen(
    agentProvider: AgentProvider,
    recorderProvider: RecorderProvider,
    onBack: () -> Unit,
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

    suspend fun runOutcome(block: suspend QAgent.() -> QAgentOutcome) {
        val a = agent ?: return
        busy = true
        status = null
        rawOutput = null
        streaming = ""
        lastDuration = null
        val mark = TimeSource.Monotonic.markNow()
        runCatching { a.block() }
            .onSuccess { outcome ->
                when (outcome) {
                    is QAgentOutcome.Handled -> {
                        status = outcome.result.message
                        rawOutput = outcome.raw
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ask quiQs") },
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
                        label = { Text("What do you want?") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy && !recording && prompt.isNotBlank(),
                            onClick = {
                                val p = prompt
                                scope.launch { runOutcome { handle(p) { streaming += it } } }
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
                                        runOutcome { handleVoice(path) { streaming += it } }
                                    }
                                }
                            },
                        ) { Text(if (recording) "Stop" else "Record") }
                    }

                    recorderError?.let { Text("Mic: $it") }
                    if (busy && streaming.isNotEmpty()) {
                        Text(text = streaming, fontFamily = FontFamily.Monospace)
                    }
                    lastDuration?.let { Text("Inference: ${it.inWholeMilliseconds} ms") }
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
