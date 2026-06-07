package com.quiqs.client.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quiqs.client.LocalThemeIsDark
import com.quiqs.shared.Priority
import com.quiqs.shared.Q
import com.quiqs.shared.QOccurrence
import com.quiqs.shared.occurrencesOn
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Vertical 24-hour timeline scoped to a single calendar date. Renders one row per hour;
 * Qs sit inside the hour their alarm fires in (multiple per hour stack vertically and
 * grow the slot). Date navigation lives in the header.
 *
 * The screen is stateless w.r.t. the selected date — caller owns the [LocalDate] and
 * passes back via [onSelectDate] when the user steps forward/back or jumps to today.
 *
 * Pre-filtered [qs] is the same projection the List view consumes; this composable
 * doesn't re-apply filters.
 */
@Composable
fun QDayView(
    qs: List<Q>,
    date: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onTapQ: (Q) -> Unit,
    onToggleCompleted: (Q, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val tz = remember { TimeZone.currentSystemDefault() }
    val today = remember(tz) { Clock.System.now().toLocalDateTime(tz).date }
    val occurrences = remember(qs, date, tz) { qs.occurrencesOn(date, tz) }
    val byHour = remember(occurrences) {
        // Bucket occurrences by their local hour so the per-hour row only iterates its
        // own list. Hours with no occurrences won't appear as keys; getOrDefault gives
        // the empty list.
        occurrences.groupBy { it.at.toLocalDateTime(tz).hour }
    }

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        DayHeader(
            date = date,
            today = today,
            onPrev = { onSelectDate(date.minus(1, DateTimeUnit.DAY)) },
            onNext = { onSelectDate(date.plus(1, DateTimeUnit.DAY)) },
            onJumpToday = { onSelectDate(today) },
        )

        // Land the user in the current hour minus 1 when first viewing today, so the
        // active hour has some context above it. For non-today dates start at the top.
        val nowHour = remember(date, tz) {
            if (date == today) Clock.System.now().toLocalDateTime(tz).hour else 0
        }
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = (nowHour - 1).coerceAtLeast(0),
        )
        // If the user jumps back to today after browsing forward, re-anchor on the
        // current hour. Skipped on the very first composition (handled by initial idx).
        LaunchedEffect(date) {
            if (date == today) {
                listState.scrollToItem((nowHour - 1).coerceAtLeast(0))
            } else {
                listState.scrollToItem(0)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding,
        ) {
            items(24, key = { it }) { hour ->
                HourRow(
                    hour = hour,
                    occurrences = byHour[hour].orEmpty(),
                    tz = tz,
                    onTapQ = onTapQ,
                    onToggleCompleted = onToggleCompleted,
                )
            }
        }
    }
}

/**
 * Top bar for [QDayView]: chevron-left / centered date label / chevron-right, with a
 * compact "Today" pill at the trailing edge that only appears when the user is off
 * today. The whole row sits flush against the screen edges to maximise hit-area on
 * the chevrons.
 */
