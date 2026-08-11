package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val StreakOrange = Color(0xFFF57C00)

/** Small "🔥 Nd" streak indicator. */
@Composable
fun StreakChip(days: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text = "🔥", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(2.dp))
        Text(
            text = "${days}d",
            color = StreakOrange,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
