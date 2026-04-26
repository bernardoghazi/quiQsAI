package com.quiqs.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.quiqs.shared.Alarm
import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Shows the user-facing label for a single [Alarm], optionally prefixed with a repeat
 * icon when the alarm recurs.
 */
@Composable
fun QAlarmLabel(
    alarm: Alarm,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (alarm is Alarm.RecurringTime) {
            Icon(
                imageVector = RepeatIcon,
                contentDescription = "Recurring",
                tint = color,
                modifier = Modifier.size(14.dp),
            )
        }
        QText(text = alarm.formatLabel(), role = QTextRole.Alarm, color = color)
    }
}

/** Renders the alarm as the same kind of short label the list cell uses. */
private fun Alarm.formatLabel(): String = when (this) {
    is Alarm.OneOffTime -> formatRelativeDateTime(at)
    is Alarm.RecurringTime -> rule
    is Alarm.Geofence -> "Near $latitude, $longitude"
}

/**
 * Format an instant relative to "now" in the device's TZ. Examples:
 *   today    → "Today, 06:32 AM"
 *   tomorrow → "Tomorrow, 06:32 AM"
 *   < 1wk    → "Mon, Apr 27, 07:30 PM"
 *   beyond   → "Apr 27, 2026, 07:30 PM"
 */
private fun formatRelativeDateTime(at: Instant): String {
    val tz = TimeZone.currentSystemDefault()
    val nowLocal = Clock.System.now().toLocalDateTime(tz)
    val atLocal = at.toLocalDateTime(tz)
    val daysDiff = atLocal.date.toEpochDays() - nowLocal.date.toEpochDays()

    val time = formatTime12h(atLocal)
    val datePart = when {
        daysDiff == 0 -> "Today"
        daysDiff == 1 -> "Tomorrow"
        daysDiff in 2..6 -> "${atLocal.dayOfWeek.shortLabel()}, ${atLocal.month.shortLabel()} ${atLocal.dayOfMonth}"
        atLocal.year == nowLocal.year -> "${atLocal.month.shortLabel()} ${atLocal.dayOfMonth}"
        else -> "${atLocal.month.shortLabel()} ${atLocal.dayOfMonth}, ${atLocal.year}"
    }
    return "$datePart, $time"
}

private fun formatTime12h(local: LocalDateTime): String {
    val hour24 = local.hour
    val hour12 = ((hour24 + 11) % 12) + 1
    val suffix = if (hour24 < 12) "AM" else "PM"
    val minute = local.minute.toString().padStart(2, '0')
    val hour = hour12.toString().padStart(2, '0')
    return "$hour:$minute $suffix"
}

private fun DayOfWeek.shortLabel(): String = when (this) {
    DayOfWeek.MONDAY -> "Mon"
    DayOfWeek.TUESDAY -> "Tue"
    DayOfWeek.WEDNESDAY -> "Wed"
    DayOfWeek.THURSDAY -> "Thu"
    DayOfWeek.FRIDAY -> "Fri"
    DayOfWeek.SATURDAY -> "Sat"
    DayOfWeek.SUNDAY -> "Sun"
}

private fun Month.shortLabel(): String = when (this) {
    Month.JANUARY -> "Jan"
    Month.FEBRUARY -> "Feb"
    Month.MARCH -> "Mar"
    Month.APRIL -> "Apr"
    Month.MAY -> "May"
    Month.JUNE -> "Jun"
    Month.JULY -> "Jul"
    Month.AUGUST -> "Aug"
    Month.SEPTEMBER -> "Sep"
    Month.OCTOBER -> "Oct"
    Month.NOVEMBER -> "Nov"
    Month.DECEMBER -> "Dec"
}

/**
 * Inline ImageVector for the "repeat" glyph — two stacked arrows. Defined here to
 * avoid pulling in the full `material-icons-extended` artifact for a single icon.
 * Path data adapted from the Material Symbols `repeat` icon.
 */
private val RepeatIcon: ImageVector = ImageVector.Builder(
    name = "Repeat",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(7f, 7f)
        horizontalLineTo(17f)
        verticalLineTo(10f)
        lineToRelative(4f, -4f)
        lineToRelative(-4f, -4f)
        verticalLineTo(5f)
        horizontalLineTo(5f)
        verticalLineTo(11f)
        horizontalLineTo(7f)
        verticalLineTo(7f)
        close()
        moveTo(17f, 17f)
        horizontalLineTo(7f)
        verticalLineTo(14f)
        lineToRelative(-4f, 4f)
        lineToRelative(4f, 4f)
        verticalLineTo(19f)
        horizontalLineTo(19f)
        verticalLineTo(13f)
        horizontalLineTo(17f)
        verticalLineTo(17f)
        close()
    }
}.build()
