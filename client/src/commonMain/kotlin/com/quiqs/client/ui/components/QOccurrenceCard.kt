package com.quiqs.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quiqs.client.LocalThemeIsDark
import com.quiqs.shared.Priority
import com.quiqs.shared.Q

/**
 * Compact card representing a Q in a timeline view (Day or Week). Lays out as a
 * vertical stack:
 *  - Top row: round checkbox + muted [timeLabel].
 *  - Title row: single-line, ellipsized — spans the card's full content width.
 *  - Badge row (when present): priority badge + tag chips in a [FlowRow] so they
 *    wrap to multiple lines for tag-heavy Qs.
 *
 * Caller pre-formats [timeLabel] so the same card can show `":30"` inside a Day
 * view's hour-labelled gutter or `"9:30 AM"` in a Week-section list where there's
 * no hour gutter to anchor against. Voice-only Qs (no title text) render "Voice
 * note" as the title.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QOccurrenceCard(
    q: Q,
    timeLabel: String,
    onTap: () -> Unit,
    onToggleCompleted: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalThemeIsDark.current
    val cardContainer = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val cardOutline = if (isDark) Color(0xFF262626) else Color(0xFFC4C4C4)
    val timeColor = if (isDark) Color(0xFF8A8A8A) else Color(0xFF6E6E6E)
    val titleColor = if (isDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)

    val priorityBadge = when (q.priority) {
        Priority.HIGH -> q.priority.wire to QBadgeTone.High
        Priority.LOW -> q.priority.wire to QBadgeTone.Low
        Priority.MEDIUM -> null
    }
    val hasBadges = priorityBadge != null || q.tags.isNotEmpty()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onTap() },
        shape = RoundedCornerShape(10.dp),
        color = cardContainer,
        border = BorderStroke(1.dp, cardOutline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QCheckbox(
                    modifier = Modifier.offset(y = 3.dp),
                    checked = q.completed,
                    onCheckedChange = onToggleCompleted,
                )
                Text(text = timeLabel, color = timeColor, fontSize = 12.sp)
                val titleText = if (q.text.isBlank() && q.voiceNotePath != null) "Voice note" else q.text
                Text(
                    text = titleText,
                    color = titleColor,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasBadges) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    priorityBadge?.let { (label, tone) -> QBadge(label = label, tone = tone) }
                    q.tags.forEach { tag -> QBadge(label = tag, tone = QBadgeTone.Neutral) }
                }
            }
        }
    }
}
