package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Centred spinner claiming the available space, with breathing room. The standard
 * first-load state for a screen or a section.
 */
@Composable
fun CenterProgress(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * Slim indeterminate bar that fades in while a background refresh is in flight and out the
 * moment it lands. Sits on the same surface as the content it annotates, so there's no
 * transition flicker between a separate loading screen and the real one.
 */
@Composable
fun TopLoadingBar(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth())
    }
}

@Composable
fun FullScreenLoader(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun DownloadProgressBar(progressPercent: Int, modifier: Modifier = Modifier) {
    if (progressPercent < 0) {
        LinearProgressIndicator(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            color = MaterialTheme.colorScheme.primary,
        )
    } else {
        LinearProgressIndicator(
            progress = { progressPercent / 100f },
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
