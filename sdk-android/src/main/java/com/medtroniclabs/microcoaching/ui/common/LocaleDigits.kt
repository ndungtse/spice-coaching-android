package com.medtroniclabs.microcoaching.ui.common

import com.medtroniclabs.microcoaching.Language

/** ০ ১ ২ ৩ ৪ ৫ ৬ ৭ ৮ ৯ — Bengali (Bangla) digit glyphs, indexed 0–9. */
private val BENGALI_DIGITS = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

/**
 * Maps ASCII digits 0–9 in [text] to Bengali digit glyphs when [language] is
 * [Language.BANGLA]; every other character (and the whole string in English)
 * passes through unchanged.
 *
 * Use this for numbers built with `Int.toString()` / Kotlin string templates,
 * which always emit Western digits regardless of locale — e.g. count badges.
 * Strings formatted from resources with `%d` under a Bangla configuration are
 * already localized and don't need this.
 */
fun localizeDigits(text: String, language: Language): String {
    if (language != Language.BANGLA) return text
    return buildString(text.length) {
        for (c in text) append(if (c in '0'..'9') BENGALI_DIGITS[c - '0'] else c)
    }
}
