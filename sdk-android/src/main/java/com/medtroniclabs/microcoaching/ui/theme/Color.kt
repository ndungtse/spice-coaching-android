package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * The raw SPICE palette.
 *
 * These are `internal` on purpose: they are the *defaults* behind [CoachingColors], not
 * the SDK's public colour vocabulary. A host restyles the SDK by copying
 * `CoachingColors.Spice` and overriding named tokens — reaching for `SpiceBlue` directly
 * would hardcode the brand back into a themed app.
 *
 * Nothing outside this file should read them; UI code reads
 * `MaterialTheme.colorScheme.*` or `CoachingTheme.colors.*`.
 */

// Brand
internal val SpiceBlue = Color(0xFF2514BE)
internal val SpiceBlueDark = Color(0xFF004B87)
internal val SpiceBlueContainer = Color(0xFFE3F3FA)
internal val SpiceNavy = Color(0xFF001E46)

/** White content on the brand colour. Backs `onPrimary` and `onSecondary`. */
internal val UserBubbleText = Color(0xFFFFFFFF)

// Surfaces
internal val SurfaceBackground = Color(0xFFFFFFFF)
/** Slightly off-white app surface (≈ Tailwind slate-50) so pure-white cards read as cards. */
internal val SurfaceMuted = Color(0xFFF8FAFC)
internal val InputBackground = Color(0xFFFFFFFF)

/**
 * Soft surface tint used for quiz answer options inside the refresher bottom
 * sheet (whose own background is white). Mirrors the Material3 light
 * `surfaceContainerLow` so the options read as the sheet's old surface.
 */
internal val QuizOptionSurface = Color(0xFFF4F2FA)

/** Muted grey for secondary/label text. Backs `onSurfaceVariant`. */
internal val MutedText = Color(0xFF6B6B7B)

/**
 * Answer-feedback / status palette. Green = a correct quiz answer or a completed
 * module; red = a wrong answer. Each colour is a semantic triad mirroring the
 * blue family above: accent (border/badge/icon), a soft container fill, and the
 * dark on-container text. These back the `success` and `error` token families, so
 * "correct/complete" reads identically everywhere.
 */
internal val SpiceGreen = Color(0xFF1B6B4A)
internal val SpiceGreenContainer = Color(0xFFD7F0E5)
internal val SpiceGreenDark = Color(0xFF0A3D27)

internal val ErrorRed = Color(0xFFB00020)
internal val ErrorRedContainer = Color(0xFFFFEBEE)
internal val ErrorRedDark = Color(0xFF7F0014)
