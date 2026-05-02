package com.quiqs.client.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quiqs.client.AgentProvider
import com.quiqs.client.audio.RecorderProvider
import com.quiqs.client.ui.components.QBottomBar
import com.quiqs.client.ui.components.QCard
import com.quiqs.client.ui.components.QVoicePromptDialog
import com.quiqs.shared.QFilterSort
import com.quiqs.shared.QRepository
import com.quiqs.shared.applyTo
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Landing screen — lists every stored Q and exposes the entry points for opening one
 * ([onOpenQ]), asking a question about them ([onQueryQs]), or invoking the combined
 * "new Q or filter" voice prompt by long-pressing the FAB.
 *
 * The list is rendered after applying [filterSort] as a pure projection. When the spec
 * is non-default an "Active filter" indicator surfaces in the top bar with a Clear
 * affordance ([onClearFilterSort]).
 *
 * The FAB drives a press-and-hold voice flow: the long-press tells [QListViewModel] to
 * open [QVoicePromptDialog] and start recording; on release the VM runs
 * `newOrFilterFromVoice`, streams chunks into the dialog, and emits a filter+sort spec
 * via [QListViewModel.filterSortApplied] which the screen forwards through
 * [onFilterSortApplied].
 *
 * Subscribes to [QRepository.observeAll], so insertions/deletions re-render automatically.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun QListScreen(
    repository: QRepository,
    filterSort: QFilterSort,
    agentProvider: AgentProvider,
    recorderProvider: RecorderProvider,
    onOpenQ: (Uuid) -> Unit,
    onQueryQs: () -> Unit,
    onFilterSortApplied: (QFilterSort) -> Unit,
    onClearFilterSort: () -> Unit,
) {
    val all by repository.observeAll().collectAsState(initial = emptyList())
    val qs = remember(all, filterSort) { filterSort.applyTo(all) }
    val scope = rememberCoroutineScope()

    val vm: QListViewModel = viewModel { QListViewModel(agentProvider, recorderProvider) }
    val voiceState by vm.voiceState.collectAsState()
    LaunchedEffect(vm, onFilterSortApplied) {
        vm.filterSortApplied.collect { onFilterSortApplied(it) }
    }

    // Tracks whether the current pointer-down crossed the long-press threshold, so the
    // shared `onPress` handler only ends the recording when a long press actually fired.
    var longPressActive by remember { mutableStateOf(false) }

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
            // Hand-rolled FAB: Material3's FloatingActionButton wraps a clickable Surface
            // whose internal detectTapAndPress consumes the pointer-down event, which
            // prevents our outer detectTapGestures from ever firing onLongPress. Using a
            // non-clickable Surface keeps our pointerInput as the sole gesture handler.
            Surface(
                modifier = Modifier
                    .size(56.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                // Awaits until press is released.
                                tryAwaitRelease()
                                if (longPressActive) {
                                    longPressActive = false
                                    vm.endLongPress()
                                }
                            },
                            onLongPress = {
                                longPressActive = true
                                vm.beginLongPress()
                            },
                        )
                    },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 6.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = AudioWaveformIcon,
                        contentDescription = "Hold to record voice prompt",
                        modifier = Modifier.size(24.dp),
                    )
                }
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
                    if (all.isEmpty()) "No Qs yet — hold the mic to add one"
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

    QVoicePromptDialog(state = voiceState, onDismiss = vm::dismissDialog)
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
