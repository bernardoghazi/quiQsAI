package com.quiqs.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
                    imageVector = FilterSortIcon,
                    contentDescription = "Filter and sort",
                    modifier = Modifier.size(22.dp),
                )
            }
            FilledTonalIconButton(onClick = onAdd) {
                Icon(
                    imageVector = AddIcon,
                    contentDescription = "Add Q",
                    modifier = Modifier.size(22.dp),
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

/**
 * Funnel + up/down-arrow glyph used by the filter & sort action. Inline to avoid pulling
 * in `material-icons-extended` for a single icon (same rationale as `RepeatIcon`).
 */
private val FilterSortIcon: ImageVector = ImageVector.Builder(
    name = "FilterSort",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        // Funnel.
        moveTo(3f, 4f)
        horizontalLineTo(13f)
        lineTo(9.5f, 11f)
        verticalLineTo(15.5f)
        lineTo(6.5f, 17.5f)
        verticalLineTo(11f)
        close()
        // Up-down arrow on the right.
        moveTo(17f, 4f)
        lineTo(14f, 8f)
        horizontalLineTo(16f)
        verticalLineTo(16f)
        horizontalLineTo(14f)
        lineTo(17f, 20f)
        lineTo(20f, 16f)
        horizontalLineTo(18f)
        verticalLineTo(8f)
        horizontalLineTo(20f)
        close()
    }
}.build()

/** Plus glyph used by the add action. */
private val AddIcon: ImageVector = ImageVector.Builder(
    name = "Add",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(19f, 13f)
        horizontalLineTo(13f)
        verticalLineTo(19f)
        horizontalLineTo(11f)
        verticalLineTo(13f)
        horizontalLineTo(5f)
        verticalLineTo(11f)
        horizontalLineTo(11f)
        verticalLineTo(5f)
        horizontalLineTo(13f)
        verticalLineTo(11f)
        horizontalLineTo(19f)
        close()
    }
}.build()

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
