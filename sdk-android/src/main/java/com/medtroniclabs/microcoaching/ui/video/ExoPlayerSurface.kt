package com.medtroniclabs.microcoaching.ui.video

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/** How often, while playing, [ExoPlayerSurface] reports a progress checkpoint. */
private const val CHECKPOINT_INTERVAL_MS = 5_000L

/**
 * Shared Media3/ExoPlayer surface — plays a video **or** audio [url] (the
 * `PlayerView` shows transport controls over a blank surface for audio-only
 * media). Auto-plays and releases the player on dispose.
 *
 * Used by [VideoPlayerActivity] (rich-card + training video) and
 * [com.medtroniclabs.microcoaching.ui.document.DocumentPreviewActivity]
 * (Knowledge source-document video/audio, streamed from the presigned URL).
 *
 * Resume + progress reporting are opt-in via the optional params (all default to
 * the prior always-start-at-0, no-callback behaviour, so existing callers are
 * unaffected):
 *  - [startPositionMs] seeks before playback so a partially-watched video resumes.
 *  - [onProgress] fires a checkpoint every [CHECKPOINT_INTERVAL_MS] while playing.
 *  - [onFlush] fires on pause or on screen-exit (dispose) — a forced progress emit.
 *  - [onEnded] fires once playback completes.
 */
@Composable
internal fun ExoPlayerSurface(
    url: String,
    modifier: Modifier = Modifier,
    startPositionMs: Long = 0L,
    onProgress: ((positionMs: Long, durationMs: Long) -> Unit)? = null,
    onFlush: ((positionMs: Long, durationMs: Long) -> Unit)? = null,
    onEnded: ((durationMs: Long) -> Unit)? = null,
) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            if (startPositionMs > 0L) seekTo(startPositionMs)
            prepare()
            playWhenReady = true
        }
    }

    // Listener + final-flush live in the SAME effect as release() so the flush
    // reads player.currentPosition BEFORE the player is released on dispose.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    onEnded?.invoke(player.duration.coerceAtLeast(0L))
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // A user pause — but not the implicit stop at end-of-media, which
                // is handled by onEnded.
                if (!isPlaying && player.playbackState != Player.STATE_ENDED) {
                    onFlush?.invoke(player.currentPosition, player.duration.coerceAtLeast(0L))
                }
            }
        }
        player.addListener(listener)
        onDispose {
            onFlush?.invoke(player.currentPosition, player.duration.coerceAtLeast(0L))
            player.removeListener(listener)
            player.release()
        }
    }

    // Periodic checkpoint while playing.
    if (onProgress != null) {
        LaunchedEffect(player) {
            while (true) {
                delay(CHECKPOINT_INTERVAL_MS)
                if (player.isPlaying) {
                    onProgress(player.currentPosition, player.duration.coerceAtLeast(0L))
                }
            }
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
    )
}
