package com.medtroniclabs.microcoaching.ai.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BanglaTokenizerTest {

    @Test
    fun `NFC normalizes composed and decomposed forms to the same tokens`() {
        // "café" composed (é = U+00E9) vs decomposed (e + combining acute U+0301).
        val composed = BanglaTokenizer.tokenize("caf\u00E9 visit")
        val decomposed = BanglaTokenizer.tokenize("cafe\u0301 visit")
        assertEquals("NFC must collapse composed/decomposed to identical tokens", composed, decomposed)
    }

    @Test
    fun `zero-width joiners are stripped before tokenizing`() {
        // ZWNJ (U+200C) embedded inside a word must not split or alter the token.
        val withZwnj = BanglaTokenizer.tokenize("vaccine‌dose")
        val plain = BanglaTokenizer.tokenize("vaccinedose")
        assertEquals(plain, withZwnj)
    }

    @Test
    fun `bangla bigrams are symmetric so an inflected query overlaps the base form`() {
        // টিকা (vaccine) vs টিকার (of the vaccine — inflected). Whole-word tokens
        // differ, but the shared character bigrams must overlap on both sides.
        val base = BanglaTokenizer.tokenize("টিকা").toSet()
        val inflected = BanglaTokenizer.tokenize("টিকার").toSet()
        assertTrue(
            "inflected Bangla must share at least one bigram with the base form",
            base.intersect(inflected).isNotEmpty(),
        )
    }

    @Test
    fun `english stays whole-word with no character bigrams`() {
        val tokens = BanglaTokenizer.tokenize("referral threshold")
        assertTrue(tokens.contains("referral"))
        assertTrue(tokens.contains("threshold"))
        // No 2-char Latin bigrams like "re"/"th" should be emitted for ASCII.
        assertFalse(tokens.contains("re"))
        assertFalse(tokens.contains("th"))
    }
}
