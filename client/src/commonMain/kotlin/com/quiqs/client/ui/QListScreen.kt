package com.quiqs.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quiqs.client.ui.components.QCard
import com.quiqs.shared.QRepository
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Landing screen — lists every stored Q and exposes the entry points for creating a new
 * Q (FAB), opening one ([onOpenQ]), or asking a question about them ([onQueryQs]).
 *
 * Subscribes to [QRepository.observeAll], so insertions/deletions re-render automatically.
 *
 * @param onAddQ Triggered by the `+` FAB — callers route to the prompt screen in Create mode.
 * @param onOpenQ Triggered by tapping a list item.
 * @param onQueryQs Triggered by the top-bar "Query" action — callers route to the prompt screen in Query mode.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun QListScreen(
    repository: QRepository,
    onAddQ: () -> Unit,
    onOpenQ: (Uuid) -> Unit,
    onQueryQs: () -> Unit,
) {
    val qs by repository.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("quiQs") },
                actions = {
                    TextButton(onClick = onQueryQs) { Text("Query") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddQ) { Text("+", fontSize = 24.sp) }
        },
    ) { padding ->
        if (qs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No Qs yet — tap + to add one")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(qs, key = { it.id.toString() }) { q ->
                    QCard(
                        q = q,
                        checked = q.completed,
                        onCheckedChange = { checked ->
                            scope.launch { repository.update(q.id, completed = checked) }
                        },
                        onClick = { onOpenQ(q.id) },
                    )
                }
            }
        }
    }
}
