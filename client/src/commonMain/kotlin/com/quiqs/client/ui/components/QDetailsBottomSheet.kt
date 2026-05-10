package com.quiqs.client.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quiqs.client.LocalThemeIsDark
import com.quiqs.client.QuiqsColorScheme
import com.quiqs.client.QuiqsDarkColorScheme
import com.quiqs.client.audio.AudioRecorder
import com.quiqs.client.audio.NoRecorder
import com.quiqs.client.audio.RecorderProvider
import com.quiqs.client.audio.RecorderStatus
import com.quiqs.shared.Alarm
import com.quiqs.shared.Priority
import com.quiqs.shared.Q
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import quiqsai.client.generated.resources.Res
import quiqsai.client.generated.resources.clock
import quiqsai.client.generated.resources.exclamation
import quiqsai.client.generated.resources.label
import quiqsai.client.generated.resources.plus
import quiqsai.client.generated.resources.wave
import quiqsai.client.generated.resources.x
import kotlin.time.Instant
import kotlin.uuid.Uuid


const val SHEET_SECTION_HORIZONTAL_PADDING = 22

/**
 * Bottom sheet shown when a Q is tapped on the list. Replaces the navigation push to
 * `QDetailScreen` with an in-place inspector that surfaces the Q's text, alarm/priority/
 * tag pills, and a placeholder action bar.
 *
 * The sheet is visually composed of four independent rounded surfaces separated by 8 dp
 * gaps, so its `containerColor` is transparent and the gaps fall through to the dimmed
 * scrim. Clicking outside (scrim tap) or back-press dismisses via [onDismiss].
 *
 * Pill `×` glyphs and the action-bar icons are temporary placeholders rendered with
 * `Res.drawable.plus`; both will be swapped for real assets once they land.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QDetailsBottomSheet(
    q: Q,
    recorderProvider: RecorderProvider,
    onSetVoiceNote: (String) -> Unit,
    onClearVoiceNote: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.8f),
        dragHandle = null,
        shape = RectangleShape,
        // Apply only the bottom navigation-bar inset so the sheet sits above the system
        // nav bar — same behavior `Scaffold` gives a `bottomBar`. Top/horizontal insets
        // stay zero because the sheet anchors to the bottom and spans full width.
        contentWindowInsets = { WindowInsets.navigationBars.only(WindowInsetsSides.Bottom) },
    ) {
        QDetailsBottomSheetContent(
            q = q,
            recorderProvider = recorderProvider,
            onSetVoiceNote = onSetVoiceNote,
            onClearVoiceNote = onClearVoiceNote,
        )
    }
}

/**
 * The sheet's body, factored out so it can be rendered in `@Preview` (the surrounding
 * [ModalBottomSheet] requires a real Window and won't render in tooling).
 *
 * The voice-note hooks default to no-op stubs so previews can render both states without
 * a runtime recorder.
 */
