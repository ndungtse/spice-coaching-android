package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The SDK's type scale, with every Material 3 style defined so a host-supplied
 * [fontFamily] actually applies everywhere.
 *
 * The previous version defined only `bodyLarge`, `bodyMedium` and `labelSmall`; the
 * other twelve fell through to M3 defaults, which meant a brand font would have been
 * honoured by three styles and ignored by twelve. The twelve are filled in from the same
 * M3 defaults they were falling through to, so this changes nothing on screen.
 */
fun coachingTypography(fontFamily: FontFamily = FontFamily.Default): Typography {
    val base = Typography()
    fun TextStyle.branded() = copy(fontFamily = fontFamily)
    return Typography(
        displayLarge = base.displayLarge.branded(),
        displayMedium = base.displayMedium.branded(),
        displaySmall = base.displaySmall.branded(),
        headlineLarge = base.headlineLarge.branded(),
        headlineMedium = base.headlineMedium.branded(),
        headlineSmall = base.headlineSmall.branded(),
        titleLarge = base.titleLarge.branded(),
        titleMedium = base.titleMedium.branded(),
        titleSmall = base.titleSmall.branded(),
        bodySmall = base.bodySmall.branded(),
        labelLarge = base.labelLarge.branded(),
        labelMedium = base.labelMedium.branded(),
        // The three the SDK had already tuned — kept byte-for-byte.
        bodyLarge = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        ),
    )
}

@Deprecated(
    "Use coachingTypography() so a host font family can be applied.",
    ReplaceWith("coachingTypography()"),
)
val CoachingTypography: Typography = coachingTypography()
