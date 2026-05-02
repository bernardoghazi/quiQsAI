package com.quiqs.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import quiqsai.client.generated.resources.Res
import quiqsai.client.generated.resources.filter
import quiqsai.client.generated.resources.plus
import org.jetbrains.compose.resources.painterResource

/**
 * Bottom action bar shown on the list screen. Pill-shaped surface with three right-aligned
 * actions: filter & sort, add new Q, and an overflow menu trigger.
 *
 * Each action takes its own `onClick` so callers can wire (or stub) them independently.
 */
@Composable
fun QBottomBar(
    onFilterSort: () -> Unit,
    onAdd: () -> Unit,
    onOverflow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onFilterSort) {
                Icon(
                    painter = painterResource(Res.drawable.filter),
                    contentDescription = "Filter and sort",
                    modifier = Modifier
                        .size(22.dp)
                        .offset(x = 1.dp, y = 1.dp),
                )
            }
            FilledTonalIconButton(onClick = onAdd) {
                Icon(
                    painter = painterResource(Res.drawable.plus),
                    contentDescription = "Add Q",
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = onOverflow) {
                Icon(
                    imageVector = MoreVertIcon,
                    contentDescription = "More",
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

// TODO: use svg.
/** Three-dot vertical glyph used by the overflow action. */
private val MoreVertIcon: ImageVector = ImageVector.Builder(
    name = "MoreVert",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(10f, 6f)
        arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 0f)
        arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, -4f, 0f)
        close()
        moveTo(10f, 12f)
        arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 0f)
        arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, -4f, 0f)
        close()
        moveTo(10f, 18f)
        arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 0f)
        arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, -4f, 0f)
        close()
    }
}.build()
