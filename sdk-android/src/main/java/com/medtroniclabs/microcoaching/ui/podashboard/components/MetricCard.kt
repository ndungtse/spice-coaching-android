package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
 * renamed "Non-Responsive SKs" is too long to wrap gracefully in a tile. Tappable → its
 * drill-down.
 */
@Composable
fun MetricCard(metric: PoMetric, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val alert = metric.key == MetricKey.INACTIVE
    val iconBg = if (alert) StatusRedBg else SpiceBlueContainer
    val iconTint = if (alert) StatusRed else SpiceBlue
    val valueColor = if (alert) StatusRed else MaterialTheme.colorScheme.onBackground

    Box(
        modifier = modifier
            .size(MetricCardSize)
            .poCard()
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        // (i) stays in the top-right corner, enlarged for legibility.
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MutedText,
            modifier = Modifier.align(Alignment.TopEnd).size(18.dp),
        )

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
