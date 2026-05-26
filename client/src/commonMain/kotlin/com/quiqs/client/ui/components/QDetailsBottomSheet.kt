package com.quiqs.client.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.quiqs.client.LocalThemeIsDark
import com.quiqs.client.QuiqsColorScheme
import com.quiqs.client.QuiqsDarkColorScheme
import com.quiqs.client.audio.AudioPlayer
import com.quiqs.client.audio.AudioRecorder
import com.quiqs.client.audio.NoPlayer
import com.quiqs.client.audio.NoRecorder
import com.quiqs.client.audio.PlayerProvider
import com.quiqs.client.audio.PlayerStatus
import com.quiqs.client.audio.RecorderProvider
import com.quiqs.client.audio.RecorderStatus
import com.quiqs.shared.Alarm
import com.quiqs.shared.Priority
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import quiqsai.client.generated.resources.Res
import quiqsai.client.generated.resources.clock
import quiqsai.client.generated.resources.exclamation
import quiqsai.client.generated.resources.label
import quiqsai.client.generated.resources.mic
import quiqsai.client.generated.resources.mic_recording
import quiqsai.client.generated.resources.speaker
import quiqsai.client.generated.resources.stop
import quiqsai.client.generated.resources.trash
import quiqsai.client.generated.resources.x
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime


const val SHEET_SECTION_HORIZONTAL_PADDING = 22

/**
 * Editable fields the sheet collects. Bundled into one struct so adding new fields
 * (right now alarms / priority / tags pass through unchanged; future entry points can
 * mutate them) only ripples through one signature instead of every `onSave` lambda
 * along the way. Convert to/from [Q] at the call site.
 */
data class QDraft(
    val text: String = "",
    val voiceNotePath: String? = null,
    val alarms: List<Alarm> = emptyList(),
    val priority: Priority = Priority.MEDIUM,
    val tags: List<String> = emptyList(),
    val completed: Boolean = false,
)

/**
 * Bottom sheet for editing a Q — used both when the user taps an existing Q (the draft
 * is seeded from that Q's fields) and when they tap the bottom bar's `+` (the draft
 * starts empty). Field changes live inside the sheet until the user taps Save, which
 * fires [onSave] with the collected draft; dismissing via scrim tap or back press
 * discards everything.
 *
 * The sheet is visually composed of four independent rounded surfaces separated by 4 dp
 * gaps, so its `containerColor` is transparent and the gaps fall through to the dimmed
 * scrim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QDetailsBottomSheet(
    initial: QDraft,
    recorderProvider: RecorderProvider,
    playerProvider: PlayerProvider,
    onSave: (QDraft) -> Unit,
    onDismiss: () -> Unit,
    // Null when the sheet is opened for a brand-new Q (no Q exists yet to delete);
    // non-null in the edit flow. The caller is expected to dismiss the sheet and
    // route through its own confirmation flow.
    onDelete: (() -> Unit)? = null,
    // Live-streamed draft from the voice flow. When non-null, the sheet's editable
    // fields are overridden by this value each time it changes (LLM produces a new
    // field). When it transitions to null, local state retains the last applied
    // value; subsequent edits don't get overwritten.
    streamingDraft: QDraft? = null,
    // True only while the LLM is still emitting. Independent of [streamingDraft]
    // because we want Save to unlock the moment streaming finishes, even though
    // the draft itself (with voice-note path attached) lingers until the user
    // dismisses or saves.
    streaming: Boolean = false,
) {
    var dismissConfirmOpen by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    // confirmValueChange runs as part of the sheet's animation engine, so a
    // returned `false` vetoes the Hidden transition *before* the sheet starts
    // sliding away. That's the only place that intercepts both scrim taps and
    // back presses without leaving the sheet visually dismissed-but-composed
    // when the user cancels. We capture the live flags via rememberUpdatedState
    // because rememberModalBottomSheetState only reads the lambda once.
    val dirtyRef = rememberUpdatedState(dirty)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
            if (newValue == SheetValue.Hidden && dirtyRef.value) {
                dismissConfirmOpen = true
                false
            } else {
                true
            }
        },
    )
    ModalBottomSheet(
        // confirmValueChange has already handled the dirty case; this only fires
        // when the sheet actually reaches Hidden (i.e., untouched draft).
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        scrimColor = Color(0xE60F0F0F),
        dragHandle = null,
        shape = RectangleShape,
        // Apply only the bottom navigation-bar inset so the sheet sits above the system
        // nav bar — same behavior `Scaffold` gives a `bottomBar`. Top/horizontal insets
        // stay zero because the sheet anchors to the bottom and spans full width.
        contentWindowInsets = { WindowInsets.navigationBars.only(WindowInsetsSides.Bottom) },
    ) {
        QDetailsBottomSheetContent(
            initial = initial,
            recorderProvider = recorderProvider,
            playerProvider = playerProvider,
            onSave = onSave,
            onDelete = onDelete,
            streamingDraft = streamingDraft,
            streaming = streaming,
            onDirtyChange = { dirty = it },
        )
    }
    if (dismissConfirmOpen) {
        AlertDialog(
            onDismissRequest = { dismissConfirmOpen = false },
            title = { Text("Discard changes?") },
            text = { Text("Closing will discard anything you haven't saved.") },
            confirmButton = {
                TextButton(onClick = {
                    dismissConfirmOpen = false
                    onDismiss()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { dismissConfirmOpen = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The sheet's body, factored out so it can be rendered in `@Preview` (the surrounding
 * [ModalBottomSheet] requires a real Window and won't render in tooling).
 *
 * The sheet keeps a local draft seeded from [initial]. Field edits update that draft
 * only; nothing is persisted until the user taps Save (which calls [onSave] with the
 * draft). Dismissing the sheet without saving drops the draft entirely.
 *
 * Alarms / priority / tags currently pass straight through the draft unchanged — they
 * render as read-only pills. As entry points for editing them land, mutate the draft
 * here.
 */
