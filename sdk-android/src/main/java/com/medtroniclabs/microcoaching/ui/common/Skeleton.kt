package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

/**
 * Reusable skeleton (shimmer) placeholders for list screens, shaped like the real
 * tiles so a loading screen reads as the content that's about to appear rather
 * than a bare spinner. Dependency-free (theme + Compose only) so any screen can
 * compose them; callers pair them with the real [SectionHeader] so section titles
 * show instantly while only the tiles shimmer.
 *
 * The animation reuses the same `rememberInfiniteTransition` idiom already used
 * elsewhere in the UI (e.g. RecordingBadge); a single shared brush drives every
 * placeholder on screen.
 */

/** An animated shimmer gradient sweeping across the placeholder shade. */
@Composable
private fun rememberShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = SHIMMER_TRAVEL,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeleton-x",
    )
    return Brush.linearGradient(
        colors = listOf(SurfaceMuted, HIGHLIGHT, SurfaceMuted),
        start = Offset(x - SHIMMER_BAND, 0f),
        end = Offset(x, 0f),
    )
}

/** A single shimmering block. Base building unit for every tile skeleton. */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    Box(modifier = modifier.clip(shape).background(rememberShimmerBrush()))
}

/**
 * Skeleton for the horizontal [com.medtroniclabs.microcoaching.ui.learn.modules.components.ModuleTile]
 * row (56dp thumbnail + two text lines + 40dp trailing circle). Used by the
 * AllModules grid.
 */
@Composable
fun ModuleTileSkeleton(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SkeletonBox(Modifier.size(56.dp), RoundedCornerShape(12.dp))
            Column(
                modifier = Modifier.weight(1f).padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonBox(Modifier.fillMaxWidth(0.7f).height(16.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.4f).height(12.dp))
            }
            SkeletonBox(Modifier.size(40.dp), CircleShape)
        }
    }
}

/**
 * Skeleton for a vertical [com.medtroniclabs.microcoaching.ui.learn.modules.components.TrainingCard]
 * (220dp tall, 110dp thumbnail, title + meta + progress bar).
 */
@Composable
fun TrainingCardSkeleton(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(220.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            SkeletonBox(
                Modifier.fillMaxWidth().height(110.dp),
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonBox(Modifier.fillMaxWidth(0.85f).height(14.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.5f).height(12.dp))
                Box(Modifier.weight(1f))
                SkeletonBox(Modifier.fillMaxWidth().height(6.dp))
            }
        }
    }
}

/**
 * Skeleton for a vertical [com.medtroniclabs.microcoaching.ui.learn.modules.components.KnowledgeCard]
 * (200dp tall, 96dp thumbnail, title + footer row).
 */
@Composable
fun KnowledgeCardSkeleton(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(200.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            SkeletonBox(
                Modifier.fillMaxWidth().height(96.dp),
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonBox(Modifier.fillMaxWidth(0.85f).height(14.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.5f).height(12.dp))
                Box(Modifier.weight(1f))
                SkeletonBox(Modifier.fillMaxWidth(0.35f).height(12.dp))
            }
        }
    }
}

/**
 * Horizontal row of [count] vertical-card skeletons, matching the width/spacing
 * of [TrainingRow] / [KnowledgeRow]. Non-scrolling (placeholders don't need it).
 */
@Composable
fun CardRowSkeleton(
    knowledge: Boolean = false,
    count: Int = 3,
    modifier: Modifier = Modifier,
    tileWidth: Dp = 150.dp,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(count) {
            if (knowledge) KnowledgeCardSkeleton(Modifier.width(tileWidth))
            else TrainingCardSkeleton(Modifier.width(tileWidth))
        }
    }
}

/** Vertical stack of [count] [ModuleTileSkeleton]s — for the refresher section. */
@Composable
fun ModuleTileListSkeleton(
    count: Int = 2,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(count) { ModuleTileSkeleton() }
    }
}

private const val SHIMMER_TRAVEL = 1_000f
private const val SHIMMER_BAND = 300f
private val HIGHLIGHT = SpiceBlueContainer.copy(alpha = 0.55f)
