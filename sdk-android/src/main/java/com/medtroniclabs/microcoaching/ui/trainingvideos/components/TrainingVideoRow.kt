package com.medtroniclabs.microcoaching.ui.trainingvideos.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.theme.SpiceNavy
import com.medtroniclabs.microcoaching.ui.trainingvideos.TrainingVideo

/**
 * Compact list-row card for one training video in the "More videos" section: a
 * 16:9 thumbnail (play badge, watched-progress bar, duration pill) on the left,
 * then a tight title + meta column. Download lives on the featured card and the
 * player, keeping these rows clean. Falls back to a brand gradient when the video
 * has no thumbnail yet.
 */
@Composable
fun TrainingVideoRow(
    video: TrainingVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(132.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                VideoThumbnail(
                    thumbnailUrl = video.thumbnailUrl,
                    contentDescription = video.title,
                    modifier = Modifier.matchParentSize(),
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
                ThumbnailPlayBadge(modifier = Modifier.align(Alignment.Center))
                WatchedProgressBar(
                    fraction = video.progressFraction,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                DurationChip(
                    durationMs = video.durationMs,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SpiceNavy,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                video.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = Color(0xFF6B7280),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val meta = video.metaLabel()
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = Color(0xFF6B7280),
                    )
                }
            }
        }
    }
}

/**
 * Meta line under the title: "category · N min", or whichever half is known.
 *
 * Empty when neither is — a backend-assigned video has no category, and its
 * duration is absent until the media has been probed, so the line disappears
 * rather than rendering a stray separator.
 */
@Composable
internal fun TrainingVideo.metaLabel(): String {
    val cat = category?.takeIf { it.isNotBlank() }
    val dur = if (durationMin > 0) stringResource(R.string.training_videos_meta_duration, durationMin) else null
    return when {
        cat != null && dur != null -> stringResource(R.string.training_videos_meta, cat, durationMin)
        cat != null -> cat
        dur != null -> dur
        else -> ""
    }
}
