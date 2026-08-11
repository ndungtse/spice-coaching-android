package com.medtroniclabs.microcoaching.ui.common

import com.medtroniclabs.microcoaching.sync.SyncErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The classifier that keeps raw exception text off a CHW's screen. Every branch must land on
 * a taxonomy case carrying a localized string — nothing here should ever pass a
 * `Throwable.message` through.
 */
class CoachingErrorTest {

    @Test
    fun `network errors are offline regardless of the reported connectivity`() {
        assertEquals(CoachingError.Offline, CoachingError.from(SyncErrorKind.NETWORK, offline = false))
        assertEquals(CoachingError.Offline, CoachingError.from(SyncErrorKind.NETWORK, offline = true))
    }

    @Test
    fun `being offline outranks the reported error kind`() {
        assertEquals(CoachingError.Offline, CoachingError.from(SyncErrorKind.HTTP_SERVER, offline = true))
    }

    @Test
    fun `http kinds map to distinct user-facing cases`() {
        assertEquals(CoachingError.Server, CoachingError.from(SyncErrorKind.HTTP_SERVER, offline = false))
        assertEquals(CoachingError.NotAllowed, CoachingError.from(SyncErrorKind.HTTP_CLIENT, offline = false))
        assertEquals(CoachingError.Unknown, CoachingError.from(SyncErrorKind.UNEXPECTED, offline = false))
    }

    @Test
    fun `a null kind degrades to unknown rather than throwing`() {
        assertEquals(CoachingError.Unknown, CoachingError.from(kind = null, offline = false))
    }

    @Test
    fun `an IOException is offline, never a raw message`() {
        val error = CoachingError.from(IOException("Unable to resolve host \"api.example.com\""), offline = false)

        assertEquals(CoachingError.Offline, error)
    }

    @Test
    fun `a non-IO exception is unknown`() {
        assertEquals(CoachingError.Unknown, CoachingError.from(IllegalStateException("HTTP 502"), offline = false))
    }

    @Test
    fun `only the offline case is flagged offline`() {
        assertTrue(CoachingError.Offline.isOffline)
        assertFalse(CoachingError.Server.isOffline)
        assertFalse(CoachingError.Unknown.isOffline)
    }

    @Test
    fun `every case carries a distinct telemetry key`() {
        val keys = listOf(
            CoachingError.Offline, CoachingError.Server, CoachingError.NotAllowed,
            CoachingError.NoBackend, CoachingError.Unknown,
        ).map { it.outcomeKey }

        assertEquals(keys.size, keys.toSet().size)
    }
}
