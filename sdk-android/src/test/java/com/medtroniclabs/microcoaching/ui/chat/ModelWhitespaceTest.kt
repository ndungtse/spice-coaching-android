package com.medtroniclabs.microcoaching.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the cleanup of escape sequences the model writes as literal characters.
 *
 * A backslash is not whitespace, so trimming leaves it in place and it reaches the CHW as
 * visible punctuation — an answer opening with a stray "\n" before its first word.
 */
class ModelWhitespaceTest {

    private fun clean(s: String) = ChatViewModel.normalizeModelWhitespace(s)

    /** The observed defect: a literal escape ahead of the first word. */
    @Test
    fun `strips a leading literal newline escape`() {
        assertEquals(
            "Your first advice for this baby will be to refer the mother urgently.",
            clean("""\nYour first advice for this baby will be to refer the mother urgently."""),
        )
    }

    @Test
    fun `converts literal escapes inside a sentence to a single space`() {
        assertEquals(
            "Refer urgently. Watch for fever.",
            clean("""Refer urgently.\nWatch for fever."""),
        )
        assertEquals("a b", clean("""a\tb"""))
        assertEquals("a b", clean("""a\r\nb"""))
    }

    /** Real newlines are structure the model meant; only excess runs collapse. */
    @Test
    fun `keeps real paragraph breaks and collapses excessive ones`() {
        assertEquals("one\n\ntwo", clean("one\n\n\n\ntwo"))
        assertEquals("one\ntwo", clean("one\ntwo"))
    }

    @Test
    fun `trims surrounding and per-line whitespace`() {
        assertEquals("one\ntwo", clean("  one  \n   two   "))
        assertEquals("a b", clean("a      b"))
    }

    /** A lone backslash that is not an escape must survive untouched. */
    @Test
    fun `leaves unrelated backslashes alone`() {
        assertEquals("""use A\B ratio""", clean("""use A\B ratio"""))
    }

    @Test
    fun `empty and blank input stay empty`() {
        assertEquals("", clean(""))
        assertEquals("", clean("   \n  "))
    }
}