@Composable
private fun QDetailsBottomSheetContent(
    initial: QDraft,
    recorderProvider: RecorderProvider = NoRecorder,
    playerProvider: PlayerProvider = NoPlayer,
    onSave: (QDraft) -> Unit = {},
    onDelete: (() -> Unit)? = null,
    streamingDraft: QDraft? = null,
    streaming: Boolean = false,
    onDirtyChange: (Boolean) -> Unit = {},
) {
    // Re-mounting the sheet (dismiss → reopen) gives us fresh state, so plain `remember`
    // is fine — no need to key on `initial`.
    var draftText by remember { mutableStateOf(initial.text) }
    var draftVoiceNotePath by remember { mutableStateOf(initial.voiceNotePath) }
    var draftAlarms by remember { mutableStateOf(initial.alarms) }
    var draftPriority by remember { mutableStateOf(initial.priority) }
    var draftCompleted by remember { mutableStateOf(initial.completed) }
    // Tags state is local now too — needed so the streaming flow can update the
    // chips after the sheet is composed.
    var draftTags by remember { mutableStateOf(initial.tags) }

    // Baseline that "Undo" reverts to and that the dirty check compares against.
    // Starts at `initial`, but during streaming each LLM snapshot becomes the new
    // baseline — so once the stream ends, Undo brings the user back to what the
    // LLM produced (not the empty pre-stream draft).
    var baseline by remember { mutableStateOf(initial) }

    // While the LLM is streaming a new Q, the VM emits incremental QDraft snapshots.
    // Each snapshot becomes the new local state. When streamingDraft transitions
    // back to null (stream ended), local state retains the last value and the
    // user can edit it before tapping Save.
    LaunchedEffect(streamingDraft) {
        if (streamingDraft != null) {
            draftText = streamingDraft.text
            draftVoiceNotePath = streamingDraft.voiceNotePath
            draftAlarms = streamingDraft.alarms
            draftPriority = streamingDraft.priority
            draftCompleted = streamingDraft.completed
            draftTags = streamingDraft.tags
            baseline = streamingDraft
        }
    }

    val currentDraft = QDraft(
        text = draftText,
        voiceNotePath = draftVoiceNotePath,
        alarms = draftAlarms,
        priority = draftPriority,
        tags = draftTags,
        completed = draftCompleted,
    )
    // Two distinct notions of "dirty":
    //   canUndo — current state differs from the Undo baseline (which floats to
    //     each LLM snapshot during streaming). Gates the Undo button only.
    //   unsaved — current state differs from the original `initial`. Gates Save
    //     and the dismiss-confirmation prompt. The voice-stream flow needs this
    //     split: post-stream the LLM-filled draft equals the baseline (so Undo
    //     is rightly disabled), but it still differs from the empty initial,
    //     so Save must remain enabled to persist the LLM's work.
    val canUndo = currentDraft != baseline
    val unsaved = currentDraft != initial
    LaunchedEffect(unsaved) { onDirtyChange(unsaved) }
    // Drives the sheet's internal nav: the main draft form vs. the alarm builder
    // overlay. Driven by the clock action on the main draft and by tapping an
    // existing alarm pill (which pre-fills the builder).
    var sheetScreen by remember { mutableStateOf<SheetScreen>(SheetScreen.MainDraft) }
    // Single picker anchored to the section-4 exclamation icon. The section-3
    // priority pill opens the same menu (so the popup appears above the icon),
    // which keeps the entry points consistent and avoids a second anchor.
    var priorityMenuOpen by remember { mutableStateOf(false) }
    val onSelectPriority: (Priority) -> Unit = { picked ->
        draftPriority = picked
        priorityMenuOpen = false
    }

    Box(modifier = Modifier.fillMaxWidth()) {
    AnimatedContent(targetState = sheetScreen, label = "sheet-screen") { current ->
        when (current) {
            is SheetScreen.AlarmBuilder -> AlarmBuilder(
                editing = current.editing,
                onCancel = { sheetScreen = SheetScreen.MainDraft },
                onAlarmChosen = { alarm ->
                    draftAlarms = listOf(alarm)
                    sheetScreen = SheetScreen.MainDraft
                },
            )
            SheetScreen.MainDraft -> Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
        // Section 1 — editable title.
        SheetSection(
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = 4.dp,
                bottomEnd = 4.dp,
            ),
        ) {
            TitleEditor(
                text = draftText,
                onTextChange = { draftText = it },
            )
        }

        // Section 2 — voice note. Recording lands on the local draft; persistence
        // happens on Save, so dismissing without saving discards the take. The clear
        // affordance lives at the section corner (overlaying the recorded-state
        // button), not inside the inner content, so it lines up with the screenshot's
        // top-right `×`.
        SheetSection(shape = RoundedCornerShape(4.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                VoiceNoteButton(
                    voiceNotePath = draftVoiceNotePath,
                    recorderProvider = recorderProvider,
                    playerProvider = playerProvider,
                    onSetVoiceNote = { draftVoiceNotePath = it },
                )
                if (draftVoiceNotePath != null) {
                    IconButton(
                        onClick = { draftVoiceNotePath = null },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.x),
                            contentDescription = "Remove voice note",
                            tint = Color(0xFFEDEDED),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        // Section 3 — alarm on row 1, priority + tags on row 2. Priority is mutable
        // via the section-4 exclamation popup; alarms / tags are still read-only.
        // Hidden entirely when the draft has nothing to show — Priority MEDIUM is the
        // "no badge" baseline (matches `QCard`), so it doesn't qualify.
        val priorityTone = when (draftPriority) {
            Priority.HIGH -> QBadgeTone.High
            Priority.LOW -> QBadgeTone.Low
            Priority.MEDIUM -> null
        }
        val showSection3 = draftAlarms.isNotEmpty() ||
                priorityTone != null ||
                draftTags.isNotEmpty()
        if (showSection3) {
            SheetSection(shape = RoundedCornerShape(4.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    draftAlarms.firstOrNull()?.let { alarm ->
                        AlarmPill(
                            alarm = alarm,
                            onClick = { sheetScreen = SheetScreen.AlarmBuilder(editing = alarm) },
                            onDismiss = { draftAlarms = emptyList() },
                        )
                    }
                    val hasPills = priorityTone != null || draftTags.isNotEmpty()
                    if (hasPills) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            priorityTone?.let { tone ->
                                DismissPill(
                                    label = draftPriority.wire,
                                    tone = tone,
                                    onClick = { priorityMenuOpen = true },
                                    onDismiss = { draftPriority = Priority.MEDIUM },
                                )
                            }
                            draftTags.forEach { tag ->
                                DismissPill(label = tag, tone = QBadgeTone.Neutral, onDismiss = {})
                            }
                        }
                    }
                }
            }
        }

        // Section 4 — action bar. The icon buttons remain placeholders pending the
        // alarm/tag entry-point work; Save commits the draft.
        SheetSection(
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 4.dp,
                bottomStart = 4.dp,
                bottomEnd = 4.dp,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp - 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Completion toggle — same circle-with-check visual as QCard's row
                // checkbox. The 40 dp Box matches IconButton's default touch target so
                // the action bar's tap rhythm stays consistent across slots.
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    QCheckbox(
                        checked = draftCompleted,
                        onCheckedChange = { draftCompleted = it },
                    )
                }
                IconButton(onClick = {
                    sheetScreen = SheetScreen.AlarmBuilder(editing = draftAlarms.firstOrNull())
                }) {
                    Icon(
                        painter = painterResource(Res.drawable.clock),
                        contentDescription = "Set alarm",
                        tint = Color(0xFFEDEDED),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Box {
                    IconButton(onClick = { priorityMenuOpen = true }) {
                        Icon(
                            painter = painterResource(Res.drawable.exclamation),
                            contentDescription = "Set priority",
                            tint = Color(0xFFEDEDED),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    // Anchored to the icon button. The menu auto-flips above when there's
                    // no room below — which is the typical case here, since section 4 sits
                    // at the bottom of the sheet.
                    DropdownMenu(
                        expanded = priorityMenuOpen,
                        onDismissRequest = { priorityMenuOpen = false },
                        offset = DpOffset(0.dp, (-8).dp),
                        shape = RoundedCornerShape(12.dp),
                        containerColor = Color(0xFF000000),
                    ) {
                        PriorityMenuContent(
                            selected = draftPriority,
                            onSelect = onSelectPriority,
                        )
                    }
                }
                IconButton(onClick = {}) {
                    Icon(
                        painter = painterResource(Res.drawable.label),
                        contentDescription = null,
                        tint = Color(0xFFEDEDED),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // Reverts every field back to the baseline (the original Q in edit
                // mode, an empty draft in the `+` flow, or the LLM's final snapshot
                // in the voice-stream flow). Disabled when there's nothing to revert
                // and while the LLM is still streaming. Labeled "Undo" when editing
                // an existing Q (onDelete present), "Clear" when creating one.
                val undoEnabled = canUndo && !streaming
                val undoLabel = if (onDelete != null) "Undo" else "Clear"
                TextButton(
                    enabled = undoEnabled,
                    onClick = {
                        draftText = baseline.text
                        draftVoiceNotePath = baseline.voiceNotePath
                        draftAlarms = baseline.alarms
                        draftPriority = baseline.priority
                        draftCompleted = baseline.completed
                        draftTags = baseline.tags
                    },
                ) {
                    Text(
                        text = undoLabel,
                        color = if (undoEnabled) Color(0xFFEDEDED) else Color(0xFF555555),
                    )
                }
                // Only present in the edit flow. Red tint to read as destructive — the
                // caller is responsible for showing the confirmation dialog and
                // dismissing the sheet.
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            painter = painterResource(Res.drawable.trash),
                            contentDescription = "Delete Q",
                            tint = Color(0xFFD85A5A),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                // Save is locked while the LLM is still streaming (would persist a
                // half-filled Q), when there's nothing different from the initial
                // draft (no work to commit), and when the draft has no content the
                // user could find later (blank title and no voice note).
                val saveEnabled = !streaming &&
                        unsaved &&
                        (draftText.isNotBlank() || draftVoiceNotePath != null)
                TextButton(
                    onClick = {
                        onSave(
                            initial.copy(
                                text = draftText.trim(),
                                voiceNotePath = draftVoiceNotePath,
                                alarms = draftAlarms,
                                priority = draftPriority,
                                completed = draftCompleted,
                                tags = draftTags,
                            )
                        )
                    },
                    enabled = saveEnabled,
                ) {
                    Text(
                        text = "Save",
                        color = if (saveEnabled) Color(0xFFEDEDED) else Color(0xFF555555),
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }
            }
        }
    }
        // Scrim drawn over the sheet's content while the priority picker is open so
        // the popup (rendered in its own popup window above this scrim) reads as the
        // foreground layer. The popup's `onDismissRequest` already handles outside-tap
        // dismissal; the scrim's `pointerInput` is a belt-and-braces consumer so taps
        // can't slip through to the title editor or save button underneath.
        if (priorityMenuOpen) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0xCC000000))
                    .pointerInput(Unit) {
                        detectTapGestures { priorityMenuOpen = false }
                    },
            )
        }
    }
}

/**
 * `BasicTextField` wrapper styled to match the View-mode title `Text` so toggling between
 * the two doesn't shift layout. Renders a dim placeholder while the draft is empty.
 */
@Composable
private fun TitleEditor(
    text: String,
    onTextChange: (String) -> Unit,
) {
    val titleStyle = TextStyle(
        color = Color(0xFFEDEDED),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
    )
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        textStyle = titleStyle,
        cursorBrush = SolidColor(Color(0xFFEDEDED)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = SHEET_SECTION_HORIZONTAL_PADDING.dp,
                top = 22.dp,
                end = SHEET_SECTION_HORIZONTAL_PADDING.dp,
                bottom = 18.dp,
            ),
        decorationBox = { innerTextField ->
            if (text.isEmpty()) {
                Text(
                    text = "New Q",
                    color = Color(0xFF555555),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontStyle = FontStyle.Italic,
                )
            }
            innerTextField()
        },
    )
}

@Composable
private fun SheetSection(
    shape: Shape,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = Color(0xFF000000),
    ) {
        content()
    }
}

