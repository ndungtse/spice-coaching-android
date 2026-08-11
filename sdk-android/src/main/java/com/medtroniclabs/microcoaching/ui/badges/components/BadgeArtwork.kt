package com.medtroniclabs.microcoaching.ui.badges.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.badges.BadgeState
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceGreen

/** Ring colour for the locked state — a soft neutral grey so it recedes behind earned rings. */
private val LockedRing = Color(0xFFC3C9D4)

/**
 * The circular badge medallion artwork, shared by the Badges grid tile ([BadgeMedallion])
 * and the Your Journey path node.
 *
 * [BadgeState.LOCKED] artwork is desaturated (and slightly dimmed) so a badge's real design
 * stays part of the reward; [BadgeState.EARNED] / [BadgeState.CURRENT] show it in full
 * colour. With [showRing] the medallion gains a state-coloured ring — green earned, blue
 * current (over a soft glow), grey locked — as used on the journey path; the grid tile draws
 * the art ringless and adds its own corner marker instead.
 */
@Composable
fun BadgeArtwork(
    @DrawableRes image: Int,
    state: BadgeState,
    contentDescription: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    showRing: Boolean = false,
) {
    val locked = state == BadgeState.LOCKED
    val greyscale = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }
    val ringColor = when (state) {
        BadgeState.EARNED -> SpiceGreen
        BadgeState.CURRENT -> SpiceBlue
        BadgeState.LOCKED -> LockedRing
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (showRing && state == BadgeState.CURRENT) {
            // Soft glow so the "current" node reads as the live one on the path.
            Box(Modifier.fillMaxSize().background(SpiceBlue.copy(alpha = 0.14f), CircleShape))
        }
        val ringModifier = if (showRing) Modifier.border(3.dp, ringColor, CircleShape) else Modifier
        Box(
            modifier = Modifier.fillMaxSize().then(ringModifier),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(image),
                contentDescription = contentDescription,
                colorFilter = if (locked) greyscale else null,
                alpha = if (locked) 0.85f else 1f,
                modifier = Modifier.size(if (showRing) size * 0.82f else size),
            )
        }
    }
}
