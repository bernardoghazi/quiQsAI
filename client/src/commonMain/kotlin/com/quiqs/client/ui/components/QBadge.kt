package com.quiqs.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Color treatment for [QBadge]. High = red-tinted, Neutral/Low = grey. */
enum class QBadgeTone { Neutral, High, Low }

private data class BadgeColors(val bg: Color, val fg: Color)

private fun colorsFor(tone: QBadgeTone): BadgeColors = when (tone) {
    QBadgeTone.Neutral -> BadgeColors(
        bg = Color(0xFF161616),
        fg = Color(0xFF666666),
    )
    QBadgeTone.High -> BadgeColors(
        bg = Color(0xFF200C0C),
        fg = Color(0xFFA34949),
    )
    QBadgeTone.Low -> BadgeColors(
        bg = Color(0xFFF1F4F8),
        fg = Color(0xFF8893A1),
    )
}

/** Pill-shaped chip used for priority and tag labels in [QCard]. */
@Composable
fun QBadge(
    label: String,
    tone: QBadgeTone = QBadgeTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = colorsFor(tone)
    val shape = RoundedCornerShape(26)
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        QText(text = label, role = QTextRole.Badge, color = fg)
    }
}