/**
 * Two-state voice-note button rendered inside section 2.
 *
 * No audio attached: a wide orange button. Long-pressing it asks [recorderProvider]
 * for an [AudioRecorder] (which prompts for the mic permission on first use), starts
 * capture, and on release stops the recorder and forwards the resulting absolute path
 * through [onSetVoiceNote].
 *
 * Audio attached: a white "tap to play voice note" pill alongside a speaker glyph. The
 * clear `×` is overlaid at the section corner by the caller, not by this composable.
 *
 * Playback isn't wired yet — the play pill currently does nothing on tap.
 */
@Composable
private fun VoiceNoteButton(
    voiceNotePath: String?,
    recorderProvider: RecorderProvider,
    playerProvider: PlayerProvider,
    onSetVoiceNote: (String) -> Unit,
) {
    if (voiceNotePath == null) {
        VoiceNoteRecordButton(
            recorderProvider = recorderProvider,
            onRecorded = onSetVoiceNote,
        )
    } else {
        VoiceNotePlayButton(
            voiceNotePath = voiceNotePath,
            playerProvider = playerProvider,
        )
    }
}

@Composable
private fun VoiceNoteRecordButton(
    recorderProvider: RecorderProvider,
    onRecorded: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Track whether the current pointer-down has crossed the long-press threshold so
    // the shared `onPress` handler only stops recording on releases that actually
    // started one. Mirrors the FAB voice-prompt gesture in `QListScreen`.
    var longPressActive by remember { mutableStateOf(false) }
    var activeRecorder by remember { mutableStateOf<AudioRecorder?>(null) }

    // Driven by `activeRecorder` — true while audio is actively being captured. Flips
    // the label/icon to the "recording" treatment so the user can see they're being
    // listened to, then back to the resting state on release.
    val isRecording = activeRecorder != null
    VoiceNoteActionButton(
        icon = if (isRecording) Res.drawable.mic_recording else Res.drawable.mic,
        iconTint = Color.White,
        label = if (isRecording) "recording voice note" else "hold to add voice note",
        labelColor = Color.White,
        containerColor = Color(0xFFD85A00),
        gestureModifier = Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    tryAwaitRelease()
                    if (longPressActive) {
                        longPressActive = false
                        scope.launch {
                            val rec = activeRecorder ?: return@launch
                            activeRecorder = null
                            val path = runCatching { rec.stop() }.getOrNull()
                            if (path != null) onRecorded(path)
                        }
                    }
                },
                onLongPress = {
                    longPressActive = true
                    scope.launch {
                        when (val r = recorderProvider.get()) {
                            is RecorderStatus.Available -> {
                                activeRecorder = r.recorder
                                runCatching { r.recorder.start() }.onFailure {
                                    activeRecorder = null
                                    longPressActive = false
                                }
                            }
                            // Permission denied / unavailable: drop the long
                            // press silently. Surfacing an error here would need
                            // additional UI; the existing FAB flow handles those
                            // states elsewhere.
                            else -> longPressActive = false
                        }
                    }
                },
            )
        },
    )
}

/**
 * "Tap to play" pill backed by [playerProvider]. Tapping starts playback; tapping again
 * stops it. Also stops playback if the sheet is dismissed mid-stream so the audio
 * doesn't continue playing into a void.
 *
 * Routing is the player's concern — the Android impl swaps speaker ↔ earpiece when the
 * proximity sensor reports the phone is at the user's ear.
 */
@Composable
private fun VoiceNotePlayButton(
    voiceNotePath: String,
    playerProvider: PlayerProvider,
) {
    val scope = rememberCoroutineScope()
    var activePlayer by remember { mutableStateOf<AudioPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            activePlayer?.stop()
            activePlayer = null
        }
    }

    VoiceNoteActionButton(
        icon = if (isPlaying) Res.drawable.stop else Res.drawable.speaker,
        iconTint = Color.Black,
        label = if (isPlaying) "tap to stop playback" else "tap to play voice note",
        labelColor = Color(0xFF111111),
        containerColor = Color.White,
        gestureModifier = Modifier.clickable {
            if (isPlaying) {
                activePlayer?.stop()
                activePlayer = null
                isPlaying = false
            } else {
                scope.launch {
                    when (val s = playerProvider.get()) {
                        is PlayerStatus.Available -> {
                            activePlayer = s.player
                            isPlaying = true
                            s.player.play(voiceNotePath, onComplete = {
                                if (activePlayer === s.player) {
                                    activePlayer = null
                                    isPlaying = false
                                }
                            })
                        }
                        // Player unavailable: drop the tap silently. Surfacing an error
                        // here would need additional UI; the stub provider is only used
                        // on platforms without playback wiring (iOS / desktop).
                        is PlayerStatus.Unavailable -> Unit
                    }
                }
            }
        },
    )
}

/**
 * Shared CTA button used by both the "hold to add" (orange, long-press) and "tap to
 * play" (white, click) states of section 2. The two only differ in palette, glyph,
 * label, and gesture, so the gesture is exposed as a [Modifier] slot ([gestureModifier])
 * the caller layers onto the inner [Surface]. Outer section padding and inner row
 * spacing/typography are shared so both states stay visually aligned.
 */
@Composable
private fun VoiceNoteActionButton(
    icon: DrawableResource,
    iconTint: Color = Color.Black,
    label: String,
    labelColor: Color,
    containerColor: Color,
    gestureModifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = gestureModifier,
            shape = RoundedCornerShape(10.dp),
            color = containerColor,
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 22.dp, vertical = 14.dp)
                    .widthIn(100.dp, 350.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = label,
                    color = labelColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun AlarmPill(
    alarm: Alarm,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isFired = alarm is Alarm.OneOffTime && alarm.firedAt != null
    val background = if (isFired) Color(0xFF0E0E0E) else Color(0xFF161616)
    val textColor = if (isFired) Color(0xFF7A7A7A) else Color.White
    val dismissTint = if (isFired) Color(0xFF5C5C5C) else Color(0xFFB8B8B8)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20))
            .background(background)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QAlarmLabel(alarm = alarm, color = textColor)
        DismissIcon(tint = dismissTint, onClick = onDismiss)
    }
}

