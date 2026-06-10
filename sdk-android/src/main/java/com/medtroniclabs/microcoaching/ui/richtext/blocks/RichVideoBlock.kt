package com.medtroniclabs.microcoaching.ui.richtext.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.content.richtext.RichBlock
import com.medtroniclabs.microcoaching.ui.video.VideoPlayerActivity

/**
 * Renders a TipTap video node as a tappable thumbnail card with a centered play
 * button. Tapping opens fullscreen playback in [VideoPlayerActivity], which
 * resolves the presigned URL and drives Media3/ExoPlayer. Keeping the inline card
 * a static placeholder avoids paying playback cost while the lesson scrolls — a
 * deliberate choice for low-end CHW devices.
 */
@Composable
internal fun RichVideoBlock(video: RichBlock.Video, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101828))
            .clickable {
                VideoPlayerActivity.start(
                    context = context,
                    src = video.src,
                    objectName = video.objectName,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.rich_video_play),
                tint = Color(0xFF101828),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}
