package com.medtroniclabs.microcoaching.ai.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate in front of a 171 MB download. Every skip reason is distinct because they
 * are diagnosed from a single logcat line on a device that is behaving as designed —
 * "dense retrieval is off" and "this phone is too small for it" are not the same
 * finding, and neither is a failure.
 */
class EncoderModelRuleTest {

    private fun evaluate(
        enabled: Boolean = true,
        lowEnd: Boolean = false,
        present: Boolean = false,
        token: Boolean = true,
    ) = EncoderModelRule.evaluate(
        enableDenseRetrieval = enabled,
        isLowEndDevice = lowEnd,
        filesPresent = present,
        hasAccessToken = token,
    )

    @Test
    fun `the flag being off skips everything`() {
        assertEquals(EncoderVerdict.SKIPPED_FLAG_OFF, evaluate(enabled = false))
        // Even with the model already on disk: the dense branch never runs, so
        // holding the encoder open would cost memory for nothing.
        assertEquals(EncoderVerdict.SKIPPED_FLAG_OFF, evaluate(enabled = false, present = true))
    }

    @Test
    fun `a low-end device is skipped even when the files are already there`() {
        // 171 MB of weights plus ~110 MB resident is not affordable below the 3 GB
        // tier, and a present file is no evidence that it is.
        assertEquals(EncoderVerdict.SKIPPED_LOW_END, evaluate(lowEnd = true))
        assertEquals(EncoderVerdict.SKIPPED_LOW_END, evaluate(lowEnd = true, present = true))
    }

    @Test
    fun `present files are ready`() {
        assertEquals(EncoderVerdict.READY, evaluate(present = true))
    }

    @Test
    fun `a missing token blocks rather than starting a doomed download`() {
        // The EmbeddingGemma repo is gated: without a token every byte request is a 401.
        assertEquals(EncoderVerdict.BLOCKED_NO_TOKEN, evaluate(token = false))
    }

    @Test
    fun `a present model needs no token`() {
        assertEquals(EncoderVerdict.READY, evaluate(present = true, token = false))
    }

    @Test
    fun `an eligible device with no model downloads`() {
        assertEquals(EncoderVerdict.DOWNLOAD, evaluate())
    }

    // ── does the encoder belong in the download plan ──────────────────────────
    // Both artifacts ride one progress bar, so this decides the denominator the user
    // is shown before they agree to anything.

    @Test
    fun `an eligible device plans for the encoder whether or not it has the files`() {
        // READY counts as well as DOWNLOAD: files already on disk are skipped by the
        // worker but still contribute to the total, so a bar reading 40% means 40% of
        // what was agreed to rather than 40% of whatever happens to be missing.
        with(EncoderModelRule) {
            assertTrue(evaluate(true, false, filesPresent = false, hasAccessToken = true).wantsEncoder())
            assertTrue(evaluate(true, false, filesPresent = true, hasAccessToken = true).wantsEncoder())
        }
    }

    @Test
    fun `every skip keeps the encoder out of the plan`() {
        with(EncoderModelRule) {
            assertFalse(evaluate(false, false, filesPresent = false, hasAccessToken = true).wantsEncoder())
            assertFalse(evaluate(true, true, filesPresent = false, hasAccessToken = true).wantsEncoder())
            assertFalse(evaluate(true, false, filesPresent = false, hasAccessToken = false).wantsEncoder())
        }
    }

    // ── readiness: the language model waits for the encoder, but not forever ──

    @Test
    fun `a wanted encoder that has not arrived holds the mode back`() {
        // "Simple words" must not announce itself while 171 MB is still moving, or the
        // download appears to finish twice.
        assertTrue(EncoderModelRule.shouldAwaitEncoder(wantsEncoder = true, filesPresent = false, gaveUp = false))
    }

    @Test
    fun `a complete encoder releases the mode`() {
        assertFalse(EncoderModelRule.shouldAwaitEncoder(wantsEncoder = true, filesPresent = true, gaveUp = false))
    }

    @Test
    fun `an encoder that failed for good releases the mode anyway`() {
        // The fallback: losing the retrieval improvement must not strand a language model
        // that already arrived, so a CHW on a weak connection still gets simple words.
        assertFalse(EncoderModelRule.shouldAwaitEncoder(wantsEncoder = true, filesPresent = false, gaveUp = true))
    }

    @Test
    fun `nothing is awaited when the encoder was never wanted`() {
        assertFalse(EncoderModelRule.shouldAwaitEncoder(wantsEncoder = false, filesPresent = false, gaveUp = false))
    }

    // ── holding the mode back is only honest while something is fetching ─────

    @Test
    fun `an in-flight download holds the mode back`() {
        assertEquals(
            EncoderWaitAction.HOLD,
            EncoderModelRule.encoderWaitAction(awaiting = true, downloadActive = true, autoDownloadAllowed = true),
        )
    }

    @Test
    fun `an outstanding encoder with no worker running schedules one`() {
        // Without this the bar sits at "Adding smarter search - 0%" forever: the state
        // says a download is happening while nothing is actually fetching anything.
        assertEquals(
            EncoderWaitAction.SCHEDULE,
            EncoderModelRule.encoderWaitAction(awaiting = true, downloadActive = false, autoDownloadAllowed = true),
        )
    }

    @Test
    fun `a host that forbids automatic downloads releases the mode instead of stalling`() {
        // PROVIDED and MANUAL mean the SDK must not fetch on its own. Holding would
        // strand a language model that is sitting on disk and perfectly usable.
        assertEquals(
            EncoderWaitAction.RELEASE,
            EncoderModelRule.encoderWaitAction(awaiting = true, downloadActive = false, autoDownloadAllowed = false),
        )
    }

    @Test
    fun `nothing outstanding releases the mode`() {
        assertEquals(
            EncoderWaitAction.RELEASE,
            EncoderModelRule.encoderWaitAction(awaiting = false, downloadActive = false, autoDownloadAllowed = true),
        )
    }
}