@Composable
private fun DismissPill(
    label: String,
    tone: QBadgeTone,
    onClick: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val (bg, fg) = pillColors(tone)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .widthIn(min = 45.dp, max = 80.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        QText(text = label, role = QTextRole.Badge, color = fg)
        Spacer(modifier = Modifier.width(10.dp))
        // Inner clickable on the icon takes priority over the row's clickable, so
        // taps on the × clear the pill instead of opening the picker.
        DismissIcon(onClick = onDismiss)
    }
}

/**
 * The three-chip selector shared by both priority dropdowns — the section-4
 * exclamation icon and the section-3 priority pill. Factored so the two
 * [DropdownMenu] anchors stay visually identical.
 */
@Composable
private fun PriorityMenuContent(
    selected: Priority,
    onSelect: (Priority) -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PriorityChip(
            label = Priority.LOW.wire,
            tone = QBadgeTone.Low,
            selected = selected == Priority.LOW,
            onClick = { onSelect(Priority.LOW) },
        )
        PriorityChip(
            label = Priority.MEDIUM.wire,
            tone = QBadgeTone.Neutral,
            selected = selected == Priority.MEDIUM,
            onClick = { onSelect(Priority.MEDIUM) },
        )
        PriorityChip(
            label = Priority.HIGH.wire,
            tone = QBadgeTone.High,
            selected = selected == Priority.HIGH,
            onClick = { onSelect(Priority.HIGH) },
        )
    }
}

/**
 * Tappable selector chip used inside the priority popup. Same palette as the
 * section-3 [DismissPill] (so the chip previews what its selection will look like)
 * but without the trailing `×` and with a 1 dp border on the currently-selected one.
 */
@Composable
private fun PriorityChip(
    label: String,
    tone: QBadgeTone,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val (bg, fg) = pillColors(tone)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20))
            .background(bg)
            .then(
                if (selected) Modifier.border(1.dp, fg, RoundedCornerShape(20))
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QText(text = label, role = QTextRole.Badge, color = fg)
    }
}

@Composable
private fun DismissIcon(tint: Color = Color.White, onClick: () -> Unit) {
    Icon(
        painter = painterResource(Res.drawable.x),
        contentDescription = "Remove",
        tint = tint,
        modifier = Modifier
            .size(8.dp)
            .clickable { onClick() },
    )
}

private data class PillColors(val bg: Color, val fg: Color)

private fun pillColors(tone: QBadgeTone): PillColors = when (tone) {
    QBadgeTone.Neutral -> PillColors(bg = Color(0xFF161616), fg = Color(0xFF666666))
    QBadgeTone.High -> PillColors(bg = Color(0xFF200C0C), fg = Color(0xFFA34949))
    QBadgeTone.Low -> PillColors(bg = Color(0xFFF1F4F8), fg = Color(0xFF8893A1))
}

/**
 * Internal-navigation state for the bottom sheet. The sheet stays a single
 * [ModalBottomSheet]; tapping the clock action or an existing alarm pill swaps
 * the body to the alarm builder via [AnimatedContent]. Each [AlarmBuilder] entry
 * carries the alarm being edited (or `null` for a fresh add), so the future
 * forms can pre-fill themselves.
 */
private sealed class SheetScreen {
    data object MainDraft : SheetScreen()
    data class AlarmBuilder(val editing: Alarm?) : SheetScreen()
}

private enum class AlarmBuilderStep {
    PickType, FormOneTime, FormInterval,
}

/**
 * Multi-step alarm builder rendered as a replacement for the main draft body.
 * Step 1 is the type picker; each option transitions to its own form step
 * (placeholders for now). [onCancel] returns control to the main draft;
 * [onAlarmChosen] is reserved for the forms — currently unused.
 */
