package com.medtroniclabs.microcoaching.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a process start makes of the smarter-search files it finds. */
class SearchSetupRuleTest {

    private fun verdict(
        complete: Boolean = false,
        paused: Boolean = false,
        active: Boolean = false,
    ) = SearchSetupRule.verdict(
        encoderComplete = complete,
        userPaused = paused,
        downloadWorkActive = active,
    )

    /** The upgrade path: an earlier build's encoder is on disk, and nothing should download. */
    @Test
    fun `complete files are ready even with a stale pause or a running worker`() {
        assertEquals(SearchSetupVerdict.READY, verdict(complete = true))
        assertEquals(SearchSetupVerdict.READY, verdict(complete = true, paused = true, active = true))
    }

    @Test
    fun `a pause is restored as paused, even before any bytes landed`() {
        // Read as idle, chat open would restart the download the user paused.
        assertEquals(SearchSetupVerdict.RESUMABLE_PARTIAL, verdict(paused = true))
        // A running download reports its own outcome.
        assertEquals(SearchSetupVerdict.LEAVE_IN_FLIGHT, verdict(paused = true, active = true))
        // Unpaused, a partial stays on disk for the next download to resume onto.
        assertEquals(SearchSetupVerdict.IDLE, verdict())
    }

    @Test
    fun `a language model is removed only when the rewrite is off`() {
        assertTrue(SearchSetupRule.removeLegacyLanguageModel(rewriteEnabled = false, languageModelOnDisk = true))
        assertFalse(SearchSetupRule.removeLegacyLanguageModel(rewriteEnabled = true, languageModelOnDisk = true))
    }
}
