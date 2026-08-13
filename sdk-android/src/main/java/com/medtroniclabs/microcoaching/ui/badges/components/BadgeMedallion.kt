package com.medtroniclabs.microcoaching.ui.badges.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.badges.AchievementBadge
import com.medtroniclabs.microcoaching.ui.badges.BadgeState
import com.medtroniclabs.microcoaching.ui.theme.MutedText
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceGreen

private val MedallionSize = 92.dp

/**
 * One badge tile in the Badges grid: the circular [BadgeArtwork] with a state marker, above
 * the badge's name (2 lines max).
 *
 * Earned badges get a green check, locked badges a lock — matching the greyscale/lock
 * treatment [BadgeArtwork] applies to the medallion itself.
 */
@Composable
fun BadgeMedallion(badge: AchievementBadge, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(MedallionSize)) {
            BadgeArtwork(
                imageUrl = badge.imageUrl,
                state = badge.state,
                contentDescription = badge.name,
                size = MedallionSize,
            )
            when (badge.state) {
                BadgeState.EARNED -> CornerMarker(
                    background = SpiceGreen,
                    icon = { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
                BadgeState.LOCKED -> CornerMarker(
                    background = Color(0xFFE4E8EF),
                    icon = { Icon(Icons.Filled.Lock, null, tint = MutedText, modifier = Modifier.size(14.dp)) },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
        Text(
            text = badge.name,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = when (badge.state) {
                BadgeState.EARNED -> MaterialTheme.colorScheme.onBackground
                BadgeState.LOCKED -> MutedText.copy(alpha = 0.8f)
            },
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small circular state marker overlaid at a medallion corner (earned check / locked lock). */
@Composable
private fun CornerMarker(
    background: Color,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(26.dp)
            .border(2.dp, Color.White, CircleShape)
            .background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) { icon() }
}
