package com.medtroniclabs.microcoaching.ui.screens.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R

/**
 * Small "Recording" pill shown above the chat input while the mic is active.
 * Visuals: a pulsing red dot, four animated waveform bars, and the localized
 * "Listening…" label.
 *
 * Replaces the explicit backend pill in the chat surface — knowing which
 * engine is transcribing isn't actionable for the CHW, so we just log the
 * backend at the call site and show this generic activity indicator instead.
 * The dedicated [SttBackendBadge] is preserved for hosts that DO want the
 * routing detail surfaced somewhere.
 */
@Composable
fun RecordingBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            PulsingDot()
            Spacer(Modifier.size(10.dp))
            WaveBars()
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.chat_voice_listening),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Solid red circle whose alpha cycles between 1.0 and 0.3 every ~700 ms. */
@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "rec-dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rec-dot-alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(Color(0xFFE53935).copy(alpha = alpha)),
    )
}

/**
 * Four narrow vertical bars that bounce between a min and max height with
 * staggered phase — gives a "live waveform" feel without the cost of a
 * real audio-level meter.
 *
 * The container Row is pinned to [MAX_BAR_HEIGHT_DP] so the bars animate
 * within a fixed slot — without this, each bar's `.height(height.dp)`
 * drives the parent's intrinsic height up and down on every frame, which
 * makes the surrounding pill grow and shrink with the waveform.
 */
@Composable
private fun WaveBars(barCount: Int = 4) {
    val transition = rememberInfiniteTransition(label = "rec-wave")
    Row(
        modifier = Modifier.height(MAX_BAR_HEIGHT_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (index in 0 until barCount) {
            val height by transition.animateFloat(
                initialValue = MIN_BAR_HEIGHT_DP,
                targetValue = MAX_BAR_HEIGHT_DP,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = BAR_PERIOD_MS),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(
                        offsetMillis = index * BAR_STAGGER_MS,
                    ),
                ),
                label = "rec-wave-$index",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private const val MIN_BAR_HEIGHT_DP = 4f
private const val MAX_BAR_HEIGHT_DP = 14f
private const val BAR_PERIOD_MS = 420
private const val BAR_STAGGER_MS = 100
