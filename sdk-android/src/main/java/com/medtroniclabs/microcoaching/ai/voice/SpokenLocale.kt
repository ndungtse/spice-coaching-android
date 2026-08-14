package com.medtroniclabs.microcoaching.ai.voice

import com.medtroniclabs.microcoaching.Language
import java.util.Locale

/** The two voices the catalogue is authored for. */
val BANGLA_TTS_LOCALE: Locale = Locale("bn", "BD")
val ENGLISH_TTS_LOCALE: Locale = Locale.US

/** Bengali Unicode block — the script every Bangla string in the catalogue is written in. */
private val BENGALI_BLOCK = 'ঀ'..'৿'

/** The voice matching an SDK [Language] — the default when text alone can't decide. */
fun ttsLocaleFor(language: Language): Locale = when (language) {
    Language.BANGLA -> BANGLA_TTS_LOCALE
    Language.ENGLISH -> ENGLISH_TTS_LOCALE
}

/**
 * Pick the voice for [text] from the script it is written in, so a card is never read
 * aloud by the wrong-language voice.
 *
 * The SDK language can't answer this on its own: content is bilingual by column and
 * `LocalizedText.forLang` serves the other language when one side is blank, so a
 * Bangla-configured install can be displaying English text. Letters are counted rather
 * than merely detected, which keeps a Bengali card carrying an English drug name in a
 * Bengali voice. Falls back to [default] when there are no letters to judge by.
 */
fun localeForSpokenText(text: String, default: Locale): Locale {
    var bengali = 0
    var latin = 0
    for (ch in text) {
        when {
            ch in BENGALI_BLOCK -> bengali++
            ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
        }
    }
    return when {
        bengali == 0 && latin == 0 -> default
        bengali >= latin -> BANGLA_TTS_LOCALE
        else -> ENGLISH_TTS_LOCALE
    }
}
