package com.medtroniclabs.microcoaching.ui.leaderboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.leaderboard.LeaderboardGroup

private val HeaderMuted = com.medtroniclabs.microcoaching.ui.theme.MutedText

/** "Dhamrai Upazila · 28 SKs" on the left, "Updated 12:00 AM" on the right. */
@Composable
fun LeaderboardGroupHeader(group: LeaderboardGroup, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.leaderboard_group_meta, group.name, group.memberCount),
            style = MaterialTheme.typography.labelSmall,
            color = HeaderMuted,
        )
        Text(
            text = stringResource(R.string.leaderboard_updated, group.updatedLabel),
            style = MaterialTheme.typography.labelSmall,
            color = HeaderMuted,
        )
    }
}
