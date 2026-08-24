package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** A decorative category chip: a soft fill plus the text colour that sits on it. */
@Immutable
data class CategoryTagColors(
    val container: Color,
    val onContainer: Color,
)

private val SpiceCategoryTags = listOf(
    CategoryTagColors(container = SpiceBlueContainer, onContainer = SpiceBlueDark),
    CategoryTagColors(container = Color(0xFFEDE7FB), onContainer = Color(0xFF4A2A9C)),
    CategoryTagColors(container = Color(0xFFFBEEE3), onContainer = Color(0xFF8A4B12)),
)

/**
 * The complete colour vocabulary of the MicroCoaching SDK, and the single thing a host
 * supplies to restyle it.
 *
 * Register it once at init:
 *
 * ```kotlin
 * MicroCoachingSDK.Builder(context)
 *     .theme(CoachingColors.Spice.copy(primary = Color(0xFF00695C)))
 * ```
 *
 * Override only what you care about — `copy` leaves the rest at the SPICE defaults
 * documented on each property.
 *
 * **Where tokens are read.** Eighteen of these map onto real Material 3 roles and are
 * read at call sites as `MaterialTheme.colorScheme.*` (see [toM3Scheme]). The remaining
 * seventeen have no M3 equivalent and are read as `CoachingTheme.colors.*` via
 * [CoachingExtendedColors]. No token is readable from both.
 *
 * **What is not here.** Chat bubbles, the SK-detail header gradient, badge label
 * colours and progress tracks are *derived* from [primary] rather than being tokens, so
 * restyling the brand carries them along automatically. Gold, silver and bronze on the
 * leaderboard are deliberately not themeable — medal metals carry universal meaning.
 */
