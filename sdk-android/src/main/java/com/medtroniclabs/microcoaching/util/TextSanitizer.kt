package com.medtroniclabs.microcoaching.util

/**
 * Removes emoji and pictographic symbols from free-text input, leaving plain
 * Bangla / English prose. Useful for any user-entered text that is stored or
 * shown to staff and shouldn't carry decorative emoji.
 *
 * Iterates by Unicode **code point** so supplementary-plane emoji (surrogate
 * pairs) and multi-code-point sequences (ZWJ joins, skin-tone / flag modifiers,
 * keycaps) are dropped cleanly, never split into orphan surrogates. Ranges
 * cover the emoji/pictograph blocks only; Bangla (U+0980–U+09FF), Latin,
 * digits, and ordinary punctuation are untouched.
 */
fun stripEmoji(input: String): String {
    if (input.isEmpty()) return input
    val out = StringBuilder(input.length)
    var i = 0
    while (i < input.length) {
        val cp = input.codePointAt(i)
        if (!isEmojiCodePoint(cp)) out.appendCodePoint(cp)
        i += Character.charCount(cp)
    }
    return out.toString()
}

private fun isEmojiCodePoint(cp: Int): Boolean = when (cp) {
    0x200D -> true // zero-width joiner (glues emoji sequences)
    0x20E3 -> true // combining enclosing keycap
    in 0xFE00..0xFE0F -> true // variation selectors (incl. VS16 emoji presentation)
    in 0x2600..0x26FF -> true // miscellaneous symbols (☀ ⚠ …)
    in 0x2700..0x27BF -> true // dingbats (✂ ✅ …)
    in 0x2B00..0x2BFF -> true // misc symbols & arrows (⭐ ⬛ …)
    in 0x1F000..0x1F02F -> true // mahjong / dominoes / playing cards
    in 0x1F0A0..0x1F0FF -> true // playing cards
    in 0x1F1E6..0x1F1FF -> true // regional indicators (flags)
    in 0x1F300..0x1F5FF -> true // misc symbols & pictographs (incl. skin-tone modifiers)
    in 0x1F600..0x1F64F -> true // emoticons
    in 0x1F680..0x1F6FF -> true // transport & map
    in 0x1F900..0x1F9FF -> true // supplemental symbols & pictographs
    in 0x1FA70..0x1FAFF -> true // symbols & pictographs extended-A
    else -> false
}
