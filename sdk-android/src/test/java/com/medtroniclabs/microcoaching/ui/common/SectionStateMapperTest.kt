package com.medtroniclabs.microcoaching.ui.common

import com.medtroniclabs.microcoaching.sync.SyncErrorKind
import com.medtroniclabs.microcoaching.sync.SyncOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the empty-vs-error decision that scopes every data-backed section.
 *
 * The first test is the regression guard for the reported bug: an empty result after a
 * *successful* sync is an empty list, not a failure. Treating it as one is what blanked the
 * whole Coaching tab — including the Training and Knowledge sub-tabs, which read entirely
 * different tables.
 */
class SectionStateMapperTest {

    private val succeeded = SyncOutcome.Succeeded(atMs = 1_000L)
    private val failed = SyncOutcome.Failed(SyncErrorKind.HTTP_SERVER, "boom", atMs = 1_000L)

    @Test
    fun `empty rows after a successful sync is Ready-empty, not Failed`() {
        val state = sectionStateFor(rows = emptyList<String>(), outcome = succeeded, offline = false)

        assertEquals(SectionState.Ready(emptyList<String>()), state)
    }

    @Test
    fun `empty rows after a failed sync surfaces the error`() {
        val state = sectionStateFor(rows = emptyList<String>(), outcome = failed, offline = false)

        assertEquals(SectionState.Failed<List<String>>(CoachingError.Server), state)
    }

    @Test
    fun `empty rows with no sync attempted yet stays Loading`() {
        val state = sectionStateFor(rows = emptyList<String>(), outcome = SyncOutcome.Unknown, offline = false)

        assertEquals(SectionState.Loading, state)
    }

    @Test
    fun `cached rows always win over a failure, flagged stale`() {
        val state = sectionStateFor(rows = listOf("a", "b"), outcome = failed, offline = false)

        assertEquals(SectionState.Ready(listOf("a", "b"), stale = true), state)
    }

    @Test
    fun `cached rows after a successful sync are not stale`() {
        val state = sectionStateFor(rows = listOf("a"), outcome = succeeded, offline = false)

        assertEquals(SectionState.Ready(listOf("a"), stale = false), state)
    }

    @Test
    fun `offline failure is reported as offline, not as a server error`() {
        val state = sectionStateFor(rows = emptyList<String>(), outcome = failed, offline = true)

        assertEquals(SectionState.Failed<List<String>>(CoachingError.Offline), state)
        assertTrue((state as SectionState.Failed).error.isOffline)
    }
}
