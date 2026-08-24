package com.medtroniclabs.microcoaching.ui.badges.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.medtroniclabs.microcoaching.ui.asset.rememberCachedImageFileForUrl
import com.medtroniclabs.microcoaching.ui.badges.BadgeState
import com.medtroniclabs.microcoaching.ui.theme.SpiceGreen
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

/** Ring colour for the locked state — a soft neutral grey so it recedes behind earned rings. */
private val LockedRing = Color(0xFFC3C9D4)

/**
 * The circular badge medallion artwork, shared by the Badges grid tile ([BadgeMedallion])
 * and the Your Journey path node.
 *
 * [BadgeState.LOCKED] artwork is desaturated (and slightly dimmed) so a badge's real design
 * stays part of the reward; [BadgeState.EARNED] shows it in full colour. With [showRing] the
 * medallion gains a state-coloured ring — green earned, grey locked — as used on the journey
 * path; the grid tile draws the art ringless and adds its own corner marker instead.
 *
 * The artwork is clipped to the medallion's circle and cropped to fill it. Backend badge
 * images are not reliably square or transparent-cornered, and an unclipped one pushed its
 * corners past the ring.
 *
 * [imageUrl] is the backend's presigned artwork URL, resolved to a locally-cached file so it
 * renders offline after the first online view. Until it resolves — and on an offline miss —
 * a neutral disc stands in, since the SDK ships no artwork of its own for a server-authored
 * catalogue.
 */
@Composable
fun BadgeArtwork(
    imageUrl: String?,
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
    val ringColor = if (locked) LockedRing else SpiceGreen
    val cachedFile by rememberCachedImageFileForUrl(imageUrl)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        val ringModifier = if (showRing) Modifier.border(3.dp, ringColor, CircleShape) else Modifier
        Box(
            modifier = Modifier.fillMaxSize().then(ringModifier),
            contentAlignment = Alignment.Center,
        ) {
            val artSize = if (showRing) size * 0.82f else size
            if (cachedFile != null) {
                Image(
                    painter = rememberAsyncImagePainter(model = cachedFile),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (locked) greyscale else null,
                    alpha = if (locked) 0.85f else 1f,
                    modifier = Modifier.size(artSize).clip(CircleShape),
                )
            } else {
                Box(Modifier.size(artSize).background(SurfaceMuted, CircleShape))
            }
        }
    }
}
