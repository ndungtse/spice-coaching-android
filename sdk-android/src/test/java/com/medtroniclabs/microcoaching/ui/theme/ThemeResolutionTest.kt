package com.medtroniclabs.microcoaching.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the fallback that keeps previews alive.
 *
 * [MicroCoachingTheme] resolves its defaults from SDK config, but roughly eleven
 * `@Preview` composables in the SDK call it with no SDK initialised at all. Reading
 * config unguarded there throws, so every preview in the module would break at once —
 * a failure that never shows up in tests or on a device, only in the IDE.
 */
class ThemeResolutionTest {

    @Test
    fun `colours fall back to the SPICE palette when the SDK is not initialised`() {
        assertEquals(CoachingColors.Spice, resolveConfiguredColors())
    }

    @Test
    fun `typography falls back to the SDK scale when the SDK is not initialised`() {
        assertEquals(coachingTypography(), resolveConfiguredTypography())
    }
}
