package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.domain.refresher.RefresherKind
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.theme.CoachingTheme
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Soft background tints cycled across the Practice Zone row. Callers pick by position:
 * `practiceZonePalette[index % practiceZonePalette.size]`.
 *
 * Shares the theme's decorative category palette rather than carrying its own three
 * hardcoded tints, so a host restyling `categoryTags` restyles this row too.
 */
val practiceZonePalette: List<Color>
    @Composable @ReadOnlyComposable
    get() = CoachingTheme.colors.categoryTags.map { it.container }

/**
 * Display label for a [RefresherKind]. Shared by both refresher tiles so the Practice Zone
 * row and the see-all list can never name the same module differently. A null kind can only
 * reach a tile mid-recomposition, before the classifier has run; "Quiz" is the safe default
 * because every kind but LEARNING drills.
 */
@StringRes
internal fun refresherKindLabel(kind: RefresherKind?): Int = when (kind) {
    RefresherKind.MICROCOACHING -> R.string.refresher_type_microcoaching
    RefresherKind.LEARNING -> R.string.refresher_type_learning_card
    RefresherKind.QUIZ, null -> R.string.refresher_type_quiz
}

/**
 * Square practice/refresher tile for the horizontal "Practice Zone" row of
 * [com.medtroniclabs.microcoaching.ui.coaching.RefresherSubTab]: a soft [containerColor] fill
 * with a white type pill top-start, the title, and a circular arrow pinned bottom-end.
 *
 * @param module The refresher/practice module to present.
 * @param onClick Invoked when the whole card is tapped.
 * @param containerColor Soft card fill; cycle [practiceZonePalette] by position.
 */
@Composable
fun PracticeZoneCard(
    module: LearnModule,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = practiceZonePalette[0],
) {
    Card(
        onClick = onClick,
        // Square (1:1) tile for the horizontal Practice Zone row.
        modifier = modifier.width(160.dp).aspectRatio(1f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                     // hide the type tag for now
                    // TypeTag(module.refresherKind)
                    ContentDomainTag(module.contentDomain)
                }
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(38.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** White pill naming what the refresher will ask of the CHW. */
@Composable
private fun TypeTag(kind: RefresherKind?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(refresherKindLabel(kind)),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
            ),
        )
    }
}