@Immutable
data class CoachingColors(
    // ─────────── Brand — projected onto Material 3 roles ───────────
    /** Primary brand colour: buttons, links, active icons, gradient starts.
     *  SPICE default `SpiceBlue` `0xFF2514BE`. Becomes `colorScheme.primary`. */
    val primary: Color = SpiceBlue,
    /** Content drawn on [primary]. SPICE default `0xFFFFFFFF`. → `onPrimary`. */
    val onPrimary: Color = UserBubbleText,
    /** Soft brand fill: chips, banners, selected states.
     *  SPICE default `SpiceBlueContainer` `0xFFE3F3FA`. → `primaryContainer`. */
    val primaryContainer: Color = SpiceBlueContainer,
    /** Content on [primaryContainer]. SPICE default `SpiceBlueDark` `0xFF004B87`.
     *  → `onPrimaryContainer`. */
    val onPrimaryContainer: Color = SpiceBlueDark,
    /** Deep brand shade for headers and secondary emphasis.
     *  SPICE default `SpiceBlueDark` `0xFF004B87`. → `secondary`. */
    val secondary: Color = SpiceBlueDark,
    /** Content on [secondary]. SPICE default `0xFFFFFFFF`. → `onSecondary`. */
    val onSecondary: Color = UserBubbleText,

    // ─────────── Surfaces ───────────
    /** Screen background. SPICE default `0xFFFFFFFF`. → `background`. */
    val background: Color = SurfaceBackground,
    /** Content on [background]. SPICE default `SpiceNavy` `0xFF001E46`. → `onBackground`. */
    val onBackground: Color = SpiceNavy,
    /** Card and input surfaces. SPICE default `0xFFFFFFFF`. → `surface`. */
    val surface: Color = InputBackground,
    /** Primary text on [surface]. SPICE default `SpiceNavy` `0xFF001E46`. → `onSurface`. */
    val onSurface: Color = SpiceNavy,
    /** Recessed app surface so white cards read as cards.
     *  SPICE default `SurfaceMuted` `0xFFF8FAFC`. → `surfaceContainerLow`. */
    val surfaceMuted: Color = SurfaceMuted,
    /** Full-strength black behind scrims and media overlays.
     *  SPICE default `0xFF000000`. → `scrim`. */
    val scrim: Color = Color.Black,

    // ─────────── Neutral text and lines ───────────
    /** Secondary and label text. SPICE default `MutedText` `0xFF6B6B7B`.
     *  → `onSurfaceVariant`. */
    val textMuted: Color = MutedText,
    /** Hairlines and dividers. SPICE default `0xFFEFEFF3`. → `outlineVariant`. */
    val border: Color = Color(0xFFEFEFF3),
    /** Input outlines and stronger separators. SPICE default `0xFFD0D5DD`. → `outline`. */
    val borderStrong: Color = Color(0xFFD0D5DD),
    /** Screen and card titles. SPICE default `0xFF101828`. Read via `CoachingTheme.colors`.
     *
     *  Distinct from [onSurface], which is the blue-navy `SpiceNavy`. Kept separate so
     *  titles stay near-neutral as they are today. */
    val textStrong: Color = Color(0xFF101828),
    /** Body copy and markdown. SPICE default `0xFF344054`. Read via `CoachingTheme.colors`. */
    val textBody: Color = Color(0xFF344054),
    /** Disabled or de-emphasised labels. SPICE default `0xFF888888`. */
    val textDisabled: Color = Color(0xFF888888),

    // ─────────── Semantic families ───────────
    /** Correct answers, completed modules. SPICE default `SpiceGreen` `0xFF1B6B4A`. */
    val success: Color = SpiceGreen,
    /** Soft fill behind a success state. SPICE default `SpiceGreenContainer` `0xFFD7F0E5`. */
    val successContainer: Color = SpiceGreenContainer,
    /** Content on [successContainer]. SPICE default `SpiceGreenDark` `0xFF0A3D27`. */
    val onSuccessContainer: Color = SpiceGreenDark,
    /** Streaks, achievements, moderate severity. SPICE default `0xFFF57C00`.
     *
     *  New family: the SDK had no warning token, so nine hardcoded oranges across eight
     *  files shared no source of truth. */
    val warning: Color = Color(0xFFF57C00),
    /** Soft fill behind a warning state. SPICE default `0xFFFFF3CD`. */
    val warningContainer: Color = Color(0xFFFFF3CD),
    /** Content on [warningContainer]. SPICE default `0xFF856404`. */
    val onWarningContainer: Color = Color(0xFF856404),
    /** Wrong answers and failures. SPICE default `ErrorRed` `0xFFB00020`. → `error`. */
    val error: Color = ErrorRed,
    /** Soft fill behind an error state. SPICE default `ErrorRedContainer` `0xFFFFEBEE`.
     *  → `errorContainer`. */
    val errorContainer: Color = ErrorRedContainer,
    /** Content on [errorContainer]. SPICE default `ErrorRedDark` `0xFF7F0014`.
     *  → `onErrorContainer`. */
    val onErrorContainer: Color = ErrorRedDark,
    /** Small high-emphasis badges: the "NEW" flag, the "CRITICAL" flag, and the
     *  high-severity chip. SPICE default `0xFFB91C1C`.
     *
     *  New family, and deliberately **not** [error]. Two of its four uses label new
     *  content rather than a failure, so collapsing them into the error red would have
     *  made a "NEW" flag semantically an error state — and shifted all four from brick
     *  to crimson. Its label colour is derived by contrast, not tokenised. */
    val attention: Color = Color(0xFFB91C1C),
    /** XP badges and reward bursts. SPICE default `0xFFFFC83D`. New family. */
    val reward: Color = Color(0xFFFFC83D),
    /** Soft fill behind a reward. SPICE default `0xFFFFF8E1`. */
    val rewardContainer: Color = Color(0xFFFFF8E1),
    /** Border around a reward badge. SPICE default `0xFFEBC85B`. */
    val rewardOutline: Color = Color(0xFFEBC85B),

    // ─────────── Component-specific ───────────
    /** Quiz answer options inside a white bottom sheet.
     *  SPICE default `QuizOptionSurface` `0xFFF4F2FA`. */
    val quizOptionSurface: Color = QuizOptionSurface,
    /** Fill for a locked badge or an untravelled journey segment.
     *  SPICE default `0xFFE4E8EF`. New family. */
    val lockedSurface: Color = Color(0xFFE4E8EF),
    /** Ring around a locked badge. SPICE default `0xFFC3C9D4`. */
    val lockedOutline: Color = Color(0xFFC3C9D4),
    /** Decorative chip palette for content domains and practice zones, cycled by index.
     *
     *  SPICE defaults: the brand pair, then violet `0xFFEDE7FB`/`0xFF4A2A9C`, then peach
     *  `0xFFFBEEE3`/`0xFF8A4B12`. Supply at least one pair; consumers index modulo size. */
    val categoryTags: List<CategoryTagColors> = SpiceCategoryTags,
) {
    companion object {
        /** The SDK's own palette, and the base every host override should `copy` from. */
        val Spice: CoachingColors = CoachingColors()
    }
}
