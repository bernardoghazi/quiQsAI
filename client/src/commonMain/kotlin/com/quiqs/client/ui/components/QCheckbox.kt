package com.quiqs.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.toggleable

/**
 * Round, hollow checkbox shown next to a Q's title in [QCard].
 *
 * Visually represents whether a Q is complete: empty circle when [checked] is `false`,
 * filled with a checkmark when `true`.
 */
@Composable
fun QCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (checked) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline
    val fillColor = if (checked) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = null)
            .border(width = 1.5.dp, color = borderColor, shape = CircleShape)
            .background(color = fillColor, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text(
                text = "✓",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 14.sp,
            )
        }
    }
}
