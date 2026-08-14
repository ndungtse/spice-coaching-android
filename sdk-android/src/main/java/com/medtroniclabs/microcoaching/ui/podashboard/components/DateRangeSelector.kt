package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceNavy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val FieldBorder = Color(0xFFE0E3EA)
private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

private fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().format(dateFormat)

private enum class RangeField { FROM, TO }

/** Local today as UTC start-of-day millis — the convention the range itself uses. */
private fun todayUtcStartMillis(): Long =
    LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * Greys out dates after today: the dashboard aggregates recorded activity, so a future
 * window can only ever come back empty.
 *
 * "Today" is the device's local date, matching how `PODashboardViewModel` builds the
 * default range. Deriving it in UTC instead would grey out the user's own today for
 * zones ahead of UTC — including Bangladesh, for the first six hours of every day.
 * Read per call rather than cached so a session open across midnight still works.
 */
@OptIn(ExperimentalMaterial3Api::class)
private object PastAndTodayOnly : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        utcTimeMillis <= todayUtcStartMillis()

    override fun isSelectableYear(year: Int): Boolean =
        year <= LocalDate.now().year
}

/**
 * From – To date-range filter for the PO dashboard. Two tappable date fields; each opens
 * a single-date picker. The range is kept ordered (from ≤ to) — picking a From after To
 * pushes To to match, and vice-versa.
 *
 * Nothing later than today can be picked ([PastAndTodayOnly]); that applies to the typed
 * "pencil" entry too, which Material validates against the same rule.
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
    // Saveable: CoachingFlowActivity declares no `configChanges`, so a font-scale or
    // locale change recreates it and would drop the open dialog mid-edit.
    var editing by rememberSaveable { mutableStateOf<RangeField?>(null) }

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
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial,
            selectableDates = PastAndTodayOnly,
        )
        DatePickerDialog(
            // Input ("pencil") mode flickered on some devices: Material resizes this
            // window as its content height animates, while the platform resizes it for
            // the IME. decorFitsSystemWindows = false ends that tug-of-war;
            // safeDrawingPadding then does the bar + keyboard insetting an un-fitted
            // window no longer gets. usePlatformDefaultWidth is Material's own default
            // here, not an override.
            modifier = Modifier.safeDrawingPadding(),
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
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
