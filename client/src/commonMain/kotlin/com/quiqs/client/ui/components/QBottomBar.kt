package com.quiqs.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.quiqs.client.LocalThemeIsDark
import com.quiqs.client.QuiqsColorScheme
import com.quiqs.client.QuiqsDarkColorScheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import quiqsai.client.generated.resources.Res
import quiqsai.client.generated.resources.day
import quiqsai.client.generated.resources.filter
import quiqsai.client.generated.resources.list
import quiqsai.client.generated.resources.month
import quiqsai.client.generated.resources.plus
import quiqsai.client.generated.resources.three_dots
import quiqsai.client.generated.resources.week

/**
 * Which list timeline view the user is currently on. Drives the four-way switcher in
 * [QBottomBar]'s left group; the screen consuming the spec decides what each mode
 * renders.
 */
enum class ViewMode { List, Day, Week, Month }

/**
 * Bottom action bar shown on the list screen.
 *
 * Layout:
 *   [list | day | week | month]                        [filter] [add]  [⋮]
 *   └── grouped pill (selected gets a filled square)   └── filled rounded squares,
 *                                                          plus a plain overflow icon
 */
@Composable
fun QBottomBar(
    onFilterSort: () -> Unit,
    onAdd: () -> Unit,
    onOverflow: () -> Unit,
    modifier: Modifier = Modifier,
    selectedView: ViewMode = ViewMode.List,
    onSelectView: (ViewMode) -> Unit = {},
) {
    val isDark = LocalThemeIsDark.current
    val groupContainer = if (isDark) Color(0xFF161616) else Color(0xFFDBDBDB)
    val filledSquare = if (isDark) Color(0xFF000000) else Color(0xFF1A1A1A)
    val onFilledIconTint = Color(0xFFEFEFEF)
    val unselectedIconTint = if (isDark) Color(0xFF7A7A7A) else Color(0xFF6E6E6E)
    val plainIconTint = if (isDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewModeGroup(
            selectedView = selectedView,
            onSelectView = onSelectView,
            container = groupContainer,
            selectedBg = filledSquare,
            selectedTint = onFilledIconTint,
            unselectedTint = unselectedIconTint,
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledSquareButton(
                drawable = Res.drawable.filter,
                contentDescription = "Filter and sort",
                background = filledSquare,
                tint = onFilledIconTint,
                onClick = onFilterSort,
                iconSize = 24.dp,
                iconOffset = DpOffset(x = 1.dp, y = 1.dp)
            )
            FilledSquareButton(
                drawable = Res.drawable.plus,
                contentDescription = "Add Q",
                background = filledSquare,
                tint = onFilledIconTint,
                onClick = onAdd,
            )
            IconButton(onClick = onOverflow) {
                Icon(
                    painter = painterResource(Res.drawable.three_dots),
                    contentDescription = "More",
                    tint = plainIconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ViewModeGroup(
    selectedView: ViewMode,
    onSelectView: (ViewMode) -> Unit,
    container: Color,
    selectedBg: Color,
    selectedTint: Color,
    unselectedTint: Color,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewModeToggle(ViewMode.List, Res.drawable.list, "List view", selectedView, onSelectView, selectedBg, selectedTint, unselectedTint)
            ViewModeToggle(ViewMode.Day, Res.drawable.day, "Day view", selectedView, onSelectView, selectedBg, selectedTint, unselectedTint)
            ViewModeToggle(ViewMode.Week, Res.drawable.week, "Week view", selectedView, onSelectView, selectedBg, selectedTint, unselectedTint)
            ViewModeToggle(ViewMode.Month, Res.drawable.month, "Month view", selectedView, onSelectView, selectedBg, selectedTint, unselectedTint)
        }
    }
}

@Composable
private fun ViewModeToggle(
    mode: ViewMode,
    drawable: DrawableResource,
    contentDescription: String,
    selectedView: ViewMode,
    onSelectView: (ViewMode) -> Unit,
    selectedBg: Color,
    selectedTint: Color,
    unselectedTint: Color,
) {
    val isSelected = selectedView == mode
    Surface(
        onClick = { onSelectView(mode) },
        shape = RoundedCornerShape(6.dp),
        color = if (isSelected) selectedBg else Color.Transparent,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(drawable),
                contentDescription = contentDescription,
                tint = if (isSelected) selectedTint else unselectedTint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun FilledSquareButton(
    drawable: DrawableResource,
    contentDescription: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
    iconSize: Dp = 20.dp,
    iconOffset: DpOffset = DpOffset.Zero
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = background,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(drawable),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier
                    .size(iconSize)
                    .offset(x = iconOffset.x, y = iconOffset.y),
            )
        }
    }
}

@Preview
@Composable
private fun QBottomBarPreview() {
    CompositionLocalProvider(LocalThemeIsDark provides false) {
        val scheme = if (LocalThemeIsDark.current) QuiqsDarkColorScheme else QuiqsColorScheme
        MaterialTheme(colorScheme = scheme) {
            Box(modifier = Modifier.background(color = Color(0xFFF7F4EE))) {
                QBottomBar(
                    onFilterSort = {},
                    onAdd = {},
                    onOverflow = {},
                    selectedView = ViewMode.List,
                    onSelectView = {},
                )
            }
        }
    }
}

@Preview
@Composable
private fun QBottomBarPreviewDark() {
    CompositionLocalProvider(LocalThemeIsDark provides true) {
        val scheme = if (LocalThemeIsDark.current) QuiqsDarkColorScheme else QuiqsColorScheme
        MaterialTheme(colorScheme = scheme) {
            Box(modifier = Modifier.background(color = Color(0xFF0A0A0A))) {
                QBottomBar(
                    onFilterSort = {},
                    onAdd = {},
                    onOverflow = {},
                    selectedView = ViewMode.Day,
                    onSelectView = {},
                )
            }
        }
    }
}
