package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.podashboard.SkStatus

/** Pill status label, coloured per [SkStatus]. */
@Composable
fun StatusChip(status: SkStatus, modifier: Modifier = Modifier) {
    val labelRes = when (status) {
        SkStatus.ACTIVE -> R.string.po_status_active
        SkStatus.NEEDS_ATTENTION -> R.string.po_status_needs_attention
        SkStatus.INACTIVE -> R.string.po_status_inactive
    }
    Text(
        text = stringResource(labelRes),
        color = statusFg(status),
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(statusBg(status))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
