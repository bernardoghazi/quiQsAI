package com.quiqs.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quiqs.client.LocalThemeIsDark
import com.quiqs.shared.Priority
import com.quiqs.shared.Q

/**
 * Compact single-line card representing a Q in a timeline view (Day or Week).
 *
 * Visual order: round checkbox → muted [timeLabel] → title → priority/first-tag
 * badges → "+N" tag-overflow hint. Caller pre-formats [timeLabel] so the same card
 * can show `":30"` inside a Day view's hour-labelled gutter or `"9:30 AM"` in a
 * Week-section list where there's no hour gutter to anchor against.
 *
 * Voice-only Qs (no title text) render "Voice note" as the title.
 */
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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onTap() },
        shape = RoundedCornerShape(10.dp),
        color = cardContainer,
        border = BorderStroke(1.dp, cardOutline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            // Top-aligned so the checkbox and time stay anchored at the top edge when
            // the title wraps to two lines; otherwise center-alignment shifts them down
            // by half the wrapped height, which reads as drift.
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QCheckbox(
                checked = q.completed,
                onCheckedChange = onToggleCompleted,
            )
            Text(text = timeLabel, color = timeColor, fontSize = 12.sp)
            val titleText = if (q.text.isBlank() && q.voiceNotePath != null) "Voice note" else q.text
            Text(
                text = titleText,
                color = titleColor,
                fontSize = 14.sp,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            val priorityBadge = when (q.priority) {
                Priority.HIGH -> q.priority.wire to QBadgeTone.High
                Priority.LOW -> q.priority.wire to QBadgeTone.Low
                Priority.MEDIUM -> null
            }
            priorityBadge?.let { (label, tone) -> QBadge(label = label, tone = tone) }
            q.tags.firstOrNull()?.let { tag ->
                QBadge(label = tag, tone = QBadgeTone.Neutral)
                if (q.tags.size > 1) {
                    Text(
                        text = "+${q.tags.size - 1}",
                        color = timeColor,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
