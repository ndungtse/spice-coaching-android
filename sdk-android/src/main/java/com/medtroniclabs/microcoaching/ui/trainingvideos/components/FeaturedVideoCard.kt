package com.medtroniclabs.microcoaching.ui.trainingvideos.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.trainingvideos.TrainingVideo

/**
 * Full-width hero card for the featured (newest) training video, at the top of the Training
 * sub-tab. Mirrors [com.medtroniclabs.microcoaching.ui.learn.modules.components.RefresherHeroCard]'s
 * corner-radius/elevation idiom: white [Card], 16.dp corners, 1.dp elevation, a
 * [ModuleThumbnail] banner. Since [TrainingVideo.thumbnailUrl] is always null until the
 * backend exists, the banner always shows the gradient fallback with a large centered play
 * icon rather than a real frame.
 */
@Composable
fun FeaturedVideoCard(
    video: TrainingVideo,
    onClick: () -> Unit,
    onDownloadToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
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
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PlayCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(64.dp),
                            )
                        }
                    },
                )
                WatchedProgressBar(
                    fraction = video.progressFraction,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = video.metaLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6B7280),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                VideoDownloadButton(state = video.download, onToggle = onDownloadToggle)
            }
        }
    }
}
