package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.ui.graphics.Color

// SPICE brand palette
val SpiceBlue = Color(0xFF2514BE)
val SpiceBlueDark = Color(0xFF004B87)
val SpiceBlueContainer = Color(0xFFE3F3FA)
val SpiceNavy = Color(0xFF001E46)

val UserBubble = Color(0xFF0085CA)
val UserBubbleText = Color(0xFFFFFFFF)
val AssistantBubble = Color(0xFFF0F7FB)
val AssistantBubbleText = Color(0xFF1A1A1A)

val SurfaceBackground = Color(0xFFFFFFFF)
/** Slightly off-white app surface (≈ Tailwind slate-50) so pure-white cards read as cards. */
val SurfaceMuted = Color(0xFFF8FAFC)
val InputBackground = Color(0xFFFFFFFF)
val ErrorRed = Color(0xFFB00020)

/**
 * Soft surface tint used for quiz answer options inside the refresher bottom
 * sheet (whose own background is white). Mirrors the Material3 light
 * `surfaceContainerLow` so the options read as the sheet's old surface.
 */
val QuizOptionSurface = Color(0xFFF4F2FA)

/** Muted grey for secondary/label text (leaderboard, PO dashboard, segmented toggle). */
val MutedText = Color(0xFF6B6B7B)

/**
 * Answer-feedback / status palette. Green = a correct quiz answer or a completed
 * module; red = a wrong answer. Each colour is a semantic triad mirroring the
 * blue family above: accent (border/badge/icon), a soft container fill, and the
 * dark on-container text. Shared by [AnswerCard], the answer-feedback overlays,
 * the module status chip, the score arc and the XP burst so "correct/complete"
 * reads identically everywhere.
 */
val SpiceGreen = Color(0xFF1B6B4A)
val SpiceGreenContainer = Color(0xFFD7F0E5)
val SpiceGreenDark = Color(0xFF0A3D27)

/** Wrong-answer container fill + on-container text (accent is [ErrorRed]). */
val ErrorRedContainer = Color(0xFFFFEBEE)
val ErrorRedDark = Color(0xFF7F0014)
