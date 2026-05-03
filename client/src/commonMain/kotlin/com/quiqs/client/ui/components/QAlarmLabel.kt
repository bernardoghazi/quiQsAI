package com.quiqs.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.quiqs.shared.Alarm
import com.quiqs.shared.nextRecurringInstant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Shows the user-facing label for a single [Alarm], optionally prefixed with a repeat
 * icon when the alarm recurs.
 */
@Composable
fun QAlarmLabel(
    alarm: Alarm,
    modifier: Modifier = Modifier,
    color: Color = Color(0XFFB8B8B8),
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        QText(text = alarm.formatLabel(), role = QTextRole.Alarm, color = color)
        if (alarm is Alarm.RecurringTime) {
            Icon(
                imageVector = RepeatIcon,
                contentDescription = "Recurring",
                tint = Color(0XFF3F3F3F),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Renders the alarm as the same kind of short label the list cell uses. For recurring
 * alarms we show the next firing time only — the repeat icon next to it conveys that the
 * alarm recurs. Falls back to the raw rule string if we can't compute a next instant.
 */
private fun Alarm.formatLabel(): String = when (this) {
    is Alarm.OneOffTime -> formatRelativeDateTime(at)
    is Alarm.RecurringTime -> {
        val tz = TimeZone.currentSystemDefault()
        val next = nextRecurringInstant(rule, Clock.System.now(), tz)
        if (next != null) formatRelativeDateTime(next) else rule
    }

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
        daysDiff == 0L -> "Today"
        daysDiff == 1L -> "Tomorrow"
        daysDiff in 2L..6L -> "${atLocal.dayOfWeek.shortLabel()}, ${atLocal.month.shortLabel()} ${atLocal.day}"
        atLocal.year == nowLocal.year -> "${atLocal.month.shortLabel()} ${atLocal.day}"
        else -> "${atLocal.month.shortLabel()} ${atLocal.day}, ${atLocal.year}"
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
