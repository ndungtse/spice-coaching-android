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
    /** Outgoing chat bubble. Derived: `primary` at 70% over `surface`. */
    val userBubble: Color,
    /** Text on [userBubble], chosen by contrast so a pale brand colour stays readable. */
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
    val user = blendOver(surface, primary, 0.70f)
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
        userBubble = user,
        onUserBubble = onColorFor(user, light = onPrimary, dark = textStrong),
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
