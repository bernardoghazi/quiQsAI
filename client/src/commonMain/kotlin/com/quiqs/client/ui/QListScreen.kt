package com.quiqs.client.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quiqs.client.AgentProvider
import com.quiqs.client.audio.PlayerProvider
import com.quiqs.client.audio.RecorderProvider
import com.quiqs.shared.Alarm
import com.quiqs.client.ui.components.QBottomBar
import com.quiqs.client.ui.components.QCard
import com.quiqs.client.ui.components.QDayView
import com.quiqs.client.ui.components.QDetailsBottomSheet
import com.quiqs.client.ui.components.QDraft
import com.quiqs.client.ui.components.QFilterSortBottomSheet
import com.quiqs.client.ui.components.QVoicePromptDialog
import com.quiqs.client.ui.components.ViewMode
import com.quiqs.shared.Q
import com.quiqs.shared.QFilterSort
import com.quiqs.shared.QRepository
import com.quiqs.shared.applyTo
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import quiqsai.client.generated.resources.Res
import quiqsai.client.generated.resources.trash
import quiqsai.client.generated.resources.wave
import kotlin.uuid.ExperimentalUuidApi

/**
 * Landing screen — lists every stored Q and exposes the entry points for asking a
 * question about them ([onQueryQs]), or invoking the combined "new Q or filter" voice
 * prompt by long-pressing the FAB.
 *
 * Tapping a Q opens an in-place [QDetailsBottomSheet] inspector instead of pushing a
 * dedicated detail screen.
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
    playerProvider: PlayerProvider,
    onQueryQs: () -> Unit,
    onFilterSortApplied: (QFilterSort) -> Unit,
    onClearFilterSort: () -> Unit,
    initialOpenQId: String? = null,
    onInitialOpenQIdConsumed: () -> Unit = {},
) {
    val all by repository.observeAll().collectAsState(initial = emptyList())
    val qs = remember(all, filterSort) { filterSort.applyTo(all) }
    val scope = rememberCoroutineScope()

    val vm: QListViewModel = viewModel { QListViewModel(agentProvider, recorderProvider, repository) }
    val voiceState by vm.voiceState.collectAsState()
    val streamingDraft by vm.streamingDraft.collectAsState()
    val streamingActive by vm.streamingActive.collectAsState()
    LaunchedEffect(vm, onFilterSortApplied) {
        vm.filterSortApplied.collect { onFilterSortApplied(it) }
    }

    // Tracks whether the current pointer-down crossed the long-press threshold, so the
    // shared `onPress` handler only ends the recording when a long press actually fired.
    var longPressActive by remember { mutableStateOf(false) }

    // Drives the details bottom sheet. Set when the user taps a Q card (initial draft
    // seeded from that Q) or the bottom bar's `+` (empty draft); cleared on dismiss
    // (scrim tap or back press), discarding anything the user typed/recorded.
    var sheetRequest by remember { mutableStateOf<SheetRequest?>(null) }

    // Drives the filter+sort bottom sheet shown from the bottom bar's filter button.
    var filterSortSheetOpen by remember { mutableStateOf(false) }

    // Selected timeline view — drives which body composable renders below the bottom
    // bar. The bar's toggle group binds to this state. Defaults to List.
    var viewMode by remember { mutableStateOf(ViewMode.List) }

    // Date the Day view is currently scoped to. Seeded to today on first composition;
    // stepped by the Day header's chevrons. Only consulted when viewMode == Day, but
    // kept hoisted so switching away and back preserves the user's place.
    val systemTz = remember { TimeZone.currentSystemDefault() }
    var dayDate by remember {
        mutableStateOf(Clock.System.now().toLocalDateTime(systemTz).date)
    }

    // Open the sheet as soon as the VM detects "type":"create" in the streaming
    // LLM output. The sheet then follows `streamingDraft` for live-fill until
    // streaming ends; Save calls repository.create with whatever's in the draft
    // at that point.
    LaunchedEffect(vm) {
        vm.openCreateSheet.collect {
            sheetRequest = SheetRequest(
                initial = QDraft(),
                onSave = { draft ->
                    scope.launch {
                        repository.create(
                            text = draft.text,
                            tags = draft.tags,
                            alarms = draft.alarms,
                            priority = draft.priority,
                            completed = draft.completed,
                            voiceNotePath = draft.voiceNotePath,
                        )
                        sheetRequest = null
                        vm.clearStreamingDraft()
                    }
                },
                fromVoiceStream = true,
            )
        }
    }

    // The Q currently pending deletion, surfaced through a confirmation dialog. Set
    // when the user swipes a card past the dismiss threshold; cleared on confirm
    // (after delete runs) or on cancel/dismiss (the swiped card springs back on its
    // own because the swipe-state's confirmValueChange returns false).
    var pendingDelete by remember { mutableStateOf<Q?>(null) }

    // Shared builder for the edit sheet used by both card tap and notification deep
    // link — keeps the save logic in one place.
    val makeEditRequest: (Q) -> SheetRequest = { q ->
        SheetRequest(
            initial = QDraft(
                text = q.text,
                voiceNotePath = q.voiceNotePath,
                alarms = q.alarms,
                priority = q.priority,
                tags = q.tags,
                completed = q.completed,
            ),
            onSave = { draft ->
                scope.launch {
                    repository.update(
                        id = q.id,
                        text = draft.text,
                        tags = draft.tags,
                        alarms = draft.alarms,
                        priority = draft.priority,
                        completed = draft.completed,
                    )
                    if (draft.voiceNotePath != q.voiceNotePath) {
                        repository.setVoiceNote(q.id, draft.voiceNotePath)
                    }
                    sheetRequest = null
                }
            },
            // Tapping trash in the sheet surfaces the confirmation dialog above the
            // open sheet — the sheet stays put so that "Cancel" leaves the user
            // exactly where they were. "Delete" (in the dialog) is responsible for
            // tearing the sheet down once the user actually commits.
            onDelete = {
                pendingDelete = q
            },
        )
    }

    // Open the bottom sheet for the deep-linked Q as soon as it lands in `all`. The
    // unfiltered list is used on purpose — the current filter shouldn't hide a Q the
    // user explicitly tapped a notification to view.
    LaunchedEffect(initialOpenQId, all) {
        if (initialOpenQId == null) return@LaunchedEffect
        val q = all.firstOrNull { it.id.toString() == initialOpenQId } ?: return@LaunchedEffect
        sheetRequest = makeEditRequest(q)
        onInitialOpenQIdConsumed()
    }

    Scaffold(
        containerColor = Color.Transparent,
//        topBar = {
//            TopAppBar(
//                title = { Text("quiQs") },
//                actions = {
//                    if (!filterSort.isDefault) {
//                        TextButton(onClick = onClearFilterSort) { Text("Clear filter") }
//                    }
//                    TextButton(onClick = onQueryQs) { Text("Query") }
//                },
//            )
//        },
        floatingActionButton = {
            // Hand-rolled FAB: Material3's FloatingActionButton wraps a clickable Surface
            // whose internal detectTapAndPress consumes the pointer-down event, which
            // prevents our outer detectTapGestures from ever firing onLongPress. Using a
            // non-clickable Surface keeps our pointerInput as the sole gesture handler.
            Surface(
                modifier = Modifier
                    .size(52.dp)
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
                shape = RoundedCornerShape(10.dp),
                color = Color(0XFFD85A00),
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 6.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(Res.drawable.wave),
                        contentDescription = "Hold to record voice prompt to add Qs or edit filter and sort.",
                        tint = Color(0xFFFFFFFF),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        bottomBar = {
            QBottomBar(
                selectedView = viewMode,
                onSelectView = { viewMode = it },
                onFilterSort = { filterSortSheetOpen = true },
                onAdd = {
                    sheetRequest = SheetRequest(
                        initial = QDraft(),
                        onSave = { draft ->
                            scope.launch {
                                repository.create(
                                    text = draft.text,
                                    tags = draft.tags,
                                    alarms = draft.alarms,
                                    priority = draft.priority,
                                    completed = draft.completed,
                                    voiceNotePath = draft.voiceNotePath,
                                )
                                sheetRequest = null
                            }
                        },
                    )
                },
                onOverflow = {},
//                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        },
    ) { padding ->
        when (viewMode) {
            ViewMode.List -> if (qs.isEmpty()) {
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
                // Only consume the top inset so the list can extend behind the bottom
                // bar; the bar's height is folded into contentPadding instead, which
                // lets a translucent bar reveal scrolling Qs underneath while still
                // allowing the last card to scroll above the bar.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 12.dp,
                    bottom = padding.calculateBottomPadding() + 84.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(qs, key = { it.id.toString() }) { q ->
                    // Left-swipe (EndToStart) reveals a red trash background and opens
                    // the delete confirmation dialog. confirmValueChange returns false
                    // so the swipe never actually settles to dismissed — the card
                    // springs back to its resting position while the dialog decides
                    // the actual fate. Right-swipe is disabled to avoid an
                    // unmarked-affordance gesture.
                    val swipeState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                pendingDelete = q
                            }
                            false
                        },
                    )
                    SwipeToDismissBox(
                        state = swipeState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFC02A1A))
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.trash),
                                    contentDescription = "Delete Q",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        },
                    ) {
                        QCard(
                            q = q,
                            checked = q.completed,
                            playerProvider = playerProvider,
                            onCheckedChange = { checked ->
                                scope.launch { repository.update(q.id, completed = checked) }
                            },
                            onClick = { sheetRequest = makeEditRequest(q) },
                        )
                    }
                }
            }
            }

            ViewMode.Day -> QDayView(
                qs = qs,
                date = dayDate,
                onSelectDate = { dayDate = it },
                onTapQ = { q -> sheetRequest = makeEditRequest(q) },
                onToggleCompleted = { q, checked ->
                    scope.launch { repository.update(q.id, completed = checked) }
                },
                onCreateAtHour = { at ->
                    sheetRequest = SheetRequest(
                        initial = QDraft(alarms = listOf(Alarm.OneOffTime(at = at))),
                        onSave = { draft ->
                            scope.launch {
                                repository.create(
                                    text = draft.text,
                                    tags = draft.tags,
                                    alarms = draft.alarms,
                                    priority = draft.priority,
                                    completed = draft.completed,
                                    voiceNotePath = draft.voiceNotePath,
                                )
                                sheetRequest = null
                            }
                        },
                    )
                },
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = padding.calculateBottomPadding() + 84.dp,
                ),
            )

            ViewMode.Week, ViewMode.Month -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("${viewMode.name} view — coming soon")
            }
        }
    }

    QVoicePromptDialog(state = voiceState, onDismiss = vm::dismissDialog)

    // Tags universe is derived from the unfiltered Q list so chip clouds (filter-sort,
    // details tag builder) don't collapse to just what's currently visible.
    val availableTags = remember(all) {
        all.flatMap { it.tags }.distinct().sorted()
    }

    sheetRequest?.let { request ->
        QDetailsBottomSheet(
            initial = request.initial,
            recorderProvider = recorderProvider,
            playerProvider = playerProvider,
            onSave = request.onSave,
            onDismiss = {
                sheetRequest = null
                if (request.fromVoiceStream) vm.clearStreamingDraft()
            },
            onDelete = request.onDelete,
            availableTags = availableTags,
            streamingDraft = if (request.fromVoiceStream) streamingDraft else null,
            streaming = request.fromVoiceStream && streamingActive,
        )
    }

    if (filterSortSheetOpen) {
        QFilterSortBottomSheet(
            current = filterSort,
            availableTags = availableTags,
            onApply = { spec ->
                onFilterSortApplied(spec)
                filterSortSheetOpen = false
            },
            onDismiss = { filterSortSheetOpen = false },
        )
    }

    pendingDelete?.let { q ->
        // "Voice note" stands in for an empty title in the dialog body, mirroring
        // QCard's own fallback so the user sees the same label they tapped.
        val label = if (q.text.isBlank() && q.voiceNotePath != null) "Voice note" else q.text
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Q?") },
            text = { Text("Delete “$label”? This can’t be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = q
                    pendingDelete = null
                    // Dismiss the sheet too, if it's open (sheet-trash flow). No-op
                    // for the swipe flow where the sheet was never open.
                    sheetRequest = null
                    scope.launch { repository.delete(target.id) }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Local holder pairing the draft to seed the sheet with, the action to run on Save, and
 * (in the edit flow only) the action to run on Delete. The card-tap path captures the
 * source Q's id; the `+` path doesn't — each call site builds its own [onSave]. The
 * `+` path leaves [onDelete] null so the trash affordance is hidden when there's no Q
 * to delete yet.
 *
 * [fromVoiceStream] marks the sheet as live-following the voice flow; the screen
 * subscribes to the VM's streaming-draft and lock-state flows and feeds them into
 * the sheet as overrides.
 */
private data class SheetRequest(
    val initial: QDraft,
    val onSave: (QDraft) -> Unit,
    val onDelete: (() -> Unit)? = null,
    val fromVoiceStream: Boolean = false,
)
