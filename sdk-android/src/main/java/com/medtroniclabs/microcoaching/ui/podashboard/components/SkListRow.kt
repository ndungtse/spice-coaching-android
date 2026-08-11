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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.AvatarCircle
import com.medtroniclabs.microcoaching.ui.podashboard.SkSummary

/** My-SKs list row: status avatar · name · status chip · progress · x/N modules · chevron. */
@Composable
fun SkListRow(sk: SkSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fraction = if (sk.modulesTotal > 0) sk.modulesDone.toFloat() / sk.modulesTotal else 0f
    Row(
        modifier = modifier.fillMaxWidth().poCard().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarCircle(sk.name, size = 44.dp, containerColor = statusBg(sk.status), contentColor = statusFg(sk.status))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sk.name,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(sk.status)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50)),
                    color = statusFg(sk.status),
                    trackColor = ProgressTrack,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.po_modules_fraction, sk.modulesDone, sk.modulesTotal),
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MutedText)
    }
}
