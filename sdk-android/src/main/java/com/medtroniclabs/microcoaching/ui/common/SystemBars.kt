package com.medtroniclabs.microcoaching.ui.common

import android.view.Window
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat

/**
 * Paints the system status bar to match the [SdkScreenHeader] so the coaching
 * flow reads as one continuous blue surface from the very top of the screen.
 *
 * Call once from an SDK-owned `Activity`'s `onCreate` (e.g.
 * [com.medtroniclabs.microcoaching.ui.flow.CoachingFlowActivity]). Scoped to the
 * SDK's own window — the host's screens keep their own status-bar styling when
 * they resume.
 *
 * Works across Android versions without any layout/inset changes:
 *  - **API < 35:** the activity is not edge-to-edge, so [Window.setStatusBarColor]
 *    colors the status bar directly.
 *  - **API 35+ (Android 15+):** edge-to-edge is enforced and `statusBarColor` is
 *    ignored — but the brand-coloured [SdkScreenHeader] already draws behind the status
 *    bar, so it's blue either way. We still call it (harmless no-op) so a single
 *    code path covers both.
 *
 * Either way we force light (white) status-bar icons for contrast against the
 * brand-coloured bar.
 *
 * @param color the bar colour — pass the configured `primary` so a host theme applies.
 */
fun Window.applyCoachingStatusBar(color: Color) {
    WindowCompat.getInsetsController(this, decorView).isAppearanceLightStatusBars = false
    @Suppress("DEPRECATION")
    statusBarColor = color.toArgb()
}
