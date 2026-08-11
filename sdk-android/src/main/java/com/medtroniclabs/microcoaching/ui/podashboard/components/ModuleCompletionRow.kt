package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.AvatarCircle
import com.medtroniclabs.microcoaching.ui.podashboard.ModuleCompletion
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue

private val DividerColor = Color(0xFFEFEFF3)

/** Accordion row for one module's completion; expands to per-SK check rows. */
@Composable
fun ModuleCompletionRow(
    item: ModuleCompletion,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = if (item.total > 0) item.done.toFloat() / item.total else 0f
    val barColor = when {
        item.done >= item.total -> StatusGreen
        fraction > 0.5f -> SpiceBlue
        else -> StatusOrange
    }
    Column(modifier = modifier.fillMaxWidth().poCard()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.moduleName,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.po_fraction, item.done, item.total),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.width(64.dp).height(6.dp).clip(RoundedCornerShape(50)),
                color = barColor,
                trackColor = ProgressTrack,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MutedText,
            )
        }
        if (expanded) {
            item.perSk.forEach { check ->
                HorizontalDivider(color = DividerColor)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarCircle(
                        check.name,
                        size = 36.dp,
                        containerColor = if (check.done) StatusGreenBg else StatusRedBg,
                        contentColor = if (check.done) StatusGreen else StatusRed,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(check.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Icon(
                        imageVector = if (check.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = null,
                        tint = if (check.done) StatusGreen else MutedText,
                    )
                }
            }
        }
    }
}
