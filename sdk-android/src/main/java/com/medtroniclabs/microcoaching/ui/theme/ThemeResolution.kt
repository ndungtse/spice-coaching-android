package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.material3.Typography
import com.medtroniclabs.microcoaching.MicroCoachingSDK

/**
 * Colours for the current SDK configuration, or the SPICE defaults when no SDK is
 * initialised.
 *
 * The fallback is what keeps the module's `@Preview` composables working: they render
 * [MicroCoachingTheme] with no SDK present, and reading config unguarded would throw.
 */
internal fun resolveConfiguredColors(): CoachingColors =
    if (MicroCoachingSDK.isInitialized()) {
        MicroCoachingSDK.getInstance().config.themeColors
    } else {
        CoachingColors.Spice
    }

/** Type scale for the current SDK configuration, or the SDK default. See above. */
internal fun resolveConfiguredTypography(): Typography =
    if (MicroCoachingSDK.isInitialized()) {
        MicroCoachingSDK.getInstance().config.themeTypography
    } else {
        coachingTypography()
    }