@Composable
private fun AlarmBuilder(
    editing: Alarm?,
    onCancel: () -> Unit,
    onAlarmChosen: (Alarm) -> Unit,
) {
    var step by remember { mutableStateOf(AlarmBuilderStep.PickType) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AnimatedContent(targetState = step, label = "alarm-builder-step") { current ->
            // Multi-section forms can overflow a short modal sheet — wrap the inner
            // column in a vertical scroll so the Done footer always stays reachable.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (current) {
                    AlarmBuilderStep.PickType -> AlarmTypePicker(
                        editing = editing,
                        onBack = onCancel,
                        onPickOneTime = { step = AlarmBuilderStep.FormOneTime },
                        onPickInterval = { step = AlarmBuilderStep.FormInterval },
                    )
                    AlarmBuilderStep.FormOneTime -> OneTimeForm(
                        editing = editing,
                        onBack = { step = AlarmBuilderStep.PickType },
                        onConfirm = onAlarmChosen,
                    )
                    AlarmBuilderStep.FormInterval -> IntervalForm(
                        editing = editing,
                        onBack = { step = AlarmBuilderStep.PickType },
                        onConfirm = onAlarmChosen,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlarmTypePicker(
    editing: Alarm?,
    onBack: () -> Unit,
    onPickOneTime: () -> Unit,
    onPickInterval: () -> Unit,
) {
    SheetSection(
        shape = RoundedCornerShape(
            topStart = 14.dp,
            topEnd = 14.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp,
        ),
    ) {
        BuilderHeader(
            title = if (editing == null) "Add alarm" else "Edit alarm",
            onBack = onBack,
        )
    }
    SheetSection(
        shape = RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = 14.dp,
            bottomEnd = 14.dp,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BuilderOptionRow(
                title = "One-time",
                description = "Fires once at a specific date and time.",
                onClick = onPickOneTime,
            )
            BuilderOptionRow(
                title = "Interval",
                description = "Repeat every N minutes, hours, days, weeks, months, or years.",
                onClick = onPickInterval,
            )
        }
    }
}

@Composable
private fun BuilderHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp - 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(Res.drawable.x),
                contentDescription = "Back",
                tint = Color(0xFFEDEDED),
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = title,
            color = Color(0xFFEDEDED),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BuilderOptionRow(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            color = Color(0xFFEDEDED),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = description,
            color = Color(0xFFB8B8B8),
            fontSize = 12.sp,
        )
    }
}

/**
 * Form for [Alarm.OneOffTime]. Two tappable rows — Date and Time — each open a
 * Material 3 picker dialog over the bottom sheet. Pre-fills from [editing] if it's
 * a one-off; otherwise seeds with "next round hour from now". Done builds the
 * Instant in the device's time zone and hands it back via [onConfirm].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OneTimeForm(
    editing: Alarm?,
    onBack: () -> Unit,
    onConfirm: (Alarm.OneOffTime) -> Unit,
) {
    val tz = TimeZone.currentSystemDefault()
    val seed = (editing as? Alarm.OneOffTime)?.at ?: defaultOneTimeStart(tz)
    val seedLocal = seed.toLocalDateTime(tz)

    var date by remember { mutableStateOf(seedLocal.date) }
    var hour by remember { mutableStateOf(seedLocal.hour) }
    var minute by remember { mutableStateOf(seedLocal.minute) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var timePickerOpen by remember { mutableStateOf(false) }

    SheetSection(
        shape = RoundedCornerShape(
            topStart = 14.dp,
            topEnd = 14.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp,
        ),
    ) {
        BuilderHeader(title = "One-time alarm", onBack = onBack)
    }
    SheetSection(shape = RoundedCornerShape(4.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            PickerRow(label = "Date", value = formatDateLabel(date, tz), onClick = { datePickerOpen = true })
            PickerRow(label = "Time", value = formatTimeLabel(hour, minute), onClick = { timePickerOpen = true })
        }
    }
    SheetSection(
        shape = RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = 14.dp,
            bottomEnd = 14.dp,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp - 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val instant = date.atTime(hour, minute).toInstant(tz)
                onConfirm(Alarm.OneOffTime(at = instant))
            }) {
                Text(
                    text = "Done",
                    color = Color(0xFFEDEDED),
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }

    if (datePickerOpen) {
        DatePickerOverlay(
            initial = date,
            onDismiss = { datePickerOpen = false },
            onConfirm = { picked ->
                date = picked
                datePickerOpen = false
            },
        )
    }

    if (timePickerOpen) {
        TimePickerOverlay(
            initialHour = hour,
            initialMinute = minute,
            onDismiss = { timePickerOpen = false },
            onConfirm = { h, m ->
                hour = h
                minute = m
                timePickerOpen = false
            },
        )
    }
}

@Composable
private fun PickerRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color(0xFFB8B8B8),
            fontSize = 13.sp,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = Color(0xFFEDEDED),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun defaultOneTimeStart(tz: TimeZone): Instant {
    val now = Clock.System.now()
    val local = now.toLocalDateTime(tz)
    return local.date.atTime(local.hour, 0).toInstant(tz) + 1.hours
}

private fun formatDateLabel(date: LocalDate, tz: TimeZone): String {
    val today = Clock.System.now().toLocalDateTime(tz).date
    return when (val days = today.daysUntil(date)) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> {
            val withinYear = days in -180..180
            if (withinYear && date.year == today.year) {
                "${weekdayShort(date.dayOfWeek)}, ${monthShort(date.month)} ${date.day}"
            } else {
                "${weekdayShort(date.dayOfWeek)}, ${monthShort(date.month)} ${date.day}, ${date.year}"
            }
        }
    }
}

private fun formatTimeLabel(hour: Int, minute: Int): String {
    val h12 = ((hour + 11) % 12) + 1
    val mm = minute.toString().padStart(2, '0')
    val suffix = if (hour < 12) "AM" else "PM"
    return "${h12.toString().padStart(2, '0')}:$mm $suffix"
}

private fun weekdayShort(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "Mon"
    DayOfWeek.TUESDAY -> "Tue"
    DayOfWeek.WEDNESDAY -> "Wed"
    DayOfWeek.THURSDAY -> "Thu"
    DayOfWeek.FRIDAY -> "Fri"
    DayOfWeek.SATURDAY -> "Sat"
    DayOfWeek.SUNDAY -> "Sun"
}

private fun monthShort(month: Month): String = when (month) {
    Month.JANUARY -> "Jan"
    Month.FEBRUARY -> "Feb"
    Month.MARCH -> "Mar"
    Month.APRIL -> "Apr"
    Month.MAY -> "May"
    Month.JUNE -> "Jun"
    Month.JULY -> "Jul"
    Month.AUGUST -> "Aug"
    Month.SEPTEMBER -> "Sep"
    Month.OCTOBER -> "Oct"
    Month.NOVEMBER -> "Nov"
    Month.DECEMBER -> "Dec"
}

/**
 * Form for a recurring alarm: fire every N units (minutes, hours, days, weeks, months,
 * or years), optionally restricted to specific weekdays, optionally bounded by an end
 * date or fire count. Compiles to
 * `FREQ=MINUTELY|HOURLY|DAILY|WEEKLY|MONTHLY|YEARLY;INTERVAL=N;BYDAY=…` with
 * [Alarm.RecurringTime.dtStart] anchoring the first occurrence — subsequent firings
 * step `INTERVAL` units from there. For MONTHLY/YEARLY without a BYDAY restriction,
 * the day-of-month / month+day pattern comes from `dtStart` per RFC 5545 defaults.
 *
 * Pre-fills from [editing] when it is a [Alarm.RecurringTime]; falls back to defaults
 * when [editing] is a different alarm shape (user switching type).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalForm(
    editing: Alarm?,
    onBack: () -> Unit,
    onConfirm: (Alarm.RecurringTime) -> Unit,
) {
    val tz = TimeZone.currentSystemDefault()
    val seed = (editing as? Alarm.RecurringTime)?.let { parseIntervalAlarm(it, tz) }
    val seedStartLocal = (seed?.dtStart ?: defaultDtStart(tz)).toLocalDateTime(tz)
    var unit by remember { mutableStateOf(seed?.unit ?: IntervalUnit.MINUTES) }
    var valueText by remember { mutableStateOf((seed?.value ?: 30).toString()) }
    val valueInt = valueText.toIntOrNull() ?: 0
    var weekdays by remember {
        mutableStateOf<Set<DayOfWeek>>(seed?.weekdays ?: WEEKDAYS_ORDERED.toSet())
    }
    var dtStartDate by remember { mutableStateOf(seedStartLocal.date) }
    var dtStartHour by remember { mutableStateOf(seedStartLocal.hour) }
    var dtStartMinute by remember { mutableStateOf(seedStartLocal.minute) }
    var end by remember { mutableStateOf<EndChoice>(seed?.end ?: EndChoice.Never) }
    // Monthly-pattern state, only surfaced in the UI when unit is MONTHS. Defaults are
    // derived from dtStart so flipping to months feels like a sensible continuation
    // rather than a fresh blank.
    var monthlyMode by remember { mutableStateOf(seed?.monthlyMode ?: MonthlyMode.DAY_OF_MONTH) }
    var monthlyDay by remember { mutableStateOf(seed?.monthlyDay ?: seedStartLocal.date.day) }
    var monthlyOrdinal by remember { mutableStateOf(seed?.monthlyOrdinal ?: NthOrdinal.FIRST) }
    var monthlyWeekday by remember { mutableStateOf(seed?.monthlyWeekday ?: seedStartLocal.date.dayOfWeek) }
    // Between-hours window for MINUTELY/HOURLY. Defaults to (0, 0) — both pickers
    // show "12:00 AM" and the rule emits no BYHOUR clause. Picking a non-trivial
    // window (start < end) enables the filter.
    var betweenStartHour by remember { mutableStateOf(seed?.betweenStartHour ?: 0) }
    var betweenEndHour by remember { mutableStateOf(seed?.betweenEndHour ?: 0) }

    var dtStartDatePickerOpen by remember { mutableStateOf(false) }
    var dtStartTimePickerOpen by remember { mutableStateOf(false) }
    var untilDatePickerOpen by remember { mutableStateOf(false) }
    var betweenStartPickerOpen by remember { mutableStateOf(false) }
    var betweenEndPickerOpen by remember { mutableStateOf(false) }

    // ON DAYS OF WEEK only surfaces for sub-day units and weekly, so weekdays only
    // need to be non-empty in those cases. Other units don't render the section,
    // so the user can't strand themselves with an empty set.
    val weekdaysApply = unit == IntervalUnit.MINUTES ||
        unit == IntervalUnit.HOURS ||
        unit == IntervalUnit.WEEKS
    val canSave = valueInt >= 1 && (!weekdaysApply || weekdays.isNotEmpty())

    // Switching unit resets the count to a sensible default for that unit; switching
    // back to a unit you already had keeps whatever the user had typed.
    val pickUnit: (IntervalUnit) -> Unit = { picked ->
        if (unit != picked) {
            unit = picked
            valueText = if (picked == IntervalUnit.MINUTES) "15" else "1"
        }
    }

    SheetSection(
        shape = RoundedCornerShape(
            topStart = 14.dp,
            topEnd = 14.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp,
        ),
    ) {
        BuilderHeader(title = "Interval alarm", onBack = onBack)
    }
    // EVERY — always shown, holds only the number + unit chips. The monthly pattern
    // now lives in its own section below.
    SheetSection(shape = RoundedCornerShape(4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FormFieldLabel("Every")
            // Reads as a sentence: "Every [15] [minutes ▾]". The unit cell is a
            // dropdown rather than a wrapping row of chips because the chips don't
            // fit on one line at typical phone widths.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NumberField(
                    text = valueText,
                    onTextChange = { valueText = it },
                )
                DropdownPicker(
                    selected = unit,
                    options = IntervalUnit.entries.toList(),
                    labelOf = { it.label(valueInt) },
                    onSelect = { pickUnit(it) },
                )
            }
        }
    }

    // MINUTES / HOURS — ON DAYS OF WEEK + BETWEEN HOURS share one section.
    if (unit == IntervalUnit.MINUTES || unit == IntervalUnit.HOURS) {
        SheetSection(shape = RoundedCornerShape(4.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormFieldLabel("On")
                    WeekdayChips(selected = weekdays) { day ->
                        weekdays = if (day in weekdays) weekdays - day else weekdays + day
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormFieldLabel("Between")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StartingCell(
                            text = formatTimeLabel(betweenStartHour, 0),
                            onClick = { betweenStartPickerOpen = true },
                        )
                        InlineLabel("and")
                        StartingCell(
                            text = formatTimeLabel(betweenEndHour, 0),
                            onClick = { betweenEndPickerOpen = true },
                        )
                    }
                }
            }
        }
    }

    // WEEKS — ON DAYS OF WEEK as its own section.
    if (unit == IntervalUnit.WEEKS) {
        SheetSection(shape = RoundedCornerShape(4.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormFieldLabel("On")
                WeekdayChips(selected = weekdays) { day ->
                    weekdays = if (day in weekdays) weekdays - day else weekdays + day
                }
            }
        }
    }

    // MONTHS — pattern: day-of-month OR Nth weekday.
    if (unit == IntervalUnit.MONTHS) {
        SheetSection(shape = RoundedCornerShape(4.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RadioContentRow(
                    selected = monthlyMode == MonthlyMode.DAY_OF_MONTH,
                    onClick = { monthlyMode = MonthlyMode.DAY_OF_MONTH },
                ) {
                    InlineLabel("Day")
                    DropdownPicker(
                        selected = monthlyDay,
                        options = (1..31).toList(),
                        labelOf = { it.toString() },
                        onSelect = { monthlyDay = it },
                    )
                    InlineLabel("of the month")
                }
                RadioContentRow(
                    selected = monthlyMode == MonthlyMode.NTH_WEEKDAY,
                    onClick = { monthlyMode = MonthlyMode.NTH_WEEKDAY },
                ) {
                    InlineLabel("The")
                    DropdownPicker(
                        selected = monthlyOrdinal,
                        options = NthOrdinal.entries.toList(),
                        labelOf = { it.label },
                        onSelect = { monthlyOrdinal = it },
                    )
                    DropdownPicker(
                        selected = monthlyWeekday,
                        options = WEEKDAYS_ORDERED,
                        labelOf = { it.fullLabel() },
                        onSelect = { monthlyWeekday = it },
                    )
                }
            }
        }
    }

    // STARTING — always shown.
    SheetSection(shape = RoundedCornerShape(4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FormFieldLabel("Starting")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StartingCell(
                    text = formatDateLabel(dtStartDate, tz),
                    modifier = Modifier.weight(1f),
                    onClick = { dtStartDatePickerOpen = true },
                )
                StartingCell(
                    text = formatTimeLabel(dtStartHour, dtStartMinute),
                    onClick = { dtStartTimePickerOpen = true },
                )
            }
        }
    }
    // ENDS — always shown.
    SheetSection(shape = RoundedCornerShape(4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 18.dp),
        ) {
            EndsBlock(
                end = end,
                onChange = { end = it },
                onPickDate = { untilDatePickerOpen = true },
                tz = tz,
            )
        }
    }
    SheetSection(
        shape = RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = 14.dp,
            bottomEnd = 14.dp,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp - 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                enabled = canSave,
                onClick = {
                    val dtStart = dtStartDate.atTime(dtStartHour, dtStartMinute).toInstant(tz)
                    val rule = buildIntervalRule(
                        value = valueInt,
                        unit = unit,
                        weekdays = weekdays,
                        monthlyMode = monthlyMode,
                        monthlyDay = monthlyDay,
                        monthlyOrdinal = monthlyOrdinal,
                        monthlyWeekday = monthlyWeekday,
                        betweenStartHour = betweenStartHour,
                        betweenEndHour = betweenEndHour,
                    )
                    val until = (end as? EndChoice.OnDate)?.date
                        ?.atTime(23, 59)
                        ?.toInstant(tz)
                    val count = (end as? EndChoice.AfterCount)?.count
                    onConfirm(
                        Alarm.RecurringTime(
                            rule = rule,
                            dtStart = dtStart,
                            until = until,
                            count = count,
                        )
                    )
                },
            ) {
                Text(
                    text = "Done",
                    color = if (canSave) Color(0xFFEDEDED) else Color(0xFF555555),
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }

    if (dtStartDatePickerOpen) {
        DatePickerOverlay(
            initial = dtStartDate,
            onDismiss = { dtStartDatePickerOpen = false },
            onConfirm = { picked ->
                dtStartDate = picked
                dtStartDatePickerOpen = false
            },
        )
    }
    if (dtStartTimePickerOpen) {
        TimePickerOverlay(
            initialHour = dtStartHour,
            initialMinute = dtStartMinute,
            onDismiss = { dtStartTimePickerOpen = false },
            onConfirm = { h, m ->
                dtStartHour = h
                dtStartMinute = m
                dtStartTimePickerOpen = false
            },
        )
    }
    if (untilDatePickerOpen) {
        val initialDate = (end as? EndChoice.OnDate)?.date ?: defaultUntilDate(tz)
        DatePickerOverlay(
            initial = initialDate,
            onDismiss = { untilDatePickerOpen = false },
            onConfirm = { picked ->
                end = EndChoice.OnDate(picked)
                untilDatePickerOpen = false
            },
        )
    }
    // Between-hours pickers reuse the regular TimePickerOverlay; we save just the
    // hour and drop the minute since BYHOUR is hour-grained. Picking 9:45 stores 9.
    if (betweenStartPickerOpen) {
        TimePickerOverlay(
            initialHour = betweenStartHour,
            initialMinute = 0,
            onDismiss = { betweenStartPickerOpen = false },
            onConfirm = { h, _ ->
                betweenStartHour = h
                betweenStartPickerOpen = false
            },
        )
    }
    if (betweenEndPickerOpen) {
        TimePickerOverlay(
            initialHour = betweenEndHour,
            initialMinute = 0,
            onDismiss = { betweenEndPickerOpen = false },
            onConfirm = { h, _ ->
                betweenEndHour = h
                betweenEndPickerOpen = false
            },
        )
    }
}

private enum class IntervalUnit { MINUTES, HOURS, DAYS, WEEKS, MONTHS, YEARS }

private fun IntervalUnit.label(count: Int): String {
    val singular = when (this) {
        IntervalUnit.MINUTES -> "minute"
        IntervalUnit.HOURS -> "hour"
        IntervalUnit.DAYS -> "day"
        IntervalUnit.WEEKS -> "week"
        IntervalUnit.MONTHS -> "month"
        IntervalUnit.YEARS -> "year"
    }
    return if (count == 1) singular else singular + "s"
}

private enum class MonthlyMode { DAY_OF_MONTH, NTH_WEEKDAY }

/**
 * Ordinal prefix used for monthly Nth-weekday rules. The [rfcValue] is the integer
 * that goes in front of the day code in a BYDAY entry — e.g. `2SU` = second Sunday,
 * `-1FR` = last Friday — per RFC 5545 §3.3.10.
 */
private enum class NthOrdinal(val rfcValue: Int, val label: String) {
    FIRST(1, "first"),
    SECOND(2, "second"),
    THIRD(3, "third"),
    FOURTH(4, "fourth"),
    LAST(-1, "last"),
}

private sealed class EndChoice {
    data object Never : EndChoice()
    data class OnDate(val date: LocalDate) : EndChoice()
    data class AfterCount(val count: Int) : EndChoice()
}

private val WEEKDAYS_ORDERED = listOf(
    DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
)
private val WEEKDAY_CHIP_LABELS = listOf("S", "M", "T", "W", "T", "F", "S")

@Composable
private fun NumberField(
    text: String,
    onTextChange: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF222222))
            .widthIn(min = 20.dp, max = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { input ->
                // Defensive: numeric keyboard can still leak non-digits on some platforms.
                onTextChange(input.filter { it.isDigit() }.take(3))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(
                color = Color(0xFFEDEDED),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(Color(0xFFEDEDED)),
            singleLine = true,
        )
    }
}

