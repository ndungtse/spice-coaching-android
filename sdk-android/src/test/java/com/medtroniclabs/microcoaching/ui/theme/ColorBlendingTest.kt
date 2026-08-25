package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the derivation maths that the chat bubbles, the SK detail header, the attention
 * badges and every progress track depend on. Two failures this guards:
 *
 *  * Using Compose's `lerp` instead of sRGB compositing. `lerp` interpolates
 *    perceptually and returns #556CD7 at 0.70 where sRGB returns #665BD2, so the
 *    "blend looks identical to alpha over the surface" promise silently breaks.
 *  * A derived user bubble whose white text drops below WCAG AA. Today's hardcoded
 *    #0085CA is already at 4.03:1; the derivation must not make that worse.
 */
class ColorBlendingTest {

    private val primary = Color(0xFF2514BE)
    private val white = Color.White

    private fun hex(c: Color): String = String.format(
        "%02X%02X%02X",
        Math.round(c.red * 255),
        Math.round(c.green * 255),
        Math.round(c.blue * 255),
    )

    @Test
    fun `blending the brand primary at 70 percent composites in sRGB not perceptually`() {
        // The value Compose's own lerp would give here is #556CD7. This is the whole
        // reason blendOver exists rather than delegating to lerp.
        assertEquals("665BD2", hex(blendOver(white, primary, 0.70f)))
    }

    @Test
    fun `blending the brand primary at 6 percent gives the documented assistant bubble`() {
        assertEquals("F2F1FB", hex(blendOver(white, primary, 0.06f)))
    }

    @Test
    fun `blending the brand primary at 85 percent gives the documented header gradient end`() {
        assertEquals("4637C8", hex(blendOver(white, primary, 0.85f)))
    }

    @Test
    fun `a fraction of zero returns the surface untouched`() {
        assertEquals("FFFFFF", hex(blendOver(white, primary, 0f)))
    }

    @Test
    fun `a fraction of one returns the colour untouched`() {
        assertEquals("2514BE", hex(blendOver(white, primary, 1f)))
    }

    @Test
    fun `the old hardcoded user bubble did not clear WCAG AA`() {
        // Documents why moving the bubble onto the brand colour was an accessibility
        // improvement rather than merely a restyle. If this ever passes, the premise
        // has shifted.
        assertTrue(Color(0xFF0085CA).contrastAgainst(white) < 4.5f)
    }

    @Test
    fun `a blend that is too light for white and too dark for black can exist`() {
        // Why the outgoing bubble is no longer a blend. Blending the UHIS magenta to
        // 85% lands in a band where neither on-colour clears AA: white scores 3.96 and
        // near-black 4.49. A dark brand hue has no such band, which is exactly the
        // problem — the safe blend factor depends on the host's hue.
        val magentaAt85 = blendOver(white, Color(0xFFD6218C), 0.85f)
        assertTrue(magentaAt85.contrastAgainst(white) < 4.5f)
        assertTrue(magentaAt85.contrastAgainst(Color(0xFF101828)) < 4.5f)
    }

    @Test
    fun `a track is the fill at low alpha so it reads under any bar colour`() {
        assertEquals(0.15f, trackFor(primary).alpha, 0.001f)
        assertEquals(primary.red, trackFor(primary).red, 0.001f)
    }

    @Test
    fun `on-colour selection picks the light option over a dark background`() {
        assertEquals(Color.White, onColorFor(primary, Color.White, Color(0xFF101828)))
    }

    @Test
    fun `on-colour selection picks the dark option over a pale background`() {
        assertEquals(
            Color(0xFF101828),
            onColorFor(Color(0xFFFFF3CD), Color.White, Color(0xFF101828)),
        )
    }
}
