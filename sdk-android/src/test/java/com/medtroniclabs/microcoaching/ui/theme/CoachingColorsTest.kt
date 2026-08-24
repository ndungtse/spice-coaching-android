package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Golden-hex pin for the SDK's default palette.
 *
 * This is what makes the colour migration safe. Every literal removed from a screen is
 * replaced by a token whose default IS that literal, so a correct migration is a
 * provable no-op — but only while these assertions hold. If someone "tidies" a default
 * here, every migrated screen shifts at once and nothing else would catch it.
 */
class CoachingColorsTest {

    private val spice = CoachingColors.Spice

    @Test
    fun `brand tokens default to the SPICE palette`() {
        assertEquals(Color(0xFF2514BE), spice.primary)
        assertEquals(Color(0xFFFFFFFF), spice.onPrimary)
        assertEquals(Color(0xFFE3F3FA), spice.primaryContainer)
        assertEquals(Color(0xFF004B87), spice.onPrimaryContainer)
        assertEquals(Color(0xFF004B87), spice.secondary)
        assertEquals(Color(0xFFFFFFFF), spice.onSecondary)
    }

    @Test
    fun `surface and neutral tokens default to the SPICE palette`() {
        assertEquals(Color(0xFFFFFFFF), spice.background)
        assertEquals(Color(0xFF001E46), spice.onBackground)
        assertEquals(Color(0xFFFFFFFF), spice.surface)
        assertEquals(Color(0xFF001E46), spice.onSurface)
        assertEquals(Color(0xFFF8FAFC), spice.surfaceMuted)
        assertEquals(Color(0xFF6B6B7B), spice.textMuted)
        assertEquals(Color(0xFF101828), spice.textStrong)
        assertEquals(Color(0xFF344054), spice.textBody)
        assertEquals(Color(0xFF888888), spice.textDisabled)
        assertEquals(Color(0xFFEFEFF3), spice.border)
        assertEquals(Color(0xFFD0D5DD), spice.borderStrong)
        assertEquals(Color(0xFF000000), spice.scrim)
    }

    @Test
    fun `semantic families default to the SPICE palette`() {
        assertEquals(Color(0xFF1B6B4A), spice.success)
        assertEquals(Color(0xFFD7F0E5), spice.successContainer)
        assertEquals(Color(0xFF0A3D27), spice.onSuccessContainer)
        assertEquals(Color(0xFFF57C00), spice.warning)
        assertEquals(Color(0xFFFFF3CD), spice.warningContainer)
        assertEquals(Color(0xFF856404), spice.onWarningContainer)
        assertEquals(Color(0xFFB00020), spice.error)
        assertEquals(Color(0xFFFFEBEE), spice.errorContainer)
        assertEquals(Color(0xFF7F0014), spice.onErrorContainer)
        assertEquals(Color(0xFFFFC83D), spice.reward)
        assertEquals(Color(0xFFFFF8E1), spice.rewardContainer)
        assertEquals(Color(0xFFEBC85B), spice.rewardOutline)
    }

    @Test
    fun `attention is its own token and is not the error red`() {
        // The NEW badge, the Critical badge and the high-severity chip all share
        // 0xFFB91C1C. Folding them into `error` would have shifted all four to
        // 0xFFB00020 and made a "NEW" flag semantically an error state.
        assertEquals(Color(0xFFB91C1C), spice.attention)
        assertNotEquals(spice.error, spice.attention)
    }

    @Test
    fun `component tokens default to the SPICE palette`() {
        assertEquals(Color(0xFFF4F2FA), spice.quizOptionSurface)
        assertEquals(Color(0xFFE4E8EF), spice.lockedSurface)
        assertEquals(Color(0xFFC3C9D4), spice.lockedOutline)
    }

    @Test
    fun `there are exactly three category tag pairs and the first is the brand pair`() {
        assertEquals(3, spice.categoryTags.size)
        assertEquals(spice.primaryContainer, spice.categoryTags[0].container)
        assertEquals(spice.onPrimaryContainer, spice.categoryTags[0].onContainer)
        assertEquals(Color(0xFFEDE7FB), spice.categoryTags[1].container)
        assertEquals(Color(0xFF4A2A9C), spice.categoryTags[1].onContainer)
        assertEquals(Color(0xFFFBEEE3), spice.categoryTags[2].container)
        assertEquals(Color(0xFF8A4B12), spice.categoryTags[2].onContainer)
    }

    @Test
    fun `copy changes only the named token`() {
        val custom = spice.copy(primary = Color(0xFF00695C))
        assertEquals(Color(0xFF00695C), custom.primary)
        assertNotEquals(spice.primary, custom.primary)
        assertEquals(spice.onPrimary, custom.onPrimary)
        assertEquals(spice.success, custom.success)
        assertEquals(spice.attention, custom.attention)
        assertEquals(spice.categoryTags, custom.categoryTags)
    }
}
