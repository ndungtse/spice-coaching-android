package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.podashboard.MetricKey
import com.medtroniclabs.microcoaching.ui.podashboard.PoMetric
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer

/** Fixed square size so all KPI cards match and their labels get a predictable width. */
private val MetricCardSize = 110.dp

/**
 * One KPI card: the (i) affordance is pinned top-right; the metric icon, value/total and
 * label form a single horizontally-centred group anchored to the bottom of the tile. A fixed
 * square (the parent scrolls horizontally), with the label forced to a single line — the
 * renamed "Non-Responsive SKs" is too long to wrap gracefully in a tile. Tapping the tile
 * opens its drill-down; tapping the (i) opens an explanatory bottom sheet instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricCard(metric: PoMetric, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val alert = metric.key == MetricKey.INACTIVE
    val iconBg = if (alert) StatusRedBg else SpiceBlueContainer
    val iconTint = if (alert) StatusRed else SpiceBlue
    val valueColor = if (alert) StatusRed else MaterialTheme.colorScheme.onBackground
    var showInfo by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(MetricCardSize)
            .poCard()
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        // (i) has its OWN clickable, so a tap here opens the info sheet and is consumed —
        // it never triggers the card's drill-down. The offset pushes the ~34dp tap target
        // (8dp padding around an 18dp glyph) out into the corner so the icon stays put while
        // the hit area grows — a small icon that's easy to hit and hard to miss.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 8.dp, y = (-8).dp)
                .clip(CircleShape)
                .clickable { showInfo = true }
                .padding(8.dp),
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = stringResource(R.string.po_metric_info_dismiss),
                tint = MutedText,
                modifier = Modifier.size(18.dp),
            )
        }

        // Metric icon · value/total · label — one centred stack pinned to the bottom.
        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(metricIcon(metric.key), contentDescription = null, tint = iconTint, modifier = Modifier.size(15.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${metric.value}",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    fontWeight = FontWeight.Bold,
                    color = valueColor,
                )
                Text(
                    text = "/${metric.total}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MutedText,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
            }
            Text(
                text = stringResource(metricLabel(metric.key)),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = MutedText,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (showInfo) {
        ModalBottomSheet(
            onDismissRequest = { showInfo = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(metricSheetTitle(metric.key)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                val full = stringResource(metricDescription(metric.key))
                val highlight = metricHighlight(metric.key)?.let { stringResource(it) }
                Text(
                    text = highlighted(full, highlight, SpiceBlue),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MutedText,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { showInfo = false },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SpiceBlue),
                ) {
                    Text(stringResource(R.string.po_metric_info_dismiss))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun metricIcon(key: MetricKey): ImageVector = when (key) {
    MetricKey.ACTIVE_NOW -> Icons.Filled.Groups
    MetricKey.INACTIVE -> Icons.Filled.WarningAmber
    MetricKey.FINISHED_MODULES -> Icons.AutoMirrored.Filled.MenuBook
    MetricKey.CHATBOT_ENGAGED -> Icons.Filled.ChatBubbleOutline
}

@StringRes
private fun metricLabel(key: MetricKey): Int = when (key) {
    MetricKey.ACTIVE_NOW -> R.string.po_metric_active_now
    MetricKey.INACTIVE -> R.string.po_metric_inactive
    MetricKey.FINISHED_MODULES -> R.string.po_metric_finished_modules
    MetricKey.CHATBOT_ENGAGED -> R.string.po_metric_chatbot_engaged
}

@StringRes
private fun metricDescription(key: MetricKey): Int = when (key) {
    MetricKey.ACTIVE_NOW -> R.string.po_metric_desc_active_now
    MetricKey.INACTIVE -> R.string.po_metric_desc_inactive
    MetricKey.FINISHED_MODULES -> R.string.po_metric_desc_finished_modules
    MetricKey.CHATBOT_ENGAGED -> R.string.po_metric_desc_chatbot_engaged
}

/** Sheet heading — Finished Modules gets its own title; the rest reuse the card label. */
@StringRes
private fun metricSheetTitle(key: MetricKey): Int = when (key) {
    MetricKey.FINISHED_MODULES -> R.string.po_metric_sheet_title_finished
    else -> metricLabel(key)
}

/** Phrase to highlight inside the description, or null for none. */
@StringRes
private fun metricHighlight(key: MetricKey): Int? = when (key) {
    MetricKey.FINISHED_MODULES -> R.string.po_metric_hl_finished
    MetricKey.CHATBOT_ENGAGED -> R.string.po_metric_hl_chatbot
    else -> null
}

/**
 * [full] with [phrase] styled in [color] (semibold). Falls back to plain text when the
 * phrase is absent — so a locale whose translation doesn't contain the exact substring
 * still renders the whole sentence, just unhighlighted.
 */
private fun highlighted(full: String, phrase: String?, color: Color): AnnotatedString =
    buildAnnotatedString {
        val p = phrase?.takeIf { it.isNotBlank() }
        val at = p?.let { full.indexOf(it) } ?: -1
        if (p == null || at < 0) {
            append(full)
        } else {
            append(full.substring(0, at))
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) {
                append(full.substring(at, at + p.length))
            }
            append(full.substring(at + p.length))
        }
    }
