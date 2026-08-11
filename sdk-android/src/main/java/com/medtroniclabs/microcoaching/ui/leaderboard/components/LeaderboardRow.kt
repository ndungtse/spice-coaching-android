package com.medtroniclabs.microcoaching.ui.leaderboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.AvatarCircle
import com.medtroniclabs.microcoaching.ui.common.StreakChip
import com.medtroniclabs.microcoaching.ui.common.XpPill
import com.medtroniclabs.microcoaching.ui.leaderboard.LeaderboardEntry
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue

/** One ranked row: rank · avatar · name · streak · XP. The current user's row is highlighted. */
@Composable
fun LeaderboardRow(entry: LeaderboardEntry, modifier: Modifier = Modifier) {
    val name = if (entry.isCurrentUser) stringResource(R.string.leaderboard_you) else entry.displayName
    val background = if (entry.isCurrentUser) SpiceBlue else Color.White
    val foreground = if (entry.isCurrentUser) Color.White else MaterialTheme.colorScheme.onBackground
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 1.dp, shape = shape)
            .clip(shape)
            .background(background)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { RankBadge(entry.rank) }
        Spacer(Modifier.width(8.dp))
        AvatarCircle(entry.displayName, imageRes = R.drawable.avatar, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = foreground,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            StreakChip(entry.streakDays)
        }
        XpPill(entry.xp)
    }
}
