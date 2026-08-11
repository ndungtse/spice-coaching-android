package com.medtroniclabs.microcoaching.ui.badges.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark

/**
 * Full-width banner atop the Badges tab that opens the Your Journey path. Shows the learning
 * path's progress ("X of N milestones") and taps through via [onClick].
 *
 * Uses the same gradient-card idiom as
 * [com.medtroniclabs.microcoaching.ui.learn.modules.components.QuizRefresherCard]
 * (`Brush.*gradient(listOf(SpiceBlue, SpiceBlueDark))` clipped to a rounded card), here
 * horizontal, with a badge thumbnail on the leading edge and a chevron on the trailing edge.
 */
@Composable
fun YourJourneyBanner(
    earnedCount: Int,
    totalCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(SpiceBlue, SpiceBlueDark)))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.badge_full_collection),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .padding(4.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.badges_journey_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = stringResource(R.string.badges_journey_see_path) +
                    " · " +
                    stringResource(R.string.badges_milestones_count, earnedCount, totalCount),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White,
        )
    }
}
