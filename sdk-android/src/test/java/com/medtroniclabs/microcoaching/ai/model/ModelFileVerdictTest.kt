package com.medtroniclabs.microcoaching.ai.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the reconcile truth table, whose whole job is to separate an incomplete file from a
 * corrupt one. The two are byte-for-byte identical — a `.task` keeps its central directory at
 * the end, so every partial fails the structural check — and only the surrounding state
 * distinguishes them. Each case below is a way that state could be misread into deleting a
 * file that still had a future.
 */
class ModelFileVerdictTest {

    private fun verdict(
        prefsReady: Boolean = false,
        fileExists: Boolean = true,
        structurallyValid: Boolean = false,
        downloadWorkActive: Boolean = false,
        userPaused: Boolean = false,
    ) = modelFileVerdict(prefsReady, fileExists, structurallyValid, downloadWorkActive, userPaused)

    @Test
    fun `valid file is adopted whether or not the flag agrees`() {
        assertEquals(
            ModelFileVerdict.ADOPT_READY,
            verdict(prefsReady = true, structurallyValid = true),
        )
        // Sideloaded, or downloaded before the flag existed — the file decides, not the flag.
        assertEquals(
            ModelFileVerdict.ADOPT_READY,
            verdict(prefsReady = false, structurallyValid = true),
        )
    }

    @Test
    fun `flag without a file is stale`() {
        assertEquals(
            ModelFileVerdict.CLEAR_STALE_FLAG,
            verdict(prefsReady = true, fileExists = false),
        )
    }

    @Test
    fun `nothing on disk and no flag is a no-op`() {
        assertEquals(ModelFileVerdict.NO_OP, verdict(prefsReady = false, fileExists = false))
    }

    @Test
    fun `partial with a live worker is left alone`() {
        assertEquals(
            ModelFileVerdict.LEAVE_IN_FLIGHT,
            verdict(structurallyValid = false, downloadWorkActive = true),
        )
    }

    /**
     * The regression this table exists for. Pausing cancels the WorkManager job, so on the
     * next process start the work reads as finished while the partial remains — the exact
     * shape of a failed download. Read without the stored pause, the only available verdict
     * is corruption, and the user loses their progress to a normal pause.
     */
    @Test
    fun `paused partial is resumable, not corrupt`() {
        assertEquals(
            ModelFileVerdict.RESUMABLE_PARTIAL,
            verdict(structurallyValid = false, downloadWorkActive = false, userPaused = true),
        )
    }

    @Test
    fun `partial with no worker and no pause is corrupt`() {
        assertEquals(
            ModelFileVerdict.DELETE_CORRUPT,
            verdict(structurallyValid = false, downloadWorkActive = false, userPaused = false),
        )
    }

    /** A live worker outranks the pause flag: bytes arriving means the pause is already over. */
    @Test
    fun `live worker outranks a stale pause flag`() {
        assertEquals(
            ModelFileVerdict.LEAVE_IN_FLIGHT,
            verdict(structurallyValid = false, downloadWorkActive = true, userPaused = true),
        )
    }

    /** A finished file has nothing to resume, so a lingering pause must not divert adoption. */
    @Test
    fun `pause flag never blocks adoption of a valid file`() {
        assertEquals(
            ModelFileVerdict.ADOPT_READY,
            verdict(structurallyValid = true, userPaused = true),
        )
    }

    /** No file means no file, whatever the transfer flags claim. */
    @Test
    fun `missing file is never resumable or corrupt`() {
        assertEquals(
            ModelFileVerdict.NO_OP,
            verdict(fileExists = false, userPaused = true),
        )
        assertEquals(
            ModelFileVerdict.NO_OP,
            verdict(fileExists = false, downloadWorkActive = true),
        )
    }

    /** No input combination may leave the file's fate undecided. */
    @Test
    fun `every combination yields a verdict`() {
        val flags = listOf(false, true)
        var count = 0
        for (ready in flags) for (exists in flags) for (valid in flags) {
            for (active in flags) for (paused in flags) {
                verdict(ready, exists, valid, active, paused)
                count++
            }
        }
        assertEquals(32, count)
    }
}
