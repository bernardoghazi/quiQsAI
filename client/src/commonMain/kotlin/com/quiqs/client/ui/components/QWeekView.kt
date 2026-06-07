package com.quiqs.client.ui.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quiqs.client.LocalThemeIsDark
import com.quiqs.shared.Q
import com.quiqs.shared.occurrencesOn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Sectioned weekly timeline — one section per day, Monday → Sunday. Each section has
 * a tappable header that drills into Day view at that date (via [onOpenDay]) and a
 * vertical list of [QOccurrenceCard]s for the Qs whose alarms fire that day. Empty
 * days render just a muted header so the week's rhythm is visible at a glance without
 * "no Qs" noise.
 *
 * [weekStart] must be the Monday of the displayed week. The caller owns this state;
 * stepping a week forward/back is `weekStart ± 7 days`.
 *
 * Pre-filtered [qs] is the same projection the List view consumes; this composable
 * doesn't re-apply filters.
 */
@Composable
fun QWeekView(
    qs: List<Q>,
    weekStart: LocalDate,
    onSelectWeek: (LocalDate) -> Unit,
    onTapQ: (Q) -> Unit,
    onToggleCompleted: (Q, Boolean) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val tz = remember { TimeZone.currentSystemDefault() }
    val today = remember(tz) { Clock.System.now().toLocalDateTime(tz).date }
    val thisWeekStart = remember(today) {
        today.minus(today.dayOfWeek.daysFromMonday(), DateTimeUnit.DAY)
    }
    val weekDates = remember(weekStart) {
        (0..6).map { weekStart.plus(it, DateTimeUnit.DAY) }
    }
    val occurrencesByDate = remember(qs, weekDates, tz) {
        weekDates.associateWith { date -> qs.occurrencesOn(date, tz) }
    }
    var showDatePicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        WeekHeader(
            weekStart = weekStart,
            weekEnd = weekDates.last(),
            isThisWeek = weekStart == thisWeekStart,
            onPrev = { onSelectWeek(weekStart.minus(7, DateTimeUnit.DAY)) },
            onNext = { onSelectWeek(weekStart.plus(7, DateTimeUnit.DAY)) },
            onJumpThisWeek = { onSelectWeek(thisWeekStart) },
            onTapLabel = { showDatePicker = true },
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (date in weekDates) {
                val dailyOccurrences = occurrencesByDate[date].orEmpty()
                item(key = "header:$date") {
                    DaySectionHeader(
                        date = date,
                        today = today,
                        hasContent = dailyOccurrences.isNotEmpty(),
                        onClick = { onOpenDay(date) },
                    )
                }
                items(
                    items = dailyOccurrences,
                    // The (q.id, instant) pair is unique within the week — recurring
                    // alarms can produce several entries for the same Q on the same day,
                    // each with a distinct firing instant.
                    key = { "${date}:${it.q.id}:${it.at}" },
                ) { occurrence ->
                    QOccurrenceCard(
                        q = occurrence.q,
                        timeLabel = formatTimeShort(occurrence.at, tz),
                        onTap = { onTapQ(occurrence.q) },
                        onToggleCompleted = { c -> onToggleCompleted(occurrence.q, c) },
                        modifier = Modifier.padding(start = DAY_SECTION_INDENT),
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        WeekDatePicker(
            initial = weekStart,
            onDismiss = { showDatePicker = false },
            onConfirm = { picked ->
                // Snap to the Monday of the picked date's week so the user doesn't
                // need to know which day starts our week.
                val pickedMonday = picked.minus(picked.dayOfWeek.daysFromMonday(), DateTimeUnit.DAY)
                onSelectWeek(pickedMonday)
                showDatePicker = false
            },
        )
    }
}

/**
 * Top bar for [QWeekView]: chevron-left / centered "Jun 8 – Jun 14" / chevron-right,
 * with a "This Week" pill at the trailing edge that only appears when the displayed
 * week isn't the current one.
 */
@Composable
private fun WeekHeader(
    weekStart: LocalDate,
    weekEnd: LocalDate,
    isThisWeek: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJumpThisWeek: () -> Unit,
    onTapLabel: () -> Unit,
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
            Text(text = "‹", color = chevronTint, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onTapLabel() }
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = weekRangeLabel(weekStart, weekEnd),
                color = titleColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        IconButton(onClick = onNext) {
            Text(text = "›", color = chevronTint, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        if (!isThisWeek) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(pillBg)
                    .clickable { onJumpThisWeek() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(text = "This week", color = pillFg, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Per-day section header — weekday + month/day label, tappable to drill into Day view
 * at that date. Empty days render muted so dense weeks read cleanly. Today's header
 * gets a subtle orange tint to mark it.
 */
@Composable
private fun DaySectionHeader(
    date: LocalDate,
    today: LocalDate,
    hasContent: Boolean,
    onClick: () -> Unit,
) {
    val isDark = LocalThemeIsDark.current
    val activeColor = if (isDark) Color(0xFFEDEDED) else Color(0xFF1A1A1A)
    val mutedColor = if (isDark) Color(0xFF666666) else Color(0xFF8A8A8A)
    val todayAccent = Color(0xFFD85A00)

    val labelColor = when {
        date == today -> todayAccent
        hasContent -> activeColor
        else -> mutedColor
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = if (hasContent) 8.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dayHeaderLabel(date),
            color = labelColor,
            fontSize = if (hasContent) 14.sp else 12.sp,
            fontWeight = if (hasContent) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "›",
            color = mutedColor,
            fontSize = 16.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekDatePicker(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    // UTC bridge — see DayDatePicker for rationale.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atTime(0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { ms ->
                    onConfirm(Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date)
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

private val DAY_SECTION_INDENT = 8.dp

/** ISO-style: Monday = 0, … Sunday = 6. Used to snap to the week's start. */
private fun DayOfWeek.daysFromMonday(): Int = isoDayNumber - 1

private fun weekRangeLabel(start: LocalDate, end: LocalDate): String {
    val startStr = "${monthShort(start.month)} ${start.day}"
    val endStr = if (start.month == end.month) {
        "${end.day}"
    } else {
        "${monthShort(end.month)} ${end.day}"
    }
    val needsYear = start.year != end.year
    val suffix = if (needsYear) ", ${end.year}" else ""
    return "$startStr – $endStr$suffix"
}

private fun dayHeaderLabel(date: LocalDate): String =
    "${weekdayShort(date.dayOfWeek)}, ${monthShort(date.month)} ${date.day}"

private fun formatTimeShort(at: Instant, tz: TimeZone): String {
    val local = at.toLocalDateTime(tz)
    val h12 = ((local.hour + 11) % 12) + 1
    val mm = local.minute.toString().padStart(2, '0')
    val suffix = if (local.hour < 12) "AM" else "PM"
    return "$h12:$mm $suffix"
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
