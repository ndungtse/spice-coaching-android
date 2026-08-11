package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = SpiceBlue,
    onPrimary = UserBubbleText,
    primaryContainer = SpiceBlueContainer,
    onPrimaryContainer = SpiceBlueDark,
    secondary = SpiceBlueDark,
    onSecondary = UserBubbleText,
    background = SurfaceBackground,
    onBackground = SpiceNavy,
    surface = InputBackground,
    onSurface = SpiceNavy,
    error = ErrorRed,
)

/**
 * MicroCoaching SDK theme wrapper.
 *
 * Always renders with the SDK's light color scheme. We deliberately ignore
 * `isSystemInDarkTheme()` because:
 *
 *   * The two BottomSheetDialogFragment wrappers (`CoachingCardBottomSheet`
 *     and `CoachingChatBottomSheet`) force `Theme_Material3_Light_BottomSheetDialog`
 *     for the dialog window. Letting Compose pick a dark scheme inside that
 *     light window produces light-on-light text (e.g. the "No guidance
 *     available" empty state was invisible on Samsung devices with system
 *     dark mode on).
 *   * Several SDK screens use hardcoded light-only colors (the coaching
 *     card banner, module detail, quiz feedback overlays) for design
 *     fidelity to the spec, so a half-dark / half-light render looks
 *     broken on devices that flip the system theme.
 *
 * Hosts that genuinely want dark-mode rendering can wrap content in their
 * own MaterialTheme outside this wrapper.
 *
 * SPICE can wrap any SDK composable in this theme to get consistent styling.
 */
@Composable
fun MicroCoachingTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = CoachingTypography,
        content = content,
    )
}
