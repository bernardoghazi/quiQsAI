package com.quiqs.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quiqs.client.ui.components.QBottomBar
import com.quiqs.client.ui.components.QCard
import com.quiqs.shared.QFilterSort
import com.quiqs.shared.QRepository
import com.quiqs.shared.applyTo
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Landing screen — lists every stored Q and exposes the entry points for creating a new
 * Q, opening one ([onOpenQ]), or asking a question about them ([onQueryQs]).
 *
 * The list is rendered after applying [filterSort] as a pure projection. When the spec
 * is non-default an "Active filter" indicator surfaces in the top bar with a Clear
 * affordance ([onClearFilterSort]). [onFilterSort] navigates to the NL filter prompt.
 *
 * Subscribes to [QRepository.observeAll], so insertions/deletions re-render automatically.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun QListScreen(
    repository: QRepository,
    filterSort: QFilterSort,
    onAddQ: () -> Unit,
    onOpenQ: (Uuid) -> Unit,
    onQueryQs: () -> Unit,
    onFilterSort: () -> Unit,
    onClearFilterSort: () -> Unit,
) {
    val all by repository.observeAll().collectAsState(initial = emptyList())
    val qs = remember(all, filterSort) { filterSort.applyTo(all) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("quiQs") },
                actions = {
                    if (!filterSort.isDefault) {
                        TextButton(onClick = onClearFilterSort) { Text("Clear filter") }
                    }
                    TextButton(onClick = onQueryQs) { Text("Query") }
                },
            )
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = onAddQ) { Text("+", fontSize = 24.sp) }
            }
        },
        bottomBar = {
            QBottomBar(
                onFilterSort = onFilterSort,
                onAdd = onAddQ,
                onOverflow = {},
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        },
    ) { padding ->
        if (qs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (all.isEmpty()) "No Qs yet — tap + to add one"
                    else "No Qs match the current filter",
                )
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
