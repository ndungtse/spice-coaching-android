package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme

/**
 * Projects the eighteen M3-roled tokens onto a Material 3 [ColorScheme].
 *
 * Two reasons this exists rather than the SDK reading tokens everywhere. Stock Material
 * components (`Button`, `TextField`, `Switch`) read `colorScheme` and cannot be told to
 * read anything else, so a correct scheme is mandatory regardless. And the SDK already
 * had 147 correct `MaterialTheme.colorScheme.*` reads before tokens existed — feeding
 * the scheme from tokens keeps every one of them working untouched.
 *
 * Roles not listed here keep their `lightColorScheme` defaults. That is deliberate: the
 * SDK has no design intent for `tertiary` or `inversePrimary`, and inventing tokens for
 * them would grow the host-facing API without giving hosts anything they asked for.
 */
fun CoachingColors.toM3Scheme(): ColorScheme = lightColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onSecondary,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onSurface,
    surfaceContainerLow = surfaceMuted,
    onSurfaceVariant = textMuted,
    outline = borderStrong,
    outlineVariant = border,
    error = error,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
    scrim = scrim,
)
