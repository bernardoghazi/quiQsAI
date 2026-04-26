package com.quiqs.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quiqs.shared.Alarm
import com.quiqs.shared.Q
import com.quiqs.shared.QRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Manual-edit screen for a single Q.
 *
 * Doubles as the "create new" screen when [qId] is `null` — in that mode only the text
 * field and Save button render. When a [qId] is supplied, the screen subscribes to
 * [QRepository.observeById], shows tags/alarms/timestamps, and adds Edit-with-AI and
 * Delete actions.
 *
 * @param onEditWithAi Invoked when the user wants to edit this Q via the LLM prompt
 *   screen. Only relevant when [qId] is non-null.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun QDetailScreen(
    repository: QRepository,
    qId: Uuid?,
    onBack: () -> Unit,
    onEditWithAi: (Uuid) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val flow: Flow<Q?> = remember(qId) {
        if (qId != null) repository.observeById(qId) else flowOf(null)
    }
    val existing by flow.collectAsState(initial = null)

    var text by remember(qId) { mutableStateOf("") }
    // Gate first-load seeding so user edits aren't overwritten by later observer emissions.
    var initialized by remember(qId) { mutableStateOf(qId == null) }
    LaunchedEffect(existing) {
        val loaded = existing
        if (!initialized && loaded != null) {
            text = loaded.text
            initialized = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (qId == null) "New Q" else "Edit Q") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("What would you like to be reminded of?") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val trimmed = text.trim()
                        scope.launch {
                            if (qId == null) repository.create(trimmed)
                            else repository.update(qId, trimmed)
                            onBack()
                        }
                    },
                    enabled = text.trim().isNotEmpty(),
                ) { Text("Save") }

                if (qId != null) {
                    OutlinedButton(onClick = { onEditWithAi(qId) }) { Text("Edit with AI") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            repository.delete(qId)
                            onBack()
                        }
                    }) { Text("Delete") }
                }
            }

            existing?.let { q ->
                Text("Priority", fontWeight = FontWeight.SemiBold)
                Text(q.priority.wire)
                if (q.tags.isNotEmpty()) {
                    Text("Tags", fontWeight = FontWeight.SemiBold)
                    Text(q.tags.joinToString(", "))
                }
                if (q.alarms.isNotEmpty()) {
                    Text("Alarms", fontWeight = FontWeight.SemiBold)
                    q.alarms.forEach { Text("• ${it.describe()}") }
                }
                Text("Created: ${q.createdAt.toLocalDisplay()}")
                Text("Updated: ${q.updatedAt.toLocalDisplay()}")
            }
        }
    }
}

/** Verbose, debug-friendly alarm description (shown as a bullet in the Alarms section). */
private fun Alarm.describe(): String = when (this) {
    is Alarm.OneOffTime -> "One-off @ ${at.toLocalDisplay()} (raw: $at)"
    is Alarm.RecurringTime -> "Recurring: $rule" + (until?.let { ", until ${it.toLocalDisplay()}" } ?: "")
    is Alarm.Geofence -> "Geofence ${latitude}, ${longitude} (${radiusMeters}m)"
}

/** Formats an instant using the device's local time zone as `YYYY-MM-DD HH:MM`. */
private fun kotlin.time.Instant.toLocalDisplay(): String {
    val local = toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.date} ${local.hour.pad()}:${local.minute.pad()}"
}

private fun Int.pad(): String = toString().padStart(2, '0')
