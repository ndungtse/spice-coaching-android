package com.medtroniclabs.microcoaching.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TextSanitizerTest {

    @Test
    fun `stripEmoji removes supplementary-plane emoji without splitting surrogates`() {
        val cleaned = stripEmoji("Diabetes care 😀 for mothers 🤰🏽")
        assertEquals("Diabetes care  for mothers ", cleaned)
        // No orphan surrogate chars left behind.
        assertFalse(cleaned.any { it.isSurrogate() })
    }

    @Test
    fun `stripEmoji removes flags, ZWJ sequences, and keycaps`() {
        assertEquals("", stripEmoji("🇧🇩"))          // regional-indicator flag
        assertEquals("", stripEmoji("👨‍👩‍👧"))       // ZWJ family sequence
        assertEquals("", stripEmoji("⚠️"))            // symbol + VS16
        assertEquals("1", stripEmoji("1️⃣"))          // keycap → bare digit remains
    }

    @Test
    fun `stripEmoji keeps bangla, latin, digits, and punctuation`() {
        val text = "গর্ভাবস্থায় ডায়াবেটিস — Diabetes (type 2), 50%."
        assertEquals(text, stripEmoji(text))
    }

    @Test
    fun `stripEmoji on empty or emoji-free text is a no-op`() {
        assertEquals("", stripEmoji(""))
        assertEquals("plain text", stripEmoji("plain text"))
    }
}
