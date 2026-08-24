package com.medtroniclabs.microcoaching.ai.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the three figures [resumePlan] derives to each other rather than to the request.
 *
 * The invariant under test: a finished download must be able to reach [ResumePlan.totalBytes]
 * exactly. Every case below is a way the offset, the total and the append flag could be read
 * from different premises, which is what lets a completed transfer measure short.
 */
class ResumePlanTest {

    @Test
    fun `fresh download counts the whole body from zero`() {
        val plan = resumePlan(existingBytes = 0L, contentLength = 304 * MB, isPartialContent = false)

        assertEquals(0L, plan.startOffset)
        assertEquals(304 * MB, plan.totalBytes)
        assertFalse(plan.append)
    }

    @Test
    fun `206 appends the remainder and totals both halves`() {
        val plan = resumePlan(existingBytes = 100 * MB, contentLength = 204 * MB, isPartialContent = true)

        assertEquals(100 * MB, plan.startOffset)
        assertEquals(304 * MB, plan.totalBytes)
        assertTrue(plan.append)
    }

    /**
     * The case that matters: a server that ignores `Range` answers 200 with the entire file.
     * Overwriting is correct, but the total must then describe only what is being written —
     * had it kept counting the discarded partial, the finished 304 MB file would be measured
     * against 404 MB and rejected as incomplete.
     */
    @Test
    fun `200 in reply to a Range request restarts and totals only the body`() {
        val plan = resumePlan(existingBytes = 100 * MB, contentLength = 304 * MB, isPartialContent = false)

        assertEquals(0L, plan.startOffset)
        assertEquals(304 * MB, plan.totalBytes)
        assertFalse(plan.append)
    }

    /** A complete transfer must land exactly on the expected total, never under it. */
    @Test
    fun `finished file reaches the expected total in both resume outcomes`() {
        val resumed = resumePlan(100 * MB, 204 * MB, isPartialContent = true)
        assertEquals(resumed.totalBytes, resumed.startOffset + 204 * MB)

        val restarted = resumePlan(100 * MB, 304 * MB, isPartialContent = false)
        assertEquals(restarted.totalBytes, restarted.startOffset + 304 * MB)
    }

    @Test
    fun `chunked response reports no total`() {
        val fresh = resumePlan(existingBytes = 0L, contentLength = -1L, isPartialContent = false)
        assertEquals(0L, fresh.totalBytes)

        val resumed = resumePlan(existingBytes = 100 * MB, contentLength = -1L, isPartialContent = true)
        assertEquals(0L, resumed.totalBytes)
        assertEquals(100 * MB, resumed.startOffset)
        assertTrue(resumed.append)
    }

    /**
     * `206` with nothing on disk cannot be a continuation, so it is treated as a fresh start.
     * Appending to a zero-length file is harmless, but claiming a resume would misreport the
     * total the moment the offset is anything but zero.
     */
    @Test
    fun `206 with an empty local file behaves as a fresh download`() {
        val plan = resumePlan(existingBytes = 0L, contentLength = 304 * MB, isPartialContent = true)

        assertEquals(0L, plan.startOffset)
        assertEquals(304 * MB, plan.totalBytes)
        assertFalse(plan.append)
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
