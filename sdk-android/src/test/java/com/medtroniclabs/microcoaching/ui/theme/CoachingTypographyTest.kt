package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins that a host-supplied font family reaches every text style.
 *
 * The failure this guards was latent in the old `CoachingTypography`: it defined three
 * of Material 3's fifteen styles, so the other twelve silently fell through to the M3
 * defaults. A host setting a brand font would have seen it applied to body text and
 * ignored by every heading, with nothing reporting a problem.
 */
class CoachingTypographyTest {

    @Test
    fun `the three previously defined styles keep their exact metrics`() {
        val t = coachingTypography()
        assertEquals(16.sp, t.bodyLarge.fontSize)
        assertEquals(24.sp, t.bodyLarge.lineHeight)
        assertEquals(14.sp, t.bodyMedium.fontSize)
        assertEquals(20.sp, t.bodyMedium.lineHeight)
        assertEquals(11.sp, t.labelSmall.fontSize)
        assertEquals(16.sp, t.labelSmall.lineHeight)
    }

    @Test
    fun `a supplied font family reaches all fifteen styles`() {
        val brand = FontFamily.Monospace
        val t = coachingTypography(brand)
        val all = listOf(
            t.displayLarge, t.displayMedium, t.displaySmall,
            t.headlineLarge, t.headlineMedium, t.headlineSmall,
            t.titleLarge, t.titleMedium, t.titleSmall,
            t.bodyLarge, t.bodyMedium, t.bodySmall,
            t.labelLarge, t.labelMedium, t.labelSmall,
        )
        assertEquals(15, all.size)
        all.forEach { assertEquals(brand, it.fontFamily) }
    }

    @Test
    fun `the default family is the platform default`() {
        assertEquals(FontFamily.Default, coachingTypography().titleLarge.fontFamily)
    }

    @Test
    fun `the twelve filled-in styles keep the metrics that were already in effect`() {
        // These twelve previously fell through to Material 3's defaults. Filling them in
        // must not change how anything renders, so their metrics are taken from the same
        // source they were falling through to.
        val filled = coachingTypography()
        val m3 = androidx.compose.material3.Typography()
        assertEquals(m3.titleLarge.fontSize, filled.titleLarge.fontSize)
        assertEquals(m3.titleLarge.lineHeight, filled.titleLarge.lineHeight)
        assertEquals(m3.headlineSmall.fontSize, filled.headlineSmall.fontSize)
        assertEquals(m3.bodySmall.fontSize, filled.bodySmall.fontSize)
        assertEquals(m3.labelLarge.fontSize, filled.labelLarge.fontSize)
        assertEquals(m3.displayLarge.fontSize, filled.displayLarge.fontSize)
    }
}
