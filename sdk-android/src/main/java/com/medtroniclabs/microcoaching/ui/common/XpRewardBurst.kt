package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Celebratory "+N XP" burst shown when the CHW answers a quiz question
 * correctly. Pops in with a bouncy spring, drifts upward, and auto-dismisses
 * after [autoDismissMillis]. Self-contained — the caller only needs to flip
 * [triggerKey] to a fresh value every time it wants the burst to fire.
 *
 * Pass `triggerKey = null` (the default) when no burst should be visible.
 * Pass a unique key (e.g. `System.nanoTime()` or `questionIndex`) on first
 * correct reveal. The `LaunchedEffect(triggerKey)` re-runs on key change,
 * flipping internal visibility on and then back off after the delay.
 *
 * Anchor the caller with `Modifier.align(Alignment.TopCenter)` (or similar)
 * inside a [Box] over the quiz content so the burst overlays rather than
 * displaces the answer cards.
 */
@Composable
fun XpRewardBurst(
    triggerKey: Any?,
    pointValue: Int,
    modifier: Modifier = Modifier,
    autoDismissMillis: Long = 1300L,
) {
    var visible by remember { mutableStateOf(false) }
    // Re-run on every triggerKey change — including from null → key, and from
    // one key to a fresh key (back-to-back correct answers).
    LaunchedEffect(triggerKey) {
        if (triggerKey != null) {
            visible = true
            delay(autoDismissMillis)
            visible = false
        }
    }

    // Upward float — animates while visible, snaps back when hidden so the
    // next burst starts from rest.
    val floatY by animateDpAsState(
        targetValue = if (visible) (-32).dp else 0.dp,
        animationSpec = tween(durationMillis = autoDismissMillis.toInt()),
        label = "xp_burst_float",
    )

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ) + fadeIn(tween(180)),
        exit = scaleOut(tween(220)) + fadeOut(tween(220)),
        modifier = modifier.offset(y = floatY),
    ) {
        Row(
            modifier = Modifier
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(28.dp),
                )
                .background(
                    color = Color(0xFFFFF8E1).copy(alpha = 0.1f), // warm cream with 10% opacity
                    shape = RoundedCornerShape(28.dp),
                )
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = Color(0xFFFFC83D), // gold
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "+$pointValue XP",
                color = Color(0xFF1B6B4A), // forest green — matches the existing "correct" palette
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
            )
        }
    }
}
