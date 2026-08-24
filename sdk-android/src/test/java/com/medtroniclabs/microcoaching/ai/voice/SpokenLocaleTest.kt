package com.medtroniclabs.microcoaching.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Pins how a card's script picks its voice. Getting this wrong is not a crash — it is a
 * CHW hearing Bengali text read by an English voice, which is unintelligible rather than
 * merely wrong.
 */
class SpokenLocaleTest {

    private val default = ENGLISH_TTS_LOCALE

    @Test
    fun `bengali text picks the bangla voice`() {
        assertEquals(
            BANGLA_TTS_LOCALE,
            localeForSpokenText("প্রসবের পর মায়ের শরীরকে সুস্থ হতে", default),
        )
    }

    @Test
    fun `english text picks the english voice`() {
        assertEquals(ENGLISH_TTS_LOCALE, localeForSpokenText("Screen every visit for danger signs", default))
    }

    @Test
    fun `a bengali card carrying an english term stays in the bangla voice`() {
        // Drug names and units are routinely left in Latin script inside Bangla copy;
        // detecting "any Latin letter" would flip the whole card to an English voice.
        assertEquals(
            BANGLA_TTS_LOCALE,
            localeForSpokenText("রোগীকে প্রতিদিন Iron Folic Acid ট্যাবলেট খেতে বলুন", default),
        )
    }

    @Test
    fun `text with no letters falls back to the caller default`() {
        assertEquals(BANGLA_TTS_LOCALE, localeForSpokenText("42 — 100%", BANGLA_TTS_LOCALE))
        assertEquals(ENGLISH_TTS_LOCALE, localeForSpokenText("42 — 100%", ENGLISH_TTS_LOCALE))
    }

    @Test
    fun `blank text falls back to the caller default`() {
        assertEquals(BANGLA_TTS_LOCALE, localeForSpokenText("   ", BANGLA_TTS_LOCALE))
    }

    @Test
    fun `sdk language maps to its voice`() {
        assertEquals(BANGLA_TTS_LOCALE, ttsLocaleFor(com.medtroniclabs.microcoaching.Language.BANGLA))
        assertEquals(ENGLISH_TTS_LOCALE, ttsLocaleFor(com.medtroniclabs.microcoaching.Language.ENGLISH))
        assertEquals(Locale.US, ENGLISH_TTS_LOCALE)
    }
}
