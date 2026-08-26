package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.medtroniclabs.microcoaching.ui.podashboard.SkStatus
import com.medtroniclabs.microcoaching.ui.theme.CoachingTheme

/*
 * Shared PO-dashboard palette, resolved from the theme's tokens rather than hardcoded.
 *
 * The colour-flavoured names are historical and kept deliberately: they are internal to
 * this package and read at ~25 call sites, so renaming them would be churn without
 * changing behaviour. Note what they now mean — `StatusGreen` is whatever the host chose
 * for `success`, `StatusOrange` its `warning`, `StatusRed` the Material `error` role. On
 * the SPICE defaults they are the same green, orange and red as before.
 *
 * They are composable getters rather than vals because reading a token needs a composition.
 */

internal val StatusGreenBg: Color
    @Composable @ReadOnlyComposable get() = CoachingTheme.colors.successContainer

internal val StatusGreen: Color
    @Composable @ReadOnlyComposable get() = CoachingTheme.colors.success

internal val StatusOrangeBg: Color
    @Composable @ReadOnlyComposable get() = CoachingTheme.colors.warningContainer

internal val StatusOrange: Color
    @Composable @ReadOnlyComposable get() = CoachingTheme.colors.warning

internal val StatusRedBg: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.errorContainer

internal val StatusRed: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error

internal val MutedText: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
@ReadOnlyComposable
internal fun statusBg(status: SkStatus): Color = when (status) {
    SkStatus.ACTIVE -> StatusGreenBg
    SkStatus.NEEDS_ATTENTION -> StatusOrangeBg
    SkStatus.INACTIVE -> StatusRedBg
}

@Composable
@ReadOnlyComposable
internal fun statusFg(status: SkStatus): Color = when (status) {
    SkStatus.ACTIVE -> StatusGreen
    SkStatus.NEEDS_ATTENTION -> StatusOrange
    SkStatus.INACTIVE -> StatusRed
}
