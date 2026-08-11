package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Canvas-based circular arc that animates from 0 to [scorePercent] on first composition.
 *
 * - Grey track shows the full 360°.
 * - Green arc sweeps from the top to the current score position.
 * - Score percentage is centered as text.
 *
 * @param scorePercent Integer 0–100.
 * @param size Canvas diameter. Default 160.dp.
 * @param strokeWidth Arc stroke width. Default 16.dp.
 */
@Composable
fun CircularScoreArc(
    scorePercent: Int,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    strokeWidth: Dp = 16.dp,
    trackColor: Color = Color(0xFFDDDDDD),
    arcColor: Color = Color(0xFF1B6B4A),
) {
    var animTarget by remember { mutableFloatStateOf(0f) }
    val animatedSweep by animateFloatAsState(
        targetValue = animTarget,
        animationSpec = tween(durationMillis = 900),
        label = "score_arc",
    )

    LaunchedEffect(scorePercent) {
        animTarget = scorePercent * 360f / 100f
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = strokeWidth.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            val topLeft = Offset(inset, inset)

            // Track (full circle)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )

            // Progress arc
            drawArc(
                color = arcColor,
                startAngle = -90f,
                sweepAngle = animatedSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }

        Text(
            text = "$scorePercent%",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0A3D27),
        )
    }
}