@Composable
private fun QDetailsBottomSheetContent(
    q: Q,
    recorderProvider: RecorderProvider = NoRecorder,
    onSetVoiceNote: (String) -> Unit = {},
    onClearVoiceNote: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Section 1 — Q text. Top corners rounded more than the others to read as the
        // sheet's header.
        SheetSection(
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = 4.dp,
                bottomEnd = 4.dp,
            ),
        ) {
            Text(
                text = q.text,
                color = Color(0xFFEDEDED),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(
                    start = SHEET_SECTION_HORIZONTAL_PADDING.dp,
                    top = 22.dp,
                    end = SHEET_SECTION_HORIZONTAL_PADDING.dp,
                    bottom = 18.dp
                ),
            )
        }

        // Section 2 — voice note. Two visual states based on whether the Q has audio
        // attached: a press-and-hold "add" button when none, otherwise a tap-to-play
        // pill with an inline delete affordance.
        SheetSection(shape = RoundedCornerShape(4.dp)) {
            VoiceNoteSection(
                voiceNotePath = q.voiceNotePath,
                recorderProvider = recorderProvider,
                onSetVoiceNote = onSetVoiceNote,
                onClearVoiceNote = onClearVoiceNote,
            )
        }

        // Section 3 — alarm on row 1, priority + tags on row 2.
        SheetSection(shape = RoundedCornerShape(4.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                q.alarms.firstOrNull()?.let { alarm ->
                    AlarmPill(alarm = alarm, onDismiss = {})
                }
                val priorityTone = when (q.priority) {
                    Priority.HIGH -> QBadgeTone.High
                    Priority.LOW -> QBadgeTone.Low
                    Priority.MEDIUM -> null
                }
                val hasPills = priorityTone != null || q.tags.isNotEmpty()
                if (hasPills) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        priorityTone?.let { tone ->
                            DismissPill(label = q.priority.wire, tone = tone, onDismiss = {})
                        }
                        q.tags.forEach { tag ->
                            DismissPill(label = tag, tone = QBadgeTone.Neutral, onDismiss = {})
                        }
                    }
                }
            }
        }

        // Section 4 — action bar. Bottom corners are square so the section abuts the
        // bottom of the screen.
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
                IconButton(onClick = {}) {
                    Icon(
                        painter = painterResource(Res.drawable.exclamation),
                        contentDescription = null,
                        tint = Color(0xFFEDEDED),
                        modifier = Modifier.size(17.dp),
                    )
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
                TextButton(onClick = {}) {
                    Text(
                        text = "Save",
                        color = Color(0xFFEDEDED),
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetSection(
    shape: Shape,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = Color(0xFF080808),
    ) {
        content()
    }
}

/**
 * Two-state voice-note panel.
 *
 * No audio attached: a wide orange button. Long-pressing the button asks
 * [recorderProvider] for an [AudioRecorder] (which prompts for the mic permission on
 * first use), starts capture, and on release stops the recorder and forwards the
 * resulting absolute path through [onSetVoiceNote].
 *
 * Audio attached: a speaker glyph plus a white "tap to play voice note" pill, with an
 * `×` in the top-right that calls [onClearVoiceNote] to detach the recording.
 *
 * Playback isn't wired yet — the play pill currently does nothing on tap.
 */
@Composable
private fun VoiceNoteSection(
    voiceNotePath: String?,
    recorderProvider: RecorderProvider,
    onSetVoiceNote: (String) -> Unit,
    onClearVoiceNote: () -> Unit,
) {
    if (voiceNotePath == null) {
        HoldToRecordButton(
            recorderProvider = recorderProvider,
            onRecorded = onSetVoiceNote,
        )
    } else {
        VoiceNotePlayBlock(
            onTapPlay = { /* Playback wiring lands later. */ },
            onClear = onClearVoiceNote,
        )
    }
}

@Composable
private fun HoldToRecordButton(
    recorderProvider: RecorderProvider,
    onRecorded: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Track whether the current pointer-down has crossed the long-press threshold so
    // the shared `onPress` handler only stops recording on releases that actually
    // started one. Mirrors the FAB voice-prompt gesture in `QListScreen`.
    var longPressActive by remember { mutableStateOf(false) }
    var activeRecorder by remember { mutableStateOf<AudioRecorder?>(null) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
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
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFFD85A00),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.wave),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "hold to add voice note",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun VoiceNotePlayBlock(
    onTapPlay: () -> Unit,
    onClear: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp, vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Speaker icon — placeholder using the existing `wave` drawable; the real
            // phone-with-speaker glyph will swap in when added.
            Icon(
                painter = painterResource(Res.drawable.wave),
                contentDescription = null,
                tint = Color(0xFFB8B8B8),
                modifier = Modifier.size(28.dp),
            )
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTapPlay() },
                shape = RoundedCornerShape(10.dp),
                color = Color.White,
            ) {
                Text(
                    text = "tap to play voice note",
                    color = Color(0xFF111111),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
        IconButton(
            onClick = onClear,
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(
                painter = painterResource(Res.drawable.x),
                contentDescription = "Remove voice note",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
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
    onDismiss: () -> Unit,
) {
    val (bg, fg) = pillColors(tone)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .widthIn(min = 45.dp, max = 80.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        QText(text = label, role = QTextRole.Badge, color = fg)
        Spacer(modifier = Modifier.width(10.dp))
        DismissIcon(onClick = onDismiss)
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

private fun previewSampleQ(): Q {
    val now = Instant.fromEpochMilliseconds(1_746_280_800_000)
    return Q(
        id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        text = "Get groceries for the party.",
        tags = listOf("work", "personal"),
        alarms = listOf(Alarm.OneOffTime(at = now)),
        priority = Priority.HIGH,
        completed = false,
        createdAt = now,
        updatedAt = now,
    )
}

@Preview
@Composable
private fun QDetailsBottomSheetPreview() {
    CompositionLocalProvider(LocalThemeIsDark provides false) {
        val scheme = if (LocalThemeIsDark.current) QuiqsDarkColorScheme else QuiqsColorScheme
        MaterialTheme(colorScheme = scheme) {
            Box(modifier = Modifier.background(color = Color(0xFFF7F4EE))) {
                QDetailsBottomSheetContent(q = previewSampleQ())
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
                QDetailsBottomSheetContent(q = previewSampleQ())
            }
        }
    }
}
