package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.AvatarCircle
import com.medtroniclabs.microcoaching.ui.podashboard.SkSummary

private val RefresherPurple = Color(0xFF7C3AED)
private val RefresherPurpleBg = Color(0xFFEDE9FE)

/** Per-SK refresher-completion row (PO-monitoring): avatar · name · x/N · purple bar. */
@Composable
fun RefresherCompletionRow(sk: SkSummary, modifier: Modifier = Modifier) {
    val fraction = if (sk.refreshersTotal > 0) sk.refreshersDone.toFloat() / sk.refreshersTotal else 0f
    Row(
        modifier = modifier.fillMaxWidth().poCard().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarCircle(sk.name, size = 40.dp, containerColor = RefresherPurpleBg, contentColor = RefresherPurple)
        Spacer(Modifier.width(12.dp))
        Text(sk.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(R.string.po_fraction, sk.refreshersDone, sk.refreshersTotal),
            color = RefresherPurple,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.width(8.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.width(80.dp).height(6.dp).clip(RoundedCornerShape(50)),
            color = RefresherPurple,
            trackColor = ProgressTrack,
        )
    }
}