@Composable
private fun DayHeader(
    date: LocalDate,
    today: LocalDate,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJumpToday: () -> Unit,
) {
    val isDark = LocalThemeIsDark.current
    val titleColor = if (isDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)
    val chevronTint = if (isDark) Color(0xFFB3B3B3) else Color(0xFF3A3A3A)
    val pillBg = if (isDark) Color(0xFF161616) else Color(0xFFE6E6E6)
    val pillFg = if (isDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) {
            Text(
                text = "‹", // single left-pointing angle quotation mark
                color = chevronTint,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = headerLabel(date, today),
                color = titleColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        IconButton(onClick = onNext) {
            Text(
                text = "›",
                color = chevronTint,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (date != today) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(pillBg)
                    .clickable { onJumpToday() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(text = "Today", color = pillFg, fontSize = 13.sp)
            }
        }
    }
}

/**
 * One hour row in the timeline. Left gutter ([HOUR_GUTTER_WIDTH]) shows the hour
 * label ("6 AM", "12 PM", …); the rest of the row is either empty (just a top divider)
 * or stacks the [occurrences] vertically inside the slot.
 *
 * Minimum height is [HOUR_ROW_MIN_HEIGHT] so empty hours read as real space; the row
 * grows beyond that when multiple Qs sit in the same hour.
 */
@Composable
private fun HourRow(
    hour: Int,
    occurrences: List<QOccurrence>,
    tz: TimeZone,
    onTapQ: (Q) -> Unit,
    onToggleCompleted: (Q, Boolean) -> Unit,
) {
    val isDark = LocalThemeIsDark.current
    val gutterColor = if (isDark) Color(0xFF6E6E6E) else Color(0xFF8A8A8A)
    val dividerColor = if (isDark) Color(0xFF1F1F1F) else Color(0xFFE0E0E0)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HOUR_ROW_MIN_HEIGHT),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .width(HOUR_GUTTER_WIDTH)
                .padding(top = 4.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // The first hour (midnight) is the only one that benefits from showing
            // both number and meridiem since "0 AM" is unusual; we render "12 AM" for
            // it (matching standard 12h notation).
            Text(
                text = hourLabel(hour),
                color = gutterColor,
                fontSize = 11.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 0.dp, end = 12.dp, top = 0.dp, bottom = 0.dp),
        ) {
            // Hairline at the top so the hour grid still reads as a grid even when
            // the slot is empty.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(dividerColor),
            )
            if (occurrences.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (occurrence in occurrences) {
                        DayQRow(
                            occurrence = occurrence,
                            tz = tz,
                            onTap = { onTapQ(occurrence.q) },
                            onToggleCompleted = { c -> onToggleCompleted(occurrence.q, c) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact Q row inside an hour slot. Left to right: round checkbox, minute mark
 * (`:30` or `:00`), title (truncated to a single line), trailing flow of priority
 * + tag badges. Tap opens the details sheet via [onTap].
 */
@Composable
private fun DayQRow(
    occurrence: QOccurrence,
    tz: TimeZone,
    onTap: () -> Unit,
    onToggleCompleted: (Boolean) -> Unit,
) {
    val isDark = LocalThemeIsDark.current
    val cardContainer = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val cardOutline = if (isDark) Color(0xFF262626) else Color(0xFFC4C4C4)
    val minuteColor = if (isDark) Color(0xFF8A8A8A) else Color(0xFF6E6E6E)
    val titleColor = if (isDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)
    val local = occurrence.at.toLocalDateTime(tz)
    val q = occurrence.q

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() },
        shape = RoundedCornerShape(10.dp),
        color = cardContainer,
        border = BorderStroke(1.dp, cardOutline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QCheckbox(
                checked = q.completed,
                onCheckedChange = onToggleCompleted,
            )
            Text(
                text = ":${local.minute.toString().padStart(2, '0')}",
                color = minuteColor,
                fontSize = 12.sp,
            )
            val titleText = if (q.text.isBlank() && q.voiceNotePath != null) "Voice note" else q.text
            Text(
                text = titleText,
                color = titleColor,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            val priorityBadge = when (q.priority) {
                Priority.HIGH -> q.priority.wire to QBadgeTone.High
                Priority.LOW -> q.priority.wire to QBadgeTone.Low
                Priority.MEDIUM -> null
            }
            priorityBadge?.let { (label, tone) -> QBadge(label = label, tone = tone) }
            // Show only the first tag to keep the row compact; the details sheet
            // surfaces the full list. A "+N" hint signals overflow.
            q.tags.firstOrNull()?.let { tag ->
                QBadge(label = tag, tone = QBadgeTone.Neutral)
                if (q.tags.size > 1) {
                    Text(
                        text = "+${q.tags.size - 1}",
                        color = minuteColor,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

private val HOUR_ROW_MIN_HEIGHT = 60.dp
private val HOUR_GUTTER_WIDTH = 48.dp

private fun headerLabel(date: LocalDate, today: LocalDate): String {
    return when (val days = today.daysUntil(date)) {
        0 -> "Today · ${weekdayShort(date.dayOfWeek)}, ${monthShort(date.month)} ${date.day}"
        1 -> "Tomorrow · ${monthShort(date.month)} ${date.day}"
        -1 -> "Yesterday · ${monthShort(date.month)} ${date.day}"
        else -> {
            val withinYear = days in -180..180 && date.year == today.year
            if (withinYear) "${weekdayShort(date.dayOfWeek)}, ${monthShort(date.month)} ${date.day}"
            else "${weekdayShort(date.dayOfWeek)}, ${monthShort(date.month)} ${date.day}, ${date.year}"
        }
    }
}

private fun hourLabel(hour: Int): String {
    val h12 = ((hour + 11) % 12) + 1
    val suffix = if (hour < 12) "AM" else "PM"
    return "$h12 $suffix"
}

private fun weekdayShort(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "Mon"
    DayOfWeek.TUESDAY -> "Tue"
    DayOfWeek.WEDNESDAY -> "Wed"
    DayOfWeek.THURSDAY -> "Thu"
    DayOfWeek.FRIDAY -> "Fri"
    DayOfWeek.SATURDAY -> "Sat"
    DayOfWeek.SUNDAY -> "Sun"
}

private fun monthShort(month: Month): String = when (month) {
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
