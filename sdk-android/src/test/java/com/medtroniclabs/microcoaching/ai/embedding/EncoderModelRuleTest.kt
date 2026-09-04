package com.medtroniclabs.microcoaching.ai.embedding

import org.junit.Assert.assertEquals
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
}
