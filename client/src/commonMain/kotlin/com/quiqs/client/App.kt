package com.quiqs.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.quiqs.client.audio.NoRecorder
import com.quiqs.client.audio.RecorderProvider
import com.quiqs.client.ui.QDetailScreen
import com.quiqs.client.ui.QListScreen
import com.quiqs.client.ui.QPromptScreen
import com.quiqs.shared.QRepository

@Composable
fun App(
    repository: QRepository,
    agentProvider: AgentProvider = NoAgent,
    recorderProvider: RecorderProvider = NoRecorder,
) {
    var route by remember { mutableStateOf<Route>(Route.List) }
    MaterialTheme {
        when (val current = route) {
            is Route.List -> QListScreen(
                repository = repository,
                onAddQ = { route = Route.Prompt(PromptMode.Create) },
                onOpenQ = { id -> route = Route.Detail(id = id) },
                onQueryQs = { route = Route.Prompt(PromptMode.Query) },
            )
            is Route.Detail -> QDetailScreen(
                repository = repository,
                qId = current.id,
                onBack = { route = Route.List },
                onEditWithAi = { id -> route = Route.Prompt(PromptMode.Edit(id)) },
            )
            is Route.Prompt -> QPromptScreen(
                agentProvider = agentProvider,
                recorderProvider = recorderProvider,
                mode = current.mode,
                onBack = { route = Route.List },
            )
        }
    }
}
