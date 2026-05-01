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
import com.quiqs.shared.QFilterSort
import com.quiqs.shared.QRepository

/**
 * Composable entry point shared by every platform launcher (Android activity, desktop
 * window, iOS view controller).
 *
 * Routing is intentionally trivial — a single `mutableStateOf<Route>` flips between the
 * three screens. LLM and audio dependencies are injected via [agentProvider] /
 * [recorderProvider]; targets without those capabilities pass the `NoAgent` / `NoRecorder`
 * stubs and the prompt screen surfaces a friendly unavailable message.
 */
@Composable
fun App(
    repository: QRepository,
    agentProvider: AgentProvider = NoAgent,
    recorderProvider: RecorderProvider = NoRecorder,
) {
    var route by remember { mutableStateOf<Route>(Route.List) }
    var filterSort by remember { mutableStateOf(QFilterSort()) }
    MaterialTheme {
        when (val current = route) {
            is Route.List -> QListScreen(
                repository = repository,
                filterSort = filterSort,
                agentProvider = agentProvider,
                recorderProvider = recorderProvider,
                onOpenQ = { id -> route = Route.Detail(id = id) },
                onQueryQs = { route = Route.Prompt(PromptMode.Query) },
                onFilterSortApplied = { spec -> filterSort = spec },
                onClearFilterSort = { filterSort = QFilterSort() },
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
                onFilterSortApplied = { spec ->
                    filterSort = spec
                    route = Route.List
                },
            )
        }
    }
}
