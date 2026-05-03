package com.quiqs.client.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** Visual role of a [QText] — drives default size, weight, and style. */
enum class QTextRole { Title, Alarm, Badge }

/**
 * Project-wide text wrapper used as the title of [QCard] and inside subcomponents
 * like [QBadge] and [QAlarmLabel]. Centralising styles keeps typography consistent
 * if we later swap font families or scales.
 */
@Composable
fun QText(
    text: String,
    role: QTextRole = QTextRole.Title,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val style = when (role) {
        QTextRole.Title -> TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
        QTextRole.Alarm -> TextStyle(fontSize = 13.sp, fontStyle = FontStyle.Italic)
        QTextRole.Badge -> TextStyle(fontSize = 12.sp, fontStyle = FontStyle.Italic)
    }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
    )
}
