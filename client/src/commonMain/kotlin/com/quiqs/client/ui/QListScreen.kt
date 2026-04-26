package com.quiqs.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quiqs.shared.Alarm
import com.quiqs.shared.Priority
import com.quiqs.shared.QRepository
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(qs, key = { it.id.toString() }) { q ->
                    ListItem(
                        headlineContent = { Text(q.text) },
                        supportingContent = q.alarms.firstOrNull()?.let { a ->
                            { Text(a.shortLabel()) }
                        },
                        trailingContent = { PriorityBadge(q.priority) },
                        modifier = Modifier.clickable { onOpenQ(q.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Small colored chip indicating the Q's [Priority]. Hidden for medium (the default). */
@Composable
private fun PriorityBadge(priority: Priority) {
    if (priority == Priority.MEDIUM) return
    val (label, bg) = when (priority) {
        Priority.HIGH -> "HIGH" to Color(0xFFD32F2F)
        Priority.LOW -> "LOW" to Color(0xFF757575)
        Priority.MEDIUM -> return
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.sp,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** One-line summary shown under each Q's title in the list. */
private fun Alarm.shortLabel(): String = when (this) {
    is Alarm.OneOffTime -> {
        val local = at.toLocalDateTime(TimeZone.currentSystemDefault())
        "At ${local.date} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    }
    is Alarm.RecurringTime -> "Repeats: $rule"
    is Alarm.Geofence -> "Near $latitude, $longitude"
}
