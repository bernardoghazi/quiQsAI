package com.quiqs.client.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/** Horizontal padding shared by every [SheetSection]'s inner content. */
const val SHEET_SECTION_HORIZONTAL_PADDING = 22

/**
 * Scrim color shared by every bottom sheet in the app, plus any in-sheet scrims that
 * need to read as the same surface (e.g. the priority popup's backdrop on the details
 * sheet).
 */
internal val SheetScrimColor = Color(0xE60F0F0F)

/**
 * Black-surfaced section block used to compose the body of the app's bottom sheets.
 * Callers stack several of these in a [androidx.compose.foundation.layout.Column] with a
 * 4 dp gap and pick the [shape] per position — the first section's top corners get a
 * 14 dp radius and bottom corners 4 dp; the last section is the inverse; middles are
 * 4 dp all around.
 */
@Composable
internal fun SheetSection(
    shape: Shape,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = Color(0xFF000000),
    ) {
        content()
    }
}
