package com.quiqs.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quiqs.client.LocalThemeIsDark
import com.quiqs.client.QuiqsColorScheme
import com.quiqs.client.QuiqsDarkColorScheme
import com.quiqs.shared.Alarm
import com.quiqs.shared.Priority
import com.quiqs.shared.Q
import org.jetbrains.compose.resources.painterResource
import quiqsai.client.generated.resources.Res
import quiqsai.client.generated.resources.clock
import quiqsai.client.generated.resources.exclamation
import quiqsai.client.generated.resources.label
import quiqsai.client.generated.resources.plus
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
        QDetailsBottomSheetContent(q = q)
    }
}

/**
 * The sheet's body, factored out so it can be rendered in `@Preview` (the surrounding
 * [ModalBottomSheet] requires a real Window and won't render in tooling).
 */
@Composable
private fun QDetailsBottomSheetContent(q: Q) {
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

        // Section 2 — placeholder; real content (voice-note playback, etc.) lands later.
        SheetSection(shape = RoundedCornerShape(4.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "Section 2 placeholder",
                    color = Color(0xFF666666),
                    fontSize = 14.sp,
                )
            }
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
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = {}) {
                    Icon(
                        painter = painterResource(Res.drawable.clock),
                        contentDescription = null,
                        tint = Color(0xFFEDEDED),
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = {}) {
                    Icon(
                        painter = painterResource(Res.drawable.exclamation),
                        contentDescription = null,
                        tint = Color(0xFFEDEDED),
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(onClick = {}) {
                    Icon(
                        painter = painterResource(Res.drawable.label),
                        contentDescription = null,
                        tint = Color(0xFFEDEDED),
                        modifier = Modifier.size(24.dp),
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
        color = Color(0xFF0D0D0D),
    ) {
        content()
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
