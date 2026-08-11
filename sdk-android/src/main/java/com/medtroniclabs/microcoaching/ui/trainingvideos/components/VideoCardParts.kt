package com.medtroniclabs.microcoaching.ui.trainingvideos.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.trainingvideos.VideoDownloadState

/** YouTube-style "watched" progress bar colour (reads on any thumbnail). */
private val WatchedProgressColor = Color(0xFFEF4444)
private val WatchedTrackColor = Color(0x33000000)

/**
 * Thin watched-progress bar overlaid at the bottom of a video thumbnail
 * (YouTube-style). Renders nothing when [fraction] is 0 — an unwatched video
 * shows a clean thumbnail. Place inside the thumbnail [Box] aligned to bottom.
 */
@Composable
fun WatchedProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    if (fraction <= 0f) return
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        color = WatchedProgressColor,
        trackColor = WatchedTrackColor,
        modifier = modifier.fillMaxWidth().height(3.dp),
    )
}

/**
 * Centered play affordance overlaid on a video thumbnail — a translucent dark
 * disc with a white triangle, the standard "this is a video" cue.
 */
@Composable
fun ThumbnailPlayBadge(modifier: Modifier = Modifier, size: Dp = 34.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.58f),
        )
    }
}

/**
 * YouTube-style duration pill (m:ss / h:mm:ss) for the thumbnail's bottom-end.
 * Renders nothing when the duration is unknown (0) so it never shows "0:00".
 */
@Composable
fun DurationChip(durationMs: Long, modifier: Modifier = Modifier) {
    if (durationMs <= 0L) return
    Box(
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = formatVideoDuration(durationMs),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** `m:ss`, or `h:mm:ss` past an hour. Assumes [durationMs] > 0 (caller-guarded). */
internal fun formatVideoDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/**
 * Download / downloading / remove-download action for a training video, shared
 * by the featured card and the list row. A determinate ring while downloading (or
 * an indeterminate spinner until the size is known), else a toggle icon.
 */
@Composable
fun VideoDownloadButton(
    state: VideoDownloadState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = SpiceBlue,
) {
    when (state) {
        is VideoDownloadState.Downloading -> Box(
            modifier = modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (state.percent != null) {
                CircularProgressIndicator(
                    progress = { state.percent / 100f },
                    color = tint,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                CircularProgressIndicator(color = tint, modifier = Modifier.size(22.dp))
            }
        }
        else -> IconButton(onClick = onToggle, modifier = modifier) {
            val (icon, desc) = when (state) {
                VideoDownloadState.Downloaded ->
                    Icons.Filled.DownloadDone to R.string.training_videos_remove_download
                else ->
                    Icons.Filled.Download to R.string.training_videos_download
            }
            Icon(imageVector = icon, contentDescription = stringResource(desc), tint = tint)
        }
    }
}