@Composable
private fun ToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) Color(0xFFEDEDED) else Color(0xFF222222)
    val fg = if (selected) Color(0xFF111111) else Color(0xFFB8B8B8)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun WeekdayChips(
    selected: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WEEKDAYS_ORDERED.forEachIndexed { i, day ->
            ToggleChip(WEEKDAY_CHIP_LABELS[i], day in selected) { onToggle(day) }
        }
    }
}

@Composable
private fun StartingCell(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF161616))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            color = Color(0xFFEDEDED),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EndsBlock(
    end: EndChoice,
    onChange: (EndChoice) -> Unit,
    onPickDate: () -> Unit,
    tz: TimeZone,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FormFieldLabel("Ends")
        RadioRow(
            label = "Never",
            selected = end is EndChoice.Never,
            onClick = { onChange(EndChoice.Never) },
        )
        RadioRow(
            label = "On date",
            selected = end is EndChoice.OnDate,
            onClick = {
                val date = (end as? EndChoice.OnDate)?.date ?: defaultUntilDate(tz)
                onChange(EndChoice.OnDate(date))
            },
            trailing = {
                if (end is EndChoice.OnDate) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF161616))
                            .clickable { onPickDate() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = formatDateLabel(end.date, tz),
                            color = Color(0xFFEDEDED),
                            fontSize = 13.sp,
                        )
                    }
                }
            },
        )
        RadioRow(
            label = "After",
            selected = end is EndChoice.AfterCount,
            onClick = {
                val n = (end as? EndChoice.AfterCount)?.count ?: 10
                onChange(EndChoice.AfterCount(n))
            },
            trailing = {
                if (end is EndChoice.AfterCount) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NumberStepper(
                            value = end.count,
                            onChange = { onChange(EndChoice.AfterCount(it)) },
                            min = 1,
                            max = 999,
                        )
                        Text(
                            text = "times",
                            color = Color(0xFFB8B8B8),
                            fontSize = 13.sp,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(50))
                .then(
                    if (selected) Modifier.background(Color(0xFFEDEDED))
                    else Modifier.border(1.5.dp, Color(0xFF888888), RoundedCornerShape(50))
                ),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = Color(0xFFEDEDED),
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * Radio-row variant with a flexible content slot rather than a single [String] label —
 * used by the monthly pattern rows in [IntervalForm], which embed dropdowns inline
 * with the row label text.
 */
@Composable
private fun RadioContentRow(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(50))
                .then(
                    if (selected) Modifier.background(Color(0xFFEDEDED))
                    else Modifier.border(1.5.dp, Color(0xFF888888), RoundedCornerShape(50))
                ),
        )
        content()
    }
}

