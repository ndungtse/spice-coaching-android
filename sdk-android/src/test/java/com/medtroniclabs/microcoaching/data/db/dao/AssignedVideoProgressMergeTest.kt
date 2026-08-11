package com.medtroniclabs.microcoaching.data.db.dao

import com.medtroniclabs.microcoaching.data.db.entity.AssignedVideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the monotonic progress merge [mergeVideoProgress] runs during an
 * assigned-videos reconcile: a lower or delayed server value must never regress
 * on-device progress.
 *
 * This carries more weight than it looks. The source-document catalogue returns no
 * watch progress at all, so every reconcile now supplies zeroes and this merge is
 * the only thing keeping a CHW's resume position alive across syncs.
 */
class AssignedVideoProgressMergeTest {

    private fun row(
        pos: Long = 0,
        pct: Double = 0.0,
        completed: Boolean = false,
        watchedAt: String? = null,
    ) = AssignedVideoEntity(
        videoId = "v1",
        chwId = "chw-1",
        title = "T",
        durationMs = 300_000,
        lastPositionMs = pos,
        percentWatched = pct,
        completed = completed,
        lastWatchedAt = watchedAt,
    )

    @Test
    fun `no prior progress returns the synced row unchanged`() {
        val synced = row(pos = 100, pct = 30.0)
        assertEquals(synced, mergeVideoProgress(synced, prev = null))
    }

    @Test
    fun `local progress ahead of server is kept`() {
        // Server hasn't yet folded in the CHW's latest playback (async worker).
        val synced = row(pos = 100_000, pct = 33.0, watchedAt = "2026-07-28T10:00:00Z")
        val prev = VideoProgressRow("v1", lastPositionMs = 250_000, percentWatched = 83.0, completed = false, lastWatchedAt = "2026-07-28T12:00:00Z")

        val merged = mergeVideoProgress(synced, prev)

        assertEquals(250_000L, merged.lastPositionMs)
        assertEquals(83.0, merged.percentWatched, 0.0001)
    }

    @Test
    fun `server progress ahead of local wins`() {
        val synced = row(pos = 250_000, pct = 83.0)
        val prev = VideoProgressRow("v1", lastPositionMs = 100_000, percentWatched = 33.0, completed = false, lastWatchedAt = null)

        val merged = mergeVideoProgress(synced, prev)

        assertEquals(250_000L, merged.lastPositionMs)
        assertEquals(83.0, merged.percentWatched, 0.0001)
    }

    @Test
    fun `completed stays true once either side set it`() {
        val syncedNotDone = row(pos = 0, pct = 0.0, completed = false)
        val prevDone = VideoProgressRow("v1", 300_000, 100.0, completed = true, lastWatchedAt = null)
        assertTrue(mergeVideoProgress(syncedNotDone, prevDone).completed)

        val syncedDone = row(pos = 300_000, pct = 100.0, completed = true)
        val prevNotDone = VideoProgressRow("v1", 10_000, 3.0, completed = false, lastWatchedAt = null)
        assertTrue(mergeVideoProgress(syncedDone, prevNotDone).completed)
    }

    @Test
    fun `a catalogue row carrying no progress preserves everything local`() {
        // What every reconcile now looks like: the catalogue has no progress fields,
        // so the mapped row holds defaults. All four columns must survive.
        val fromCatalogue = row(pos = 0, pct = 0.0, completed = false, watchedAt = null)
        val prev = VideoProgressRow(
            "v1",
            lastPositionMs = 180_000,
            percentWatched = 60.0,
            completed = true,
            lastWatchedAt = "2026-08-09T09:00:00Z",
        )

        val merged = mergeVideoProgress(fromCatalogue, prev)

        assertEquals(180_000L, merged.lastPositionMs)
        assertEquals(60.0, merged.percentWatched, 0.0001)
        assertTrue(merged.completed)
        assertEquals("2026-08-09T09:00:00Z", merged.lastWatchedAt)
    }

    @Test
    fun `a delayed lower sync does not regress a completed video`() {
        val stale = row(pos = 5_000, pct = 2.0, completed = false)
        val prev = VideoProgressRow("v1", 300_000, 100.0, completed = true, lastWatchedAt = null)

        val merged = mergeVideoProgress(stale, prev)

        assertEquals(300_000L, merged.lastPositionMs)
        assertEquals(100.0, merged.percentWatched, 0.0001)
        assertTrue(merged.completed)
        assertFalse(merged == stale)
    }
}
