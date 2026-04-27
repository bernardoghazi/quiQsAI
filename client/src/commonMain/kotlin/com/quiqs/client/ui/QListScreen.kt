package com.quiqs.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.quiqs.client.ui.components.QBottomBar
import com.quiqs.client.ui.components.QCard
import com.quiqs.shared.QFilterSort
import com.quiqs.shared.QRepository
import com.quiqs.shared.applyTo
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Landing screen — lists every stored Q and exposes the entry points for opening one
 * ([onOpenQ]), asking a question about them ([onQueryQs]), or invoking the combined
 * "new Q or filter" voice prompt ([onAudioPrompt]).
 *
 * The list is rendered after applying [filterSort] as a pure projection. When the spec
 * is non-default an "Active filter" indicator surfaces in the top bar with a Clear
 * affordance ([onClearFilterSort]).
 *
 * Subscribes to [QRepository.observeAll], so insertions/deletions re-render automatically.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun QListScreen(
    repository: QRepository,
    filterSort: QFilterSort,
    onOpenQ: (Uuid) -> Unit,
    onQueryQs: () -> Unit,
    onAudioPrompt: () -> Unit,
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
            FloatingActionButton(
                onClick = onAudioPrompt,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    imageVector = AudioWaveformIcon,
                    contentDescription = "Voice prompt",
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        bottomBar = {
            // Bottom-bar actions are intentionally no-ops for now — wiring lands
            // alongside the dedicated UI affordances in a follow-up.
            QBottomBar(
                onFilterSort = {},
                onAdd = {},
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

/**
 * Five-bar audio level glyph used by the voice-prompt FAB. Inline to avoid pulling in
 * `material-icons-extended` (same rationale as `RepeatIcon` in `QAlarmLabel`).
 *
 * Bar layout in the 24×24 viewport: heights 4-12-20-12-4 px, centered vertically,
 * spaced 4 px apart starting at x=3.
 */
private val AudioWaveformIcon: ImageVector = ImageVector.Builder(
    name = "AudioWaveform",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 10f); horizontalLineTo(5f); verticalLineTo(14f); horizontalLineTo(3f); close()
        moveTo(7f, 6f); horizontalLineTo(9f); verticalLineTo(18f); horizontalLineTo(7f); close()
        moveTo(11f, 2f); horizontalLineTo(13f); verticalLineTo(22f); horizontalLineTo(11f); close()
        moveTo(15f, 6f); horizontalLineTo(17f); verticalLineTo(18f); horizontalLineTo(15f); close()
        moveTo(19f, 10f); horizontalLineTo(21f); verticalLineTo(14f); horizontalLineTo(19f); close()
    }
}.build()
