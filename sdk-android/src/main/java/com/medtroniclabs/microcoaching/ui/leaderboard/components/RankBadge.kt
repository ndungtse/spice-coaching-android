package com.medtroniclabs.microcoaching.ui.leaderboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val Gold = Color(0xFFFFC107)
private val Silver = Color(0xFFB0BEC5)
private val Bronze = Color(0xFFCD7F32)
private val RankMuted = com.medtroniclabs.microcoaching.ui.theme.MutedText

/** Top-3 ranks show a coloured medal; the rest show a plain number. */
@Composable
fun RankBadge(rank: Int, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    if (rank in 1..3) {
        val color = when (rank) {
            1 -> Gold
            2 -> Silver
            else -> Bronze
        }
        Box(
            modifier = modifier.size(size).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text("$rank", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        Text(
            text = "$rank",
            modifier = modifier,
            color = RankMuted,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
