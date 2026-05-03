package com.quiqs.client

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import com.quiqs.client.audio.NoRecorder
import com.quiqs.client.audio.RecorderProvider
import com.quiqs.client.ui.QDetailScreen
import com.quiqs.client.ui.QListScreen
import com.quiqs.client.ui.QPromptScreen
import com.quiqs.shared.QFilterSort
import com.quiqs.shared.QRepository

/**
 * Composable entry point shared by every platform launcher (Android activity, desktop
 * window, iOS view controller).
 *
 * Routing is intentionally trivial — a single `mutableStateOf<Route>` flips between the
 * three screens. LLM and audio dependencies are injected via [agentProvider] /
 * [recorderProvider]; targets without those capabilities pass the `NoAgent` / `NoRecorder`
 * stubs and the prompt screen surfaces a friendly unavailable message.
 */
@Composable
fun App(
    repository: QRepository,
    agentProvider: AgentProvider = NoAgent,
    recorderProvider: RecorderProvider = NoRecorder,
) {
    var route by remember { mutableStateOf<Route>(Route.List) }
    var filterSort by remember { mutableStateOf(QFilterSort()) }
    val isDark = isSystemInDarkTheme()
    val scheme = if (isDark) QuiqsDarkColorScheme else QuiqsColorScheme
    val backgroundBrush = if (isDark) QuiqsDarkBackgroundBrush else QuiqsBackgroundBrush
    CompositionLocalProvider(LocalThemeIsDark provides isDark) {
        MaterialTheme(colorScheme = scheme) {
            Box(modifier = Modifier.fillMaxSize().background(backgroundBrush)) {
            when (val current = route) {
                is Route.List -> QListScreen(
                    repository = repository,
                    filterSort = filterSort,
                    agentProvider = agentProvider,
                    recorderProvider = recorderProvider,
                    onOpenQ = { id -> route = Route.Detail(id = id) },
                    onQueryQs = { route = Route.Prompt(PromptMode.Query) },
                    onFilterSortApplied = { spec -> filterSort = spec },
                    onClearFilterSort = { filterSort = QFilterSort() },
                )

                is Route.Detail -> QDetailScreen(
                    repository = repository,
                    qId = current.id,
                    onBack = { route = Route.List },
                    onEditWithAi = { id -> route = Route.Prompt(PromptMode.Edit(id)) },
                )

                is Route.Prompt -> QPromptScreen(
                    agentProvider = agentProvider,
                    recorderProvider = recorderProvider,
                    mode = current.mode,
                    onBack = { route = Route.List },
                    onFilterSortApplied = { spec ->
                        filterSort = spec
                        route = Route.List
                    },
                )
            }
            }
        }
    }
}

/**
 * Whether the active theme is dark. Set once where the color scheme is chosen so any
 * descendant can read `LocalThemeIsDark.current` without recomputing — and so a future
 * "force dark/light" user setting in [App] flips the same provider that drives the
 * scheme + gradient choice.
 */
val LocalThemeIsDark = staticCompositionLocalOf { false }

/**
 * App-wide Material color scheme. The `background` and `surface` slots set the page
 * fill that every `Scaffold` (and tonally-elevated surface) inherits — change them
 * here, not at each call site.
 */
val QuiqsColorScheme = lightColorScheme(
    background = Color(0xFFF7F4EE),
    surface = Color(0xFFF7F4EE),
)

val QuiqsDarkColorScheme = darkColorScheme(
    background = Color(0xFF15140F),
    surface = Color(0xFF1C1B16),
)

/**
 * Page-fill gradients painted by the root [App] [Box]. Each [androidx.compose.material3.Scaffold]
 * passes `containerColor = Color.Transparent` so the gradient stays visible behind the
 * top bar / bottom bar / FAB slots — Material's tonal `surface` colors keep cards,
 * dialogs, and tonal-icon buttons opaque on top.
 */
private val QuiqsBackgroundBrush = Brush.radialGradient(
    center = Offset(0f, 0f),
    colors = listOf(Color(0xFFE8E8E8), Color(0xFF9A9A9A)),
)

private val QuiqsDarkBackgroundBrush = Brush.radialGradient(
    center = Offset(0f, 0f),
    radius = 3000F,
    colors = listOf(Color(0xFF141414), Color(0xFF000000)),
)
