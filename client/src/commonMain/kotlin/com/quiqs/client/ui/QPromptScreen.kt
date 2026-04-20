package com.quiqs.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
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
import com.quiqs.shared.llm.QAgent
import com.quiqs.shared.llm.QAgentOutcome
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QPromptScreen(agentProvider: AgentProvider, onBack: () -> Unit) {
    var agent by remember { mutableStateOf<QAgent?>(null) }
    var agentError by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var rawOutput by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(agentProvider) {
        try {
            when (val status = agentProvider.get()) {
                is AgentStatus.Available -> agent = status.agent
                is AgentStatus.Unavailable -> agentError = status.reason
            }
        } catch (t: Throwable) {
            agentError = t.message ?: t::class.simpleName ?: "Failed to initialize LLM"
        }
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
                    Button(
                        enabled = !busy && prompt.isNotBlank(),
                        onClick = {
                            val a = agent ?: return@Button
                            val p = prompt
                            busy = true
                            status = null
                            rawOutput = null
                            scope.launch {
                                runCatching { a.handle(p) }
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
                                busy = false
                            }
                        },
                    ) { Text(if (busy) "Thinking…" else "Send") }

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
