package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The tokens Material 3 has no role for, plus the colours derived from [CoachingColors].
 *
 * Read it as `CoachingTheme.colors.textStrong`. Anything with a real M3 role is
 * deliberately **absent** here and read as `MaterialTheme.colorScheme.*` instead — the
 * one rule that keeps a call site from having two different ways to ask for the same
 * colour. The rule is enforced by absence rather than by convention: there is no
 * `primary` property to reach for.
 */
@Immutable
class CoachingExtendedColors internal constructor(
    val textStrong: Color,
    val textBody: Color,
    val textDisabled: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val attention: Color,
    /** Label colour for an [attention] badge, chosen by contrast. */
    val onAttention: Color,
    val reward: Color,
    val rewardContainer: Color,
    val rewardOutline: Color,
    val quizOptionSurface: Color,
    val lockedSurface: Color,
    val lockedOutline: Color,
    val categoryTags: List<CategoryTagColors>,
    /**
     * Outgoing chat bubble: the brand colour at full strength, as most chat UIs do it.
     *
     * A semantic alias for `primary` rather than a blend. Earlier revisions tinted it
     * (70%, then 85%), but the right blend factor turned out to depend on the brand
     * hue's luminance: blending a dark blue stays dark enough for white text from 70%
     * up, while a light magenta does not until 90%, leaving an 85-95% band where neither
     * white nor black clears WCAG AA. Full strength has no such cliff.
     */
    val userBubble: Color,
    /**
     * Text on [userBubble] — this is `onPrimary`, deliberately not picked by contrast.
     *
     * The bubble IS `primary`, so it must agree with every other primary-coloured
     * surface (the screen header, filled buttons) about what goes on top. A bubble that
     * contrast-picked its own label could render dark text on the same colour the header
     * renders white text on.
     */
    val onUserBubble: Color,
    /** Incoming chat bubble. Derived: `primary` at 6% over `surface`. */
    val assistantBubble: Color,
    /** Text on [assistantBubble]. */
    val onAssistantBubble: Color,
    /** SK-detail header, dark to light. Derived: `primary`, then `primary` at 85%. */
    val headerGradient: List<Color>,
)

/**
 * Builds the read-side holder, computing every derived colour from the brand tokens.
 *
 * The derivations are why overriding `primary` restyles the chat bubbles, the SK-detail
 * header and the badge labels without a host naming any of them.
 */
fun CoachingColors.extended(): CoachingExtendedColors {
    val assistant = blendOver(surface, primary, 0.06f)
    return CoachingExtendedColors(
        textStrong = textStrong,
        textBody = textBody,
        textDisabled = textDisabled,
        success = success,
        successContainer = successContainer,
        onSuccessContainer = onSuccessContainer,
        warning = warning,
        warningContainer = warningContainer,
        onWarningContainer = onWarningContainer,
        attention = attention,
        onAttention = onColorFor(attention, light = onPrimary, dark = textStrong),
        reward = reward,
        rewardContainer = rewardContainer,
        rewardOutline = rewardOutline,
        quizOptionSurface = quizOptionSurface,
        lockedSurface = lockedSurface,
        lockedOutline = lockedOutline,
        categoryTags = categoryTags,
        userBubble = primary,
        onUserBubble = onPrimary,
        assistantBubble = assistant,
        onAssistantBubble = onColorFor(assistant, light = onPrimary, dark = textStrong),
        headerGradient = listOf(primary, blendOver(surface, primary, 0.85f)),
    )
}

/**
 * Defaults to the SPICE palette so a composable previewed outside [MicroCoachingTheme]
 * renders rather than throwing.
 */
val LocalCoachingColors = staticCompositionLocalOf { CoachingColors.Spice.extended() }

/** Accessor for the non-M3 tokens: `CoachingTheme.colors.textStrong`. */
object CoachingTheme {
    val colors: CoachingExtendedColors
        @Composable @ReadOnlyComposable get() = LocalCoachingColors.current
}
