package com.quiqs.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quiqs.shared.Priority
import com.quiqs.shared.QFilterSort
import org.jetbrains.compose.resources.painterResource
import quiqsai.client.generated.resources.Res
import quiqsai.client.generated.resources.x

/**
 * Bottom sheet for editing a [QFilterSort] spec by hand. Mirrors [QDetailsBottomSheet]
 * visually (same scrim, same [SheetSection] grouping) but holds the draft locally —
 * field edits only update the local draft and the screen's filter doesn't change until
 * the user taps Apply. Dismissing via scrim/back drops the draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QFilterSortBottomSheet(
    current: QFilterSort,
    availableTags: List<String>,
    onApply: (QFilterSort) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        scrimColor = SheetScrimColor,
        dragHandle = null,
        shape = RectangleShape,
        contentWindowInsets = { WindowInsets.navigationBars.only(WindowInsetsSides.Bottom) },
    ) {
        QFilterSortContent(
            current = current,
            availableTags = availableTags,
            onApply = onApply,
            onClose = onDismiss,
        )
    }
}

/**
 * Sheet body, factored out so previews don't need a real Window. The draft is seeded
 * from [current] and re-seeds whenever a different spec is passed in.
 */
@Composable
private fun QFilterSortContent(
    current: QFilterSort,
    availableTags: List<String>,
    onApply: (QFilterSort) -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SheetSection(
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = 4.dp,
                bottomEnd = 4.dp,
            ),
        ) {
            FilterSortHeader(
                canClear = !draft.isDefault,
                onClear = { draft = QFilterSort() },
                onClose = onClose,
            )
        }

        SheetSection(shape = RoundedCornerShape(4.dp)) {
            Column(
                modifier = Modifier.padding(
                    horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp,
                    vertical = 14.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                FilterField(label = "Search") {
                    SearchField(
                        value = draft.text.orEmpty(),
                        onChange = { v -> draft = draft.copy(text = v.ifBlank { null }) },
                    )
                }
                FilterField(label = "Priority") {
                    ChipFlow {
                        Priority.entries.forEach { p ->
                            FilterPill(
                                label = p.wire.replaceFirstChar { it.uppercase() },
                                selected = p in draft.priorities,
                                onClick = {
                                    draft = draft.copy(
                                        priorities = if (p in draft.priorities) draft.priorities - p
                                                     else draft.priorities + p,
                                    )
                                },
                            )
                        }
                    }
                }
                if (availableTags.isNotEmpty()) {
                    FilterField(label = "Tags") {
                        ChipFlow {
                            availableTags.forEach { tag ->
                                FilterPill(
                                    label = tag,
                                    selected = tag in draft.tags,
                                    onClick = {
                                        draft = draft.copy(
                                            tags = if (tag in draft.tags) draft.tags - tag
                                                   else draft.tags + tag,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                FilterField(label = "Alarm") {
                    ChipFlow {
                        ALARM_FILTER_LABELS.forEach { (value, label) ->
                            FilterPill(
                                label = label,
                                selected = draft.alarm == value,
                                onClick = { draft = draft.copy(alarm = value) },
                            )
                        }
                    }
                }
                FilterField(label = "When") {
                    ChipFlow {
                        ALARM_WINDOW_LABELS.forEach { (value, label) ->
                            FilterPill(
                                label = label,
                                selected = draft.alarmWindow == value,
                                onClick = { draft = draft.copy(alarmWindow = value) },
                            )
                        }
                    }
                }
                FilterField(label = "Status") {
                    ChipFlow {
                        COMPLETED_LABELS.forEach { (value, label) ->
                            FilterPill(
                                label = label,
                                selected = draft.completed == value,
                                onClick = { draft = draft.copy(completed = value) },
                            )
                        }
                    }
                }
            }
        }

        SheetSection(
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 4.dp,
                bottomStart = 14.dp,
                bottomEnd = 14.dp,
            ),
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp,
                    vertical = 14.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FieldLabel("Sort by")
                    Spacer(modifier = Modifier.weight(1f))
                    DirectionToggle(
                        direction = draft.sortDir,
                        onToggle = {
                            draft = draft.copy(
                                sortDir = if (draft.sortDir == QFilterSort.SortDir.ASC) QFilterSort.SortDir.DESC
                                          else QFilterSort.SortDir.ASC,
                            )
                        },
                    )
                }
                ChipFlow {
                    SORT_FIELD_LABELS.forEach { (value, label) ->
                        FilterPill(
                            label = label,
                            selected = draft.sortBy == value,
                            onClick = { draft = draft.copy(sortBy = value) },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { onApply(draft) }) {
                Text(
                    text = "Apply",
                    color = Color(0xFFEDEDED),
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun FilterSortHeader(
    canClear: Boolean,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SHEET_SECTION_HORIZONTAL_PADDING.dp - 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                painter = painterResource(Res.drawable.x),
                contentDescription = "Close",
                tint = Color(0xFFEDEDED),
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Filter & Sort",
            color = Color(0xFFEDEDED),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            enabled = canClear,
            onClick = onClear,
        ) {
            Text(
                text = "Clear",
                color = if (canClear) Color(0xFFEDEDED) else Color(0xFF555555),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun FilterField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label)
        content()
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = Color(0xFFB8B8B8),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = Color(0xFF161616)
    val fg = if (selected) Color(0xFFEDEDED) else Color(0xFF8893A1)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20))
            .background(bg)
            .then(
                if (selected) Modifier.border(1.dp, Color(0xFFEDEDED), RoundedCornerShape(20))
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = fg, fontSize = 13.sp)
    }
}

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = TextStyle(
            color = Color(0xFFEDEDED),
            fontSize = 14.sp,
        ),
        cursorBrush = SolidColor(Color(0xFFEDEDED)),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161616), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = "Search text or tags",
                        color = Color(0xFF666666),
                        fontSize = 14.sp,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun DirectionToggle(
    direction: QFilterSort.SortDir,
    onToggle: () -> Unit,
) {
    val label = when (direction) {
        QFilterSort.SortDir.ASC -> "Asc"
        QFilterSort.SortDir.DESC -> "Desc"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20))
            .background(Color(0xFF161616))
            .border(1.dp, Color(0xFFEDEDED), RoundedCornerShape(20))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = Color(0xFFEDEDED), fontSize = 12.sp)
    }
}

private val ALARM_FILTER_LABELS = listOf(
    QFilterSort.AlarmFilter.ANY to "Any",
    QFilterSort.AlarmFilter.NONE to "None",
    QFilterSort.AlarmFilter.ANY_SET to "Any set",
    QFilterSort.AlarmFilter.RECURRING to "Recurring",
    QFilterSort.AlarmFilter.ONE_OFF to "One-off",
    QFilterSort.AlarmFilter.GEOFENCE to "Geofence",
)

private val ALARM_WINDOW_LABELS = listOf(
    QFilterSort.AlarmWindow.ANY to "Any",
    QFilterSort.AlarmWindow.TODAY to "Today",
    QFilterSort.AlarmWindow.TOMORROW to "Tomorrow",
    QFilterSort.AlarmWindow.THIS_WEEK to "This week",
    QFilterSort.AlarmWindow.NEXT_WEEK to "Next week",
)

private val COMPLETED_LABELS = listOf(
    QFilterSort.CompletedFilter.ANY to "Any",
    QFilterSort.CompletedFilter.ONLY_OPEN to "Open",
    QFilterSort.CompletedFilter.ONLY_DONE to "Done",
)

private val SORT_FIELD_LABELS = listOf(
    QFilterSort.SortField.CREATED_AT to "Created",
    QFilterSort.SortField.UPDATED_AT to "Updated",
    QFilterSort.SortField.ALARM to "Alarm",
    QFilterSort.SortField.PRIORITY to "Priority",
    QFilterSort.SortField.TAGS to "Tags",
)