@Composable
private fun InlineLabel(text: String) {
    Text(
        text = text,
        color = Color(0xFFEDEDED),
        fontSize = 14.sp,
    )
}

/**
 * Tappable cell that opens a [DropdownMenu] anchored to itself. Used by the monthly
 * pattern rows for day-of-month / ordinal / weekday selection.
 */
@Composable
private fun <T> DropdownPicker(
    selected: T,
    options: List<T>,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF161616))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = labelOf(selected),
                color = Color(0xFFEDEDED),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(8.dp),
            containerColor = Color(0xFF1F1F1F),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = labelOf(option),
                            color = Color(0xFFEDEDED),
                            fontSize = 13.sp,
                        )
                    },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NumberStepper(
    value: Int,
    onChange: (Int) -> Unit,
    min: Int,
    max: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperButton("−") { if (value > min) onChange(value - 1) }
        Text(
            text = value.toString(),
            color = Color(0xFFEDEDED),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 28.dp).padding(horizontal = 6.dp),
        )
        StepperButton("+") { if (value < max) onChange(value + 1) }
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF222222))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color(0xFFEDEDED),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FormFieldLabel(text: String) {
    Text(
        text = text,
        color = Color(0xFFB8B8B8),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerOverlay(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        text = { TimePicker(state = state) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerOverlay(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { ms ->
                    onConfirm(Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date)
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

private data class IntervalFormDefaults(
    val unit: IntervalUnit,
    val value: Int,
    val weekdays: Set<DayOfWeek>,
    val end: EndChoice,
    val dtStart: Instant,
    // Monthly-specific pattern. Always populated (with sensible defaults derived from
    // dtStart for non-MONTHLY rules), so the form can fall back to them if the user
    // later switches the unit to months.
    val monthlyMode: MonthlyMode,
    val monthlyDay: Int,
    val monthlyOrdinal: NthOrdinal,
    val monthlyWeekday: DayOfWeek,
    // Between-hours window for MINUTELY/HOURLY rules. End is exclusive: 9..17 means
    // fires can land in hours 9..16 inclusive. (0, 0) means "no window" (BYHOUR
    // absent in the rule).
    val betweenStartHour: Int,
    val betweenEndHour: Int,
)

/**
 * Inverse of [buildIntervalRule]: extracts the interval-form fields from an existing
 * [Alarm.RecurringTime] so the builder can re-open in the state the user previously
 * picked. Returns `null` only if the rule's `FREQ` is unrecognized, which is the
 * signal to fall back to defaults.
 */
private fun parseIntervalAlarm(alarm: Alarm.RecurringTime, tz: TimeZone): IntervalFormDefaults? {
    val parts = alarm.rule
        .split(';')
        .mapNotNull { piece ->
            val eq = piece.indexOf('=')
            if (eq <= 0) null
            else piece.substring(0, eq).trim().uppercase() to piece.substring(eq + 1).trim()
        }
        .toMap()

    val unit = when (parts["FREQ"]?.uppercase()) {
        "MINUTELY" -> IntervalUnit.MINUTES
        "HOURLY" -> IntervalUnit.HOURS
        "DAILY" -> IntervalUnit.DAYS
        "WEEKLY" -> IntervalUnit.WEEKS
        "MONTHLY" -> IntervalUnit.MONTHS
        "YEARLY" -> IntervalUnit.YEARS
        else -> return null
    }
    val value = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val byDay = parts["BYDAY"]
    val hasOrdinalBYDAY = byDay != null && byDayHasOrdinal(byDay)
    // For MONTHLY+BYDAY with an ordinal prefix the BYDAY is the pattern itself
    // ("first Monday"), not a weekday filter — don't surface it as weekday chips.
    val weekdays = if (unit == IntervalUnit.MONTHS && hasOrdinalBYDAY) {
        WEEKDAYS_ORDERED.toSet()
    } else {
        byDay
            ?.split(',')
            ?.mapNotNull { it.toDayOfWeekIgnoringOrdinal() }
            ?.toSet()
            ?: WEEKDAYS_ORDERED.toSet()
    }
    val dtStartLocal = alarm.dtStart.toLocalDateTime(tz)
    val monthlyMode = if (unit == IntervalUnit.MONTHS && hasOrdinalBYDAY) {
        MonthlyMode.NTH_WEEKDAY
    } else {
        MonthlyMode.DAY_OF_MONTH
    }
    val monthlyDay = (parts["BYMONTHDAY"]?.split(',')?.firstNotNullOfOrNull { it.toIntOrNull() })
        ?.takeIf { it in 1..31 }
        ?: dtStartLocal.date.day
    val nthFromByDay = if (monthlyMode == MonthlyMode.NTH_WEEKDAY) {
        byDay?.split(',')?.firstOrNull()?.trim()?.let { token ->
            val day = token.takeLast(2).toDayOfWeekIgnoringOrdinal()
            val rfc = token.dropLast(2).toIntOrNull()
            if (day != null && rfc != null) {
                NthOrdinal.entries.firstOrNull { it.rfcValue == rfc } to day
            } else null
        }
    } else null
    val monthlyOrdinal = nthFromByDay?.first ?: NthOrdinal.FIRST
    val monthlyWeekday = nthFromByDay?.second ?: dtStartLocal.date.dayOfWeek
    val byHourValues = parts["BYHOUR"]
        ?.split(',')
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.filter { it in 0..23 }
        ?.sorted()
    val (betweenStartHour, betweenEndHour) = if (!byHourValues.isNullOrEmpty()) {
        byHourValues.first() to (byHourValues.last() + 1).coerceAtMost(24)
    } else {
        0 to 0
    }
    val count = alarm.count
    val until = alarm.until
    val end = when {
        count != null -> EndChoice.AfterCount(count)
        until != null -> EndChoice.OnDate(until.toLocalDateTime(tz).date)
        else -> EndChoice.Never
    }
    return IntervalFormDefaults(
        unit = unit,
        value = value,
        weekdays = weekdays,
        end = end,
        dtStart = alarm.dtStart,
        monthlyMode = monthlyMode,
        monthlyDay = monthlyDay,
        monthlyOrdinal = monthlyOrdinal,
        monthlyWeekday = monthlyWeekday,
        betweenStartHour = betweenStartHour,
        betweenEndHour = betweenEndHour,
    )
}

private fun byDayHasOrdinal(byDay: String): Boolean = byDay.split(',').any { token ->
    val trimmed = token.trim()
    trimmed.length > 2 && trimmed.dropLast(2).toIntOrNull() != null
}

private fun String.toDayOfWeekIgnoringOrdinal(): DayOfWeek? {
    val trimmed = trim().uppercase()
    if (trimmed.length < 2) return null
    return when (trimmed.takeLast(2)) {
        "MO" -> DayOfWeek.MONDAY
        "TU" -> DayOfWeek.TUESDAY
        "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY
        "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY
        else -> null
    }
}

private fun buildIntervalRule(
    value: Int,
    unit: IntervalUnit,
    weekdays: Set<DayOfWeek>,
    monthlyMode: MonthlyMode,
    monthlyDay: Int,
    monthlyOrdinal: NthOrdinal,
    monthlyWeekday: DayOfWeek,
    betweenStartHour: Int,
    betweenEndHour: Int,
): String {
    val freq = when (unit) {
        IntervalUnit.MINUTES -> "MINUTELY"
        IntervalUnit.HOURS -> "HOURLY"
        IntervalUnit.DAYS -> "DAILY"
        IntervalUnit.WEEKS -> "WEEKLY"
        IntervalUnit.MONTHS -> "MONTHLY"
        IntervalUnit.YEARS -> "YEARLY"
    }
    val byDayApplies = unit == IntervalUnit.MINUTES ||
        unit == IntervalUnit.HOURS ||
        unit == IntervalUnit.WEEKS
    val byHourApplies = unit == IntervalUnit.MINUTES || unit == IntervalUnit.HOURS
    return buildString {
        append("FREQ=$freq;INTERVAL=$value")
        if (unit == IntervalUnit.MONTHS) {
            // Monthly uses its own pattern slot (BYMONTHDAY for "day N", ordinal BYDAY
            // for "the Nth weekday"); other units' BYDAY semantics don't apply.
            when (monthlyMode) {
                MonthlyMode.DAY_OF_MONTH -> append(";BYMONTHDAY=$monthlyDay")
                MonthlyMode.NTH_WEEKDAY -> append(
                    ";BYDAY=${monthlyOrdinal.rfcValue}${monthlyWeekday.rruleCode()}"
                )
            }
        } else if (byDayApplies && weekdays.isNotEmpty() && weekdays.size < 7) {
            val days = weekdays
                .sortedBy { it.isoDayNumber }
                .joinToString(",") { it.rruleCode() }
            append(";BYDAY=$days")
        }
        // BYHOUR is end-exclusive ("between 9 and 17" → hours 9..16). Skipped when
        // the window is trivial (start >= end) or covers the full day.
        if (byHourApplies &&
            betweenStartHour in 0..23 &&
            betweenEndHour in 1..24 &&
            betweenStartHour < betweenEndHour &&
            !(betweenStartHour == 0 && betweenEndHour == 24)
        ) {
            val hours = (betweenStartHour until betweenEndHour).joinToString(",")
            append(";BYHOUR=$hours")
        }
    }
}

private fun DayOfWeek.fullLabel(): String = when (this) {
    DayOfWeek.MONDAY -> "Monday"
    DayOfWeek.TUESDAY -> "Tuesday"
    DayOfWeek.WEDNESDAY -> "Wednesday"
    DayOfWeek.THURSDAY -> "Thursday"
    DayOfWeek.FRIDAY -> "Friday"
    DayOfWeek.SATURDAY -> "Saturday"
    DayOfWeek.SUNDAY -> "Sunday"
}

private fun DayOfWeek.rruleCode(): String = when (this) {
    DayOfWeek.MONDAY -> "MO"
    DayOfWeek.TUESDAY -> "TU"
    DayOfWeek.WEDNESDAY -> "WE"
    DayOfWeek.THURSDAY -> "TH"
    DayOfWeek.FRIDAY -> "FR"
    DayOfWeek.SATURDAY -> "SA"
    DayOfWeek.SUNDAY -> "SU"
}

private fun defaultUntilDate(tz: TimeZone): LocalDate =
    Clock.System.now().toLocalDateTime(tz).date.plus(30, DateTimeUnit.DAY)

private fun defaultDtStart(tz: TimeZone): Instant {
    val nowLocal = Clock.System.now().toLocalDateTime(tz)
    return nowLocal.date.atTime(nowLocal.hour, nowLocal.minute).toInstant(tz)
}

private fun previewSeededDraft(): QDraft {
    val now = Instant.fromEpochMilliseconds(1_746_280_800_000)
    return QDraft(
        text = "Get groceries for the party.",
        voiceNotePath = null,
        alarms = listOf(Alarm.OneOffTime(at = now)),
        priority = Priority.HIGH,
        tags = listOf("work", "personal"),
    )
}

@Preview
@Composable
private fun QDetailsBottomSheetPreview() {
    CompositionLocalProvider(LocalThemeIsDark provides false) {
        val scheme = if (LocalThemeIsDark.current) QuiqsDarkColorScheme else QuiqsColorScheme
        MaterialTheme(colorScheme = scheme) {
            Box(modifier = Modifier.background(color = Color(0xFFF7F4EE))) {
                QDetailsBottomSheetContent(initial = previewSeededDraft())
            }
        }
    }
}

@Preview
@Composable
private fun QDetailsBottomSheetPreviewDark() {
    CompositionLocalProvider(LocalThemeIsDark provides true) {
        val scheme = if (LocalThemeIsDark.current) QuiqsDarkColorScheme else QuiqsColorScheme
        MaterialTheme(colorScheme = scheme) {
            Box(modifier = Modifier.background(color = Color(0xFF0A0A0A))) {
                QDetailsBottomSheetContent(initial = previewSeededDraft())
            }
        }
    }
}

@Preview
@Composable
private fun QDetailsBottomSheetEmptyPreviewDark() {
    CompositionLocalProvider(LocalThemeIsDark provides true) {
        val scheme = if (LocalThemeIsDark.current) QuiqsDarkColorScheme else QuiqsColorScheme
        MaterialTheme(colorScheme = scheme) {
            Box(modifier = Modifier.background(color = Color(0xFF0A0A0A))) {
                QDetailsBottomSheetContent(initial = QDraft().copy(voiceNotePath = "xyz"))
            }
        }
    }
}
