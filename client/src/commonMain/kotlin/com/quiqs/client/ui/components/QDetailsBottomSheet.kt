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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextAlign
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
    var draftAlarms by remember { mutableStateOf(initial.alarms) }
    var draftPriority by remember { mutableStateOf(initial.priority) }
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
                initial.tags.isNotEmpty()
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
                val saveEnabled = draftText.isNotBlank() || draftVoiceNotePath != null
                TextButton(
                    onClick = {
                        onSave(
                            initial.copy(
                                text = draftText.trim(),
                                voiceNotePath = draftVoiceNotePath,
                                alarms = draftAlarms,
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
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20))
            .background(Color(0xFF161616))
            .clickable { onClick() }
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
    PickType, FormOneTime, FormInterval, FormCalendar,
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (current) {
                    AlarmBuilderStep.PickType -> AlarmTypePicker(
                        editing = editing,
                        onBack = onCancel,
                        onPickOneTime = { step = AlarmBuilderStep.FormOneTime },
                        onPickInterval = { step = AlarmBuilderStep.FormInterval },
                        onPickCalendar = { step = AlarmBuilderStep.FormCalendar },
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
                    AlarmBuilderStep.FormCalendar -> ComingSoonForm(
                        title = "Calendar",
                        onBack = { step = AlarmBuilderStep.PickType },
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
    onPickCalendar: () -> Unit,
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
                description = "Repeat every N minutes or hours within a daily window.",
                onClick = onPickInterval,
            )
            BuilderOptionRow(
                title = "Calendar",
                description = "Repeat daily, weekly, monthly, or yearly.",
                onClick = onPickCalendar,
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
        // Material's DatePickerState anchors on UTC midnight, so we seed with that
        // and read back the same way to keep the calendar date stable regardless of
        // the user's local zone.
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
        )
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { ms ->
                        date = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date
                    }
                    datePickerOpen = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { datePickerOpen = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (timePickerOpen) {
        val timePickerState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { timePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    hour = timePickerState.hour
                    minute = timePickerState.minute
                    timePickerOpen = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { timePickerOpen = false }) { Text("Cancel") }
            },
            text = { TimePicker(state = timePickerState) },
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
 * Form for an "interval" recurring alarm: fire every N minutes or hours within a
 * daily hour window, on selected weekdays. Compiles to
 * `FREQ=MINUTELY|HOURLY;INTERVAL=N;BYHOUR=..;BYDAY=..` with [Alarm.RecurringTime.dtStart]
 * set to the first weekday-matching window start at or after "now".
 *
 * Editing an existing recurring alarm is not yet pre-filled — the form opens at its
 * defaults regardless of [editing]. A future pass can parse the rule back into form
 * state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalForm(
    @Suppress("UNUSED_PARAMETER") editing: Alarm?,
    onBack: () -> Unit,
    onConfirm: (Alarm.RecurringTime) -> Unit,
) {
    val tz = TimeZone.currentSystemDefault()
    var unit by remember { mutableStateOf(IntervalUnit.MINUTES) }
    var value by remember { mutableStateOf(30) }
    var windowStartHour by remember { mutableStateOf(9) }
    var windowEndHour by remember { mutableStateOf(21) }
    var weekdays by remember { mutableStateOf<Set<DayOfWeek>>(WEEKDAYS_ORDERED.toSet()) }
    var end by remember { mutableStateOf<EndChoice>(EndChoice.Never) }

    var startHourPickerOpen by remember { mutableStateOf(false) }
    var endHourPickerOpen by remember { mutableStateOf(false) }
    var untilDatePickerOpen by remember { mutableStateOf(false) }

    val valueOptions = if (unit == IntervalUnit.MINUTES) MINUTE_OPTIONS else HOUR_OPTIONS
    val canSave = weekdays.isNotEmpty() && windowEndHour >= windowStartHour && value >= 1

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
    SheetSection(shape = RoundedCornerShape(4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Every
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormFieldLabel("Every")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip("minutes", unit == IntervalUnit.MINUTES) {
                        if (unit != IntervalUnit.MINUTES) {
                            unit = IntervalUnit.MINUTES
                            value = 30
                        }
                    }
                    ToggleChip("hours", unit == IntervalUnit.HOURS) {
                        if (unit != IntervalUnit.HOURS) {
                            unit = IntervalUnit.HOURS
                            value = 1
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    valueOptions.forEach { v ->
                        ToggleChip(v.toString(), value == v) { value = v }
                    }
                }
            }

            // Between
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormFieldLabel("Between")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HourCell(windowStartHour) { startHourPickerOpen = true }
                    Text("and", color = Color(0xFFB8B8B8), fontSize = 13.sp)
                    HourCell(windowEndHour) { endHourPickerOpen = true }
                }
            }

            // On
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormFieldLabel("On")
                WeekdayChips(selected = weekdays) { day ->
                    weekdays = if (day in weekdays) weekdays - day else weekdays + day
                }
            }

            // Ends
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
                    val nowLocal = Clock.System.now().toLocalDateTime(tz)
                    val dtStart = computeIntervalDtStart(nowLocal, weekdays, windowStartHour, tz)
                    val rule = buildIntervalRule(value, unit, windowStartHour, windowEndHour, weekdays)
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

    if (startHourPickerOpen) {
        HourPickerDialog(
            initialHour = windowStartHour,
            onDismiss = { startHourPickerOpen = false },
            onConfirm = { picked ->
                windowStartHour = picked
                startHourPickerOpen = false
            },
        )
    }
    if (endHourPickerOpen) {
        HourPickerDialog(
            initialHour = windowEndHour,
            onDismiss = { endHourPickerOpen = false },
            onConfirm = { picked ->
                windowEndHour = picked
                endHourPickerOpen = false
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
}

private enum class IntervalUnit { MINUTES, HOURS }

private sealed class EndChoice {
    data object Never : EndChoice()
    data class OnDate(val date: LocalDate) : EndChoice()
    data class AfterCount(val count: Int) : EndChoice()
}

private val MINUTE_OPTIONS = listOf(5, 10, 15, 30, 45, 60, 90, 120)
private val HOUR_OPTIONS = listOf(1, 2, 3, 4, 6, 8, 12)
private val WEEKDAYS_ORDERED = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
)
private val WEEKDAY_CHIP_LABELS = listOf("M", "T", "W", "T", "F", "S", "S")

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
private fun HourCell(hour: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF161616))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = formatHourLabel(hour),
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

/**
 * Shared TimePicker-in-AlertDialog used by the interval form's window pickers. We
 * ask the user for an hour:minute but only consume the hour — interval windows are
 * hour-granular by design (BYHOUR filters at hour resolution).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HourPickerDialog(
    initialHour: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = 0,
        is24Hour = false,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour) }) { Text("OK") }
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

/**
 * First firing for an interval rule: the next selected weekday whose [windowStartHour]
 * is still ahead of [nowLocal]. Defaults to "today at windowStart" if today qualifies
 * and we haven't passed the window start; otherwise advances day-by-day.
 */
private fun computeIntervalDtStart(
    nowLocal: LocalDateTime,
    weekdays: Set<DayOfWeek>,
    windowStartHour: Int,
    tz: TimeZone,
): Instant {
    val days = weekdays.ifEmpty { WEEKDAYS_ORDERED.toSet() }
    val windowStartToday = nowLocal.date.atTime(windowStartHour, 0)
    if (nowLocal.dayOfWeek in days && nowLocal < windowStartToday) {
        return windowStartToday.toInstant(tz)
    }
    var d = nowLocal.date.plus(1, DateTimeUnit.DAY)
    repeat(7) {
        if (d.dayOfWeek in days) {
            return d.atTime(windowStartHour, 0).toInstant(tz)
        }
        d = d.plus(1, DateTimeUnit.DAY)
    }
    return windowStartToday.toInstant(tz)
}

private fun buildIntervalRule(
    value: Int,
    unit: IntervalUnit,
    windowStartHour: Int,
    windowEndHour: Int,
    weekdays: Set<DayOfWeek>,
): String {
    val freq = if (unit == IntervalUnit.MINUTES) "MINUTELY" else "HOURLY"
    return buildString {
        append("FREQ=$freq;INTERVAL=$value")
        if (windowEndHour >= windowStartHour) {
            val hours = (windowStartHour..windowEndHour).joinToString(",")
            append(";BYHOUR=$hours")
        }
        if (weekdays.isNotEmpty() && weekdays.size < 7) {
            val days = weekdays
                .sortedBy { it.isoDayNumber }
                .joinToString(",") { it.rruleCode() }
            append(";BYDAY=$days")
        }
    }
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

private fun formatHourLabel(hour: Int): String {
    val h12 = ((hour + 11) % 12) + 1
    val suffix = if (hour < 12) "AM" else "PM"
    return "${h12.toString().padStart(2, '0')}:00 $suffix"
}

@Composable
private fun ComingSoonForm(
    title: String,
    onBack: () -> Unit,
) {
    SheetSection(
        shape = RoundedCornerShape(
            topStart = 14.dp,
            topEnd = 14.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp,
        ),
    ) {
        BuilderHeader(title = title, onBack = onBack)
    }
    SheetSection(
        shape = RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = 14.dp,
            bottomEnd = 14.dp,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Form coming next",
                color = Color(0xFF888888),
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
            )
        }
    }
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
