package com.medtroniclabs.microcoaching.ui.leaderboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.AvatarCircle
import com.medtroniclabs.microcoaching.ui.common.StreakChip
import com.medtroniclabs.microcoaching.ui.leaderboard.LeaderboardEntry

private val PodiumXpMuted = com.medtroniclabs.microcoaching.ui.theme.MutedText

/** Top-3 podium laid out 2 · 1 · 3, bottom-aligned so the leader's card rises above the others. */
@Composable
fun LeaderboardPodium(top: List<LeaderboardEntry>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        top.getOrNull(1)?.let { PodiumColumn(it, Modifier.weight(1f), cardMinHeight = 168.dp, avatarSize = 56.dp) }
        top.getOrNull(0)?.let { PodiumColumn(it, Modifier.weight(1f), cardMinHeight = 212.dp, avatarSize = 76.dp) }
        top.getOrNull(2)?.let { PodiumColumn(it, Modifier.weight(1f), cardMinHeight = 168.dp, avatarSize = 56.dp) }
    }
}

@Composable
private fun RowScope.PodiumColumn(
    entry: LeaderboardEntry,
    modifier: Modifier = Modifier,
    cardMinHeight: Dp,
    avatarSize: Dp,
) {
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp) // room for the rank badge to overlap the card top
                .heightIn(min = cardMinHeight),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            shadowElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp, start = 8.dp, end = 8.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AvatarCircle(entry.displayName, imageRes = R.drawable.avatar, size = avatarSize)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (entry.isCurrentUser) stringResource(R.string.leaderboard_you) else entry.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.leaderboard_xp_value, "%,d".format(entry.xp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = PodiumXpMuted,
                )
                Spacer(Modifier.height(4.dp))
                StreakChip(entry.streakDays)
            }
        }
        RankBadge(entry.rank, size = 32.dp)
    }
}
