package com.medtroniclabs.microcoaching.ui.common

import android.view.Window
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue

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
 *    ignored — but the SpiceBlue [SdkScreenHeader] already draws behind the status
 *    bar, so it's blue either way. We still call it (harmless no-op) so a single
 *    code path covers both.
 *
 * Either way we force light (white) status-bar icons for contrast against the
 * deep-blue bar.
 */
fun Window.applyCoachingStatusBar() {
    WindowCompat.getInsetsController(this, decorView).isAppearanceLightStatusBars = false
    @Suppress("DEPRECATION")
    statusBarColor = SpiceBlue.toArgb()
}
