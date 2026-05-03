package com.quiqs.client.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.quiqs.client.LocalThemeIsDark
import com.quiqs.shared.Alarm
import com.quiqs.shared.Priority
import com.quiqs.shared.Q
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Card representing a single Q in the list.
 *
 * Layout:
 *   [checkbox] [title — wraps]
 *               [alarm (optional)]   [priority? + tag badges]
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QCard(
    q: Q,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalThemeIsDark.current
    val cardContainer = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val cardContent = if (isDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)
    Card(
        modifier = modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardContainer,
            contentColor = cardContent,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                QCheckbox(checked = checked, onCheckedChange = onCheckedChange)
                QText(
                    text = q.text,
                    role = QTextRole.Title,
                    modifier = Modifier.weight(1f),
                )
            }

            val priorityBadge = when (q.priority) {
                Priority.HIGH -> q.priority.wire to QBadgeTone.High
                Priority.LOW -> q.priority.wire to QBadgeTone.Low
                Priority.MEDIUM -> null
            }
            val hasBottomRow = q.alarms.isNotEmpty() || priorityBadge != null || q.tags.isNotEmpty()
            if (hasBottomRow) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    q.alarms.firstOrNull()?.let { alarm ->
                        QAlarmLabel(alarm = alarm, modifier = Modifier.weight(1f))
                    } ?: run {
                        // Empty spacer pushes badges to the right when no alarm.
                        Row(modifier = Modifier.weight(1f)) {}
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        priorityBadge?.let { (label, tone) -> QBadge(label = label, tone = tone) }
                        q.tags.forEach { tag -> QBadge(label = tag, tone = QBadgeTone.Neutral) }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun QCardPreview() {
    val now = Instant.fromEpochMilliseconds(1_746_280_800_000)
    val sample = Q(
        id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        text = "Pick up Stella from school",
        tags = listOf("family", "afternoon"),
        alarms = listOf(Alarm.OneOffTime(at = now)),
        priority = Priority.HIGH,
        completed = false,
        createdAt = now,
        updatedAt = now,
    )
    MaterialTheme {
        QCard(
            q = sample,
            checked = sample.completed,
            onCheckedChange = {},
            onClick = {},
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Preview
@Composable
private fun QCardDarkPreview() {
    val now = Instant.fromEpochMilliseconds(1_746_280_800_000)
    val sample = Q(
        id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        text = "Pick up Stella from school",
        tags = listOf("family", "afternoon"),
        alarms = listOf(Alarm.OneOffTime(at = now)),
        priority = Priority.HIGH,
        completed = false,
        createdAt = now,
        updatedAt = now,
    )
    CompositionLocalProvider(LocalThemeIsDark provides true) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            QCard(
                q = sample,
                checked = sample.completed,
                onCheckedChange = {},
                onClick = {},
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
