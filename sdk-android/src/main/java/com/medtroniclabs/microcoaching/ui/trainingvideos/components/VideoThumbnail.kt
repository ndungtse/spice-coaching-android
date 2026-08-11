package com.medtroniclabs.microcoaching.ui.trainingvideos.components

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.medtroniclabs.microcoaching.ui.asset.rememberCachedImageFileForUrl
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer

/**
 * Video thumbnail that shows the **whole** frame (`ContentScale.Fit` / "contain")
 * rather than cropping it, so a 16:9 or square source never gets its edges cut.
 * The letterbox gutter is filled with a **vibrant, colour-matched backdrop**: the
 * same image cover-scaled and blurred (the YouTube/Spotify treatment). Because
 * `Modifier.blur` is a no-op below API 31, the blurred fill is gated to API ≥ 31;
 * older devices fall back to the brand gradient behind the contained image.
 *
 * The [fallback] (e.g. a gradient + play icon) shows when there's no image yet
 * (null URL, still resolving, or an offline miss).
 */
@Composable
fun VideoThumbnail(
    thumbnailUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    val cachedFile by rememberCachedImageFileForUrl(thumbnailUrl)
    Box(modifier) {
        val file = cachedFile
        if (file == null) {
            fallback()
        } else {
            // Base gradient — the letterbox fill on API < 31 (and a harmless base
            // beneath the blurred fill on newer devices).
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(listOf(SpiceBlueContainer, SpiceBlue.copy(alpha = 0.3f))),
                    ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Vibrant colour-matched fill: same image, cover-scaled + blurred.
                Image(
                    painter = rememberAsyncImagePainter(model = file),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize().blur(24.dp),
                    contentScale = ContentScale.Crop,
                )
                // Gentle scrim so the contained frame stays crisp against the fill.
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.12f)))
            }
            // Foreground: the whole frame, contained (may letterbox on the sides).
            Image(
                painter = rememberAsyncImagePainter(model = file),
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
