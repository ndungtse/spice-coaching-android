package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the read-side holder and the colours derived into it.
 *
 * The split it protects: an M3-roled token must NOT be reachable here, or call sites
 * gain two ways to ask for `primary` and drift apart. Kotlin enforces that by absence,
 * so these tests instead pin the derivations — the bubbles, the SK header and the
 * attention label, which are the colours this design deliberately does not tokenise so
 * that they follow a host's brand automatically.
 */
class CoachingExtendedColorsTest {

    private val ext = CoachingColors.Spice.extended()

    private fun hex(c: Color): String = String.format(
        "%02X%02X%02X",
        Math.round(c.red * 255),
        Math.round(c.green * 255),
        Math.round(c.blue * 255),
    )

    @Test
    fun `non-M3 tokens pass through unchanged`() {
        assertEquals(Color(0xFF101828), ext.textStrong)
        assertEquals(Color(0xFF344054), ext.textBody)
        assertEquals(Color(0xFF888888), ext.textDisabled)
        assertEquals(Color(0xFF1B6B4A), ext.success)
        assertEquals(Color(0xFFD7F0E5), ext.successContainer)
        assertEquals(Color(0xFF0A3D27), ext.onSuccessContainer)
        assertEquals(Color(0xFFF57C00), ext.warning)
        assertEquals(Color(0xFFFFF3CD), ext.warningContainer)
        assertEquals(Color(0xFF856404), ext.onWarningContainer)
        assertEquals(Color(0xFFB91C1C), ext.attention)
        assertEquals(Color(0xFFFFC83D), ext.reward)
        assertEquals(Color(0xFFFFF8E1), ext.rewardContainer)
        assertEquals(Color(0xFFEBC85B), ext.rewardOutline)
        assertEquals(Color(0xFFF4F2FA), ext.quizOptionSurface)
        assertEquals(Color(0xFFE4E8EF), ext.lockedSurface)
        assertEquals(Color(0xFFC3C9D4), ext.lockedOutline)
        assertEquals(3, ext.categoryTags.size)
    }

    @Test
    fun `the user bubble is the brand colour at full strength`() {
        assertEquals(CoachingColors.Spice.primary, ext.userBubble)
        assertEquals("2514BE", hex(ext.userBubble))
    }

    @Test
    fun `the user bubble label agrees with every other primary surface`() {
        // Not contrast-picked: the bubble IS primary, so a bubble that chose its own
        // label could put dark text on the colour the screen header puts white text on.
        assertEquals(CoachingColors.Spice.onPrimary, ext.onUserBubble)
    }

    @Test
    fun `the assistant bubble is derived from primary at a readable tint`() {
        assertEquals("F2F1FB", hex(ext.assistantBubble))
    }

    @Test
    fun `bubble on-colours clear WCAG AA against their bubbles`() {
        assertTrue(ext.userBubble.contrastAgainst(ext.onUserBubble) >= 4.5f)
        assertTrue(ext.assistantBubble.contrastAgainst(ext.onAssistantBubble) >= 4.5f)
    }

    @Test
    fun `the UHIS magenta clears AA on the bubble at full strength`() {
        // The hue that failed at 85%. At full strength white reaches 4.71:1.
        val uhis = CoachingColors.Spice.copy(primary = Color(0xFFD6218C)).extended()
        assertTrue(uhis.userBubble.contrastAgainst(uhis.onUserBubble) >= 4.5f)
    }

    @Test
    fun `the attention label stays white on the SPICE brick red`() {
        // All three badges hardcoded Color.White before the migration, so with the
        // default palette this must resolve to exactly that.
        assertEquals(Color.White, ext.onAttention)
        assertTrue(ext.attention.contrastAgainst(ext.onAttention) >= 4.5f)
    }

    @Test
    fun `the header gradient runs from primary to a lighter blend`() {
        assertEquals(2, ext.headerGradient.size)
        assertEquals("2514BE", hex(ext.headerGradient[0]))
        assertEquals("4637C8", hex(ext.headerGradient[1]))
    }

    @Test
    fun `overriding primary carries the bubbles and header with it`() {
        val teal = CoachingColors.Spice.copy(primary = Color(0xFF00695C)).extended()
        // Exact hexes are not pinned here: one channel of this particular blend lands on
        // a .5 rounding boundary, which would make the assertion brittle for no gain.
        // What matters is that the derivation tracks primary rather than a stale token.
        assertNotEquals(ext.userBubble, teal.userBubble)
        assertNotEquals(ext.assistantBubble, teal.assistantBubble)
        assertEquals(Color(0xFF00695C), teal.userBubble)
        assertEquals(Color(0xFF00695C), teal.headerGradient[0])
        assertNotEquals(ext.headerGradient[1], teal.headerGradient[1])
    }

    @Test
    fun `a pale attention colour flips its label to dark for contrast`() {
        val pale = CoachingColors.Spice.copy(attention = Color(0xFFFFE082)).extended()
        assertEquals(pale.textStrong, pale.onAttention)
        assertTrue(pale.attention.contrastAgainst(pale.onAttention) >= 4.5f)
    }
}
