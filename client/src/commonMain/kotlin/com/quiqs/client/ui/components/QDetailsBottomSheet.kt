package com.quiqs.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
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
import quiqsai.client.generated.resources.plus
import quiqsai.client.generated.resources.speaker
import quiqsai.client.generated.resources.stop
import quiqsai.client.generated.resources.x
import kotlin.time.Instant


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
 *
 * Pill `×` glyphs and the action-bar icons are temporary placeholders rendered with
 * `Res.drawable.plus`; both will be swapped for real assets once they land.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QDetailsBottomSheet(
    initial: QDraft,
    recorderProvider: RecorderProvider,
    playerProvider: PlayerProvider,
    onSave: (QDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
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
) {
    // Re-mounting the sheet (dismiss → reopen) gives us fresh state, so plain `remember`
    // is fine — no need to key on `initial`.
    var draftText by remember { mutableStateOf(initial.text) }
    var draftVoiceNotePath by remember { mutableStateOf(initial.voiceNotePath) }
    var draftPriority by remember { mutableStateOf(initial.priority) }
    // Single picker anchored to the section-4 exclamation icon. The section-3
    // priority pill opens the same menu (so the popup appears above the icon),
    // which keeps the entry points consistent and avoids a second anchor.
    var priorityMenuOpen by remember { mutableStateOf(false) }
    val onSelectPriority: (Priority) -> Unit = { picked ->
        draftPriority = picked
        priorityMenuOpen = false
    }

    Box(modifier = Modifier.fillMaxWidth()) {
    Column(
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
        val showSection3 = initial.alarms.isNotEmpty() ||
                priorityTone != null ||
                initial.tags.isNotEmpty()
        if (showSection3) {
            SheetSection(shape = RoundedCornerShape(4.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    initial.alarms.firstOrNull()?.let { alarm ->
                        AlarmPill(alarm = alarm, onDismiss = {})
                    }
                    val hasPills = priorityTone != null || initial.tags.isNotEmpty()
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
                            initial.tags.forEach { tag ->
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
                IconButton(onClick = {}) {
                    Icon(
                        painter = painterResource(Res.drawable.plus),
                        contentDescription = null,
                        tint = Color(0xFFEDEDED),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = {}) {
                    Icon(
                        painter = painterResource(Res.drawable.clock),
                        contentDescription = null,
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
                val saveEnabled = draftText.isNotBlank() || draftVoiceNotePath != null
                TextButton(
                    onClick = {
                        onSave(
                            initial.copy(
                                text = draftText.trim(),
                                voiceNotePath = draftVoiceNotePath,
                                priority = draftPriority,
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
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20))
            .background(Color(0xFF161616))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QAlarmLabel(alarm = alarm, color = Color.White)
        DismissIcon(tint = Color(0xFFB8B8B8), onClick = onDismiss)
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
