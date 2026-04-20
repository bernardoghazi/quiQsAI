package com.quiqs.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.quiqs.client.ui.QDetailScreen
import com.quiqs.client.ui.QListScreen
import com.quiqs.client.ui.QPromptScreen
import com.quiqs.shared.QRepository

@Composable
fun App(repository: QRepository, agentProvider: AgentProvider = NoAgent) {
    var route by remember { mutableStateOf<Route>(Route.List) }
    MaterialTheme {
        when (val current = route) {
            is Route.List -> QListScreen(
                repository = repository,
                onNewQ = { route = Route.Detail(id = null) },
                onOpenQ = { id -> route = Route.Detail(id = id) },
                onOpenPrompt = { route = Route.Prompt },
            )
            is Route.Detail -> QDetailScreen(
                repository = repository,
                qId = current.id,
                onBack = { route = Route.List },
            )
            is Route.Prompt -> QPromptScreen(
                agentProvider = agentProvider,
                onBack = { route = Route.List },
            )
        }
    }
}
