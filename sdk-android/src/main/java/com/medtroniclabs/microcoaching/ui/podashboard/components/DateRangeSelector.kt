package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceNavy
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val FieldBorder = Color(0xFFE0E3EA)
private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().format(dateFormat)

private enum class RangeField { FROM, TO }

/**
 * From – To date-range filter for the PO dashboard (replaces the old Weekly/Monthly toggle).
 * Two tappable date fields; each opens a single-date picker. The range is kept ordered
 * (from ≤ to) — picking a From after To pushes To to match, and vice-versa.
 *
 * Millis are UTC start-of-day, matching Material's [DatePicker] convention.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeSelector(
    fromMillis: Long,
    toMillis: Long,
    onRangeChange: (fromMillis: Long, toMillis: Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var editing by remember { mutableStateOf<RangeField?>(null) }

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateField(
            label = stringResource(R.string.po_date_from),
            millis = fromMillis,
            onClick = { editing = RangeField.FROM },
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
        DateField(
            label = stringResource(R.string.po_date_to),
            millis = toMillis,
            onClick = { editing = RangeField.TO },
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
    }

    editing?.let { field ->
        val initial = if (field == RangeField.FROM) fromMillis else toMillis
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { editing = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { picked ->
                        when (field) {
                            RangeField.FROM -> onRangeChange(picked, maxOf(picked, toMillis))
                            RangeField.TO -> onRangeChange(minOf(fromMillis, picked), picked)
                        }
                    }
                    editing = null
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun DateField(
    label: String,
    millis: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, FieldBorder, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MutedText)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.CalendarMonth,
                contentDescription = null,
                tint = SpiceBlue,
                modifier = Modifier.size(16.dp),
            )
            Text(text = formatDate(millis), style = MaterialTheme.typography.bodyMedium, color = SpiceNavy)
        }
    }
}
