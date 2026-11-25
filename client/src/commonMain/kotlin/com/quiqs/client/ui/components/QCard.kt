package com.quiqs.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.quiqs.client.LocalThemeIsDark
import com.quiqs.client.QuiqsColorScheme
import com.quiqs.client.QuiqsDarkColorScheme
import com.quiqs.client.audio.AudioPlayer
import com.quiqs.client.audio.NoPlayer
import com.quiqs.client.audio.PlayerProvider
import com.quiqs.client.audio.PlayerStatus
import com.quiqs.shared.Alarm
import com.quiqs.shared.Priority
import com.quiqs.shared.Q
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import quiqsai.client.generated.resources.Res
import quiqsai.client.generated.resources.speaker
import quiqsai.client.generated.resources.stop
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Card representing a single Q in the list.
 *
 * Layout:
 *   [checkbox] [title — wraps]                 [▶ play (optional)]
 *               [alarm (optional)]   [priority? + tag badges]
 *
 * A Q may have a title, a voice note, or both. When a voice note is present, a small
 * speaker/stop button appears at the top-right of the title row and toggles playback
 * via [playerProvider]. When the Q has no title, "Voice note" stands in so the row
 * still has a primary label. The play button uses its own [Modifier.clickable], which
 * consumes the tap before the card's outer click handler — so playing/stopping won't
 * also open the details sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QCard(
    q: Q,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    playerProvider: PlayerProvider = NoPlayer,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalThemeIsDark.current
    val cardContainer = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val cardContent = if (isDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)
    val cardOutline = if (isDark) Color(0xFF262626) else Color(0xFFC4C4C4)
    Card(
        modifier = modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardContainer,
            contentColor = cardContent,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, cardOutline),
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Indents the first line of the title so the checkbox can overlay that
            // space and read as part of the text block; subsequent lines flow back to the
            // left edge. Indent is converted from dp so it stays at 16.dp regardless of
            // the user's font scale.
            val titleIndent = with(LocalDensity.current) { TextIndent(firstLine = 24.dp.toSp()) }
            val hasVoiceNote = q.voiceNotePath != null
            // When the Q has no title text but does have a voice note, show "Voice note"
            // as a stand-in primary label so the card still has something to read. If
            // both are missing the card shouldn't exist — render q.text either way.
            val titleText = if (q.text.isBlank() && hasVoiceNote) "Voice note" else q.text
            Box(modifier = Modifier.fillMaxWidth()) {
                QText(
                    text = titleText,
                    role = QTextRole.Title,
                    // End padding reserves space for the play button so wrapped lines
                    // don't run under it.
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(28.dp, Dp.Infinity)
                        .padding(end = if (hasVoiceNote) 32.dp else 0.dp, top = 5.dp),
                    textIndent = titleIndent,
                    maxLines = 4
                )
                QCheckbox(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 6.dp),
                )
                if (hasVoiceNote) {
                    VoiceNotePlayIcon(
                        voiceNotePath = q.voiceNotePath!!,
                        playerProvider = playerProvider,
                        tint = cardContent,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
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

/**
 * Compact play/stop button rendered at the top-right of a [QCard] that owns a voice
 * note. Tapping starts playback via [playerProvider]; tapping again stops it. Stops
 * playback if the card scrolls off-screen / leaves composition so audio doesn't
 * continue into a void.
 *
 * The underlying [AudioPlayer] is single-track — if another card starts playing while
 * this one is in flight, this player is stopped externally and `onComplete` won't
 * fire, so the icon will appear stuck on "stop" until tapped. Acceptable for now;
 * consolidate playback state at a higher level if multi-card UX gets fancier.
 */
@Composable
private fun VoiceNotePlayIcon(
    voiceNotePath: String,
    playerProvider: PlayerProvider,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var activePlayer by remember { mutableStateOf<AudioPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            activePlayer?.stop()
            activePlayer = null
        }
    }

    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable {
                if (isPlaying) {
                    activePlayer?.stop()
                    activePlayer = null
                    isPlaying = false
                } else {
                    scope.launch {
                        when (val s = playerProvider.get()) {
                            is PlayerStatus.Available -> {
                                activePlayer = s.player
                                isPlaying = true
                                s.player.play(voiceNotePath, onComplete = {
                                    if (activePlayer === s.player) {
                                        activePlayer = null
                                        isPlaying = false
                                    }
                                })
                            }
                            // Player unavailable on this platform: drop the tap silently.
                            is PlayerStatus.Unavailable -> Unit
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (isPlaying) Res.drawable.stop else Res.drawable.speaker),
            contentDescription = if (isPlaying) "Stop voice note" else "Play voice note",
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Preview
@Composable
private fun QCardPreview() {
    val now = Instant.fromEpochMilliseconds(1_746_280_800_000)
    val sample = Q(
        id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        text = "Pick up Stella from school",
        tags = listOf("family", "personal"),
        alarms = listOf(Alarm.OneOffTime(at = now)),
        priority = Priority.HIGH,
        completed = false,
        createdAt = now,
        updatedAt = now,
        voiceNotePath = "xyz"
    )
    MaterialTheme {
        CompositionLocalProvider(LocalThemeIsDark provides true) {
            val scheme = if (LocalThemeIsDark.current) QuiqsDarkColorScheme else QuiqsColorScheme
            MaterialTheme(colorScheme = scheme) {
                Box(modifier = Modifier.background(color = Color(0xFFD9D9D9))) {
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
    }
}

@Preview
@Composable
private fun QCardDarkPreview() {
    val now = Instant.fromEpochMilliseconds(1_746_280_800_000)
    val sample = Q(
        id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        text = """
Pick up Stella from school and bla bla bla bla bla bla.
Bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla bla.
            """.trimMargin(),
        tags = listOf("family", "personal"),
        alarms = listOf(Alarm.RecurringTime(rule = "FREQ=WEEKLY;BYDAY=MO,WE,FR", dtStart = now, until = now)),
        priority = Priority.HIGH,
        completed = false,
        createdAt = now,
        updatedAt = now,
        voiceNotePath = "xyz"
    )
    CompositionLocalProvider(LocalThemeIsDark provides true) {
        val scheme = if (LocalThemeIsDark.current) QuiqsDarkColorScheme else QuiqsColorScheme
        MaterialTheme(colorScheme = scheme) {
            Box(modifier = Modifier.background(color = Color(0xFF111111))) {
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
}
