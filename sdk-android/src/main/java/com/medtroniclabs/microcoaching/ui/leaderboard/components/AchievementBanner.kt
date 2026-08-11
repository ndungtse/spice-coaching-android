package com.medtroniclabs.microcoaching.ui.leaderboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R

private val AchievementOrange = Color(0xFFF57C00)

/** Celebratory banner shown when the SK is ranked #1 in their group. */
@Composable
fun AchievementBanner(groupName: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AchievementOrange).padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.leaderboard_achievement_leading, groupName),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
