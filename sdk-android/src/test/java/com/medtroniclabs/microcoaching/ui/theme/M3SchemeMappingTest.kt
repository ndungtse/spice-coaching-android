package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the token to Material 3 role projection.
 *
 * Guards two failures. First, a mis-wired role is invisible in review but wrong on
 * screen for every stock Material component at once — an `onSurface` accidentally fed
 * from `surface` renders white-on-white. Second, the 147 existing
 * `MaterialTheme.colorScheme.*` reads in the SDK were correct before this change and
 * must keep resolving to the same colours, so the scheme built from the SPICE defaults
 * has to match the old hand-written `LightColorScheme`.
 */
class M3SchemeMappingTest {

    private val scheme = CoachingColors.Spice.toM3Scheme()

    @Test
    fun `brand roles come from the brand tokens`() {
        assertEquals(Color(0xFF2514BE), scheme.primary)
        assertEquals(Color(0xFFFFFFFF), scheme.onPrimary)
        assertEquals(Color(0xFFE3F3FA), scheme.primaryContainer)
        assertEquals(Color(0xFF004B87), scheme.onPrimaryContainer)
        assertEquals(Color(0xFF004B87), scheme.secondary)
        assertEquals(Color(0xFFFFFFFF), scheme.onSecondary)
    }

    @Test
    fun `surface roles come from the surface tokens`() {
        assertEquals(Color(0xFFFFFFFF), scheme.background)
        assertEquals(Color(0xFF001E46), scheme.onBackground)
        assertEquals(Color(0xFFFFFFFF), scheme.surface)
        assertEquals(Color(0xFF001E46), scheme.onSurface)
        assertEquals(Color(0xFFF8FAFC), scheme.surfaceContainerLow)
        assertEquals(Color(0xFF000000), scheme.scrim)
    }

    @Test
    fun `variant and outline roles come from the neutral tokens`() {
        assertEquals(Color(0xFF6B6B7B), scheme.onSurfaceVariant)
        assertEquals(Color(0xFFEFEFF3), scheme.outlineVariant)
        assertEquals(Color(0xFFD0D5DD), scheme.outline)
    }

    @Test
    fun `error roles come from the error family`() {
        assertEquals(Color(0xFFB00020), scheme.error)
        assertEquals(Color(0xFFFFEBEE), scheme.errorContainer)
        assertEquals(Color(0xFF7F0014), scheme.onErrorContainer)
    }

    @Test
    fun `overriding a token moves its M3 role`() {
        val custom = CoachingColors.Spice.copy(primary = Color(0xFF00695C)).toM3Scheme()
        assertEquals(Color(0xFF00695C), custom.primary)
        assertEquals(Color(0xFFE3F3FA), custom.primaryContainer)
    }

    @Test
    fun `the defaults reproduce every role the old hand-written scheme set`() {
        // The pre-token LightColorScheme in Theme.kt set exactly these eleven roles.
        // Everything reading MaterialTheme.colorScheme today depends on them.
        assertEquals(Color(0xFF2514BE), scheme.primary)
        assertEquals(Color(0xFFFFFFFF), scheme.onPrimary)
        assertEquals(Color(0xFFE3F3FA), scheme.primaryContainer)
        assertEquals(Color(0xFF004B87), scheme.onPrimaryContainer)
        assertEquals(Color(0xFF004B87), scheme.secondary)
        assertEquals(Color(0xFFFFFFFF), scheme.onSecondary)
        assertEquals(Color(0xFFFFFFFF), scheme.background)
        assertEquals(Color(0xFF001E46), scheme.onBackground)
        assertEquals(Color(0xFFFFFFFF), scheme.surface)
        assertEquals(Color(0xFF001E46), scheme.onSurface)
        assertEquals(Color(0xFFB00020), scheme.error)
    }
}
