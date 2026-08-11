package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer

/** Hero card aspect ratio — a wide banner matching the module design mock. */
private const val HeroAspectRatio = 1.85f

/**
 * Full-width hero card for the featured refresher module — the module's artwork fills the
 * card with a "NEW" pill overlaid top-start and a "Start module" pill overlaid bottom-start,
 * matching the module design mock (SDK colours). Used at the top of
 * [com.medtroniclabs.microcoaching.ui.coaching.RefresherSubTab].
 *
 * The thumbnail falls back to a [SpiceBlue] gradient when [LearnModule.thumbnailUrl] is null
 * (same idiom as [TrainingCard]); a soft bottom scrim keeps the overlays legible over any
 * artwork.
 *
 * @param module The featured refresher module to present.
 * @param onStart Invoked when the Start-module button is tapped.
 */
@Composable
fun RefresherHeroCard(
    module: LearnModule,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(HeroAspectRatio),
        ) {
            ModuleThumbnail(
                thumbnailUrl = module.thumbnailUrl,
                contentDescription = module.title,
                modifier = Modifier.fillMaxSize(),
                fallback = {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(SpiceBlueContainer, SpiceBlue.copy(alpha = 0.3f)),
                                ),
                            ),
                    )
                },
            )
            // Soft bottom scrim so the badge + button stay legible over any artwork.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, SpiceBlue.copy(alpha = 0.28f)),
                        ),
                    ),
            )
            HeroNewBadge(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )
            StartModuleButton(
                onClick = onStart,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            )
        }
    }
}

/** Blue "Start module" pill with a trailing circular arrow, overlaid on the hero artwork. */
@Composable
private fun StartModuleButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(SpiceBlue)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.refresher_hero_start_module),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = SpiceBlue,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Small red "NEW" pill overlaid on the hero banner. Same shape/colour idiom as
 * `CriticalBadge` in RefresherTile.kt (4.dp rounded corners, solid red fill,
 * bold white label) — kept file-local since the label text differs.
 */
@Composable
private fun HeroNewBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFB91C1C))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.badge_new),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}
