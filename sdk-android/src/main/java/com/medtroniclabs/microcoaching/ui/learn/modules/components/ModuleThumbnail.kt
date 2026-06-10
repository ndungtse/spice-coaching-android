package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.rememberAsyncImagePainter

/**
 * Renders a module thumbnail from its already-presigned URL
 * ([com.medtroniclabs.microcoaching.ui.learn.LearnModule.thumbnailUrl]).
 *
 * No [com.medtroniclabs.microcoaching.network.MediaUrlResolver] round-trip is
 * needed — the URL is resolved at sync time and cached in `module_cache`. The
 * [fallback] is drawn underneath and shows through whenever the URL is null,
 * still loading, or expired/failed (Coil draws nothing on error), so a missing
 * or stale thumbnail degrades gracefully to the caller's placeholder.
 */
@Composable
fun ModuleThumbnail(
    thumbnailUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier) {
        fallback()
        if (!thumbnailUrl.isNullOrBlank()) {
            Image(
                painter = rememberAsyncImagePainter(model = thumbnailUrl),
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )
        }
    }
}
