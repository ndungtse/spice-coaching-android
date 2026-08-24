package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * MicroCoaching SDK theme wrapper. Applies the SDK's colour tokens and type scale.
 *
 * ## Restyling from a host app
 *
 * Register colours once at init — **not** by wrapping SDK content in your own
 * `MaterialTheme`:
 *
 * ```kotlin
 * MicroCoachingSDK.Builder(context)
 *     .theme(CoachingColors.Spice.copy(primary = Color(0xFF00695C)))
 *     .typography(coachingTypography(fontFamily = MyBrandFont))
 * ```
 *
 * Wrapping from outside does not work, for two reasons. `MaterialTheme` *replaces* the
 * colour scheme rather than inheriting it, so this wrapper would discard an outer
 * theme. And for most SDK surfaces there is no outside: `CoachingFlowActivity` and the
 * four `BottomSheetDialogFragment`s own their own Compose roots, and
 * `SdkLocalizedTheme` applies this wrapper internally at every entry point.
 *
 * ## Where tokens are read
 *
 * Tokens with a Material 3 role are read as `MaterialTheme.colorScheme.*`. The
 * seventeen with no M3 equivalent are read as `CoachingTheme.colors.*`. No token is
 * readable from both — see [CoachingColors].
 *
 * ## Light only
 *
 * This always renders light, and ignores `isSystemInDarkTheme()`, because the four
 * bottom-sheet fragments force `Theme_Material3_Light_BottomSheetDialog` on the dialog
 * window and three manifest activities force `Theme.AppCompat.Light.NoActionBar`. A
 * dark Compose scheme inside a light window produced invisible light-on-light text —
 * the "No guidance available" empty state on Samsung devices with system dark mode on.
 * Supporting dark mode means changing those window themes, not this function.
 */
@Composable
fun MicroCoachingTheme(
    colors: CoachingColors = resolveConfiguredColors(),
    typography: Typography = resolveConfiguredTypography(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalCoachingColors provides colors.extended()) {
        MaterialTheme(
            colorScheme = colors.toM3Scheme(),
            typography = typography,
            content = content,
        )
    }
}
