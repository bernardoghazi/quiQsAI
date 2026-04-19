package com.quiqs.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.quiqs.shared.QRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun QListScreen(
    repository: QRepository,
    onNewQ: () -> Unit,
    onOpenQ: (Uuid) -> Unit,
) {
    val qs by repository.observeAll().collectAsState(initial = emptyList())

    Scaffold(
        topBar = { TopAppBar(title = { Text("quiQs") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewQ) { Text("+", fontSize = 24.sp) }
        },
    ) { padding ->
        if (qs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No Qs yet — tap + to create one")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(qs, key = { it.id.toString() }) { q ->
                    ListItem(
                        headlineContent = { Text(q.text) },
                        modifier = Modifier.clickable { onOpenQ(q.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
