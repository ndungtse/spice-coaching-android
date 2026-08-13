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
        durationMs: Long = 300_000,
    ) = AssignedVideoEntity(
        videoId = "v1",
        chwId = "chw-1",
        title = "T",
        durationMs = durationMs,
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
        val prev = VideoProgressRow("v1", lastPositionMs = 250_000, percentWatched = 83.0, completed = false, lastWatchedAt = "2026-07-28T12:00:00Z", durationMs = 300_000)

        val merged = mergeVideoProgress(synced, prev)

        assertEquals(250_000L, merged.lastPositionMs)
        assertEquals(83.0, merged.percentWatched, 0.0001)
    }

    @Test
    fun `server progress ahead of local wins`() {
        val synced = row(pos = 250_000, pct = 83.0)
        val prev = VideoProgressRow("v1", lastPositionMs = 100_000, percentWatched = 33.0, completed = false, lastWatchedAt = null, durationMs = 300_000)

        val merged = mergeVideoProgress(synced, prev)

        assertEquals(250_000L, merged.lastPositionMs)
        assertEquals(83.0, merged.percentWatched, 0.0001)
    }

    @Test
    fun `completed stays true once either side set it`() {
        val syncedNotDone = row(pos = 0, pct = 0.0, completed = false)
        val prevDone = VideoProgressRow("v1", 300_000, 100.0, completed = true, lastWatchedAt = null, durationMs = 300_000)
        assertTrue(mergeVideoProgress(syncedNotDone, prevDone).completed)

        val syncedDone = row(pos = 300_000, pct = 100.0, completed = true)
        val prevNotDone = VideoProgressRow("v1", 10_000, 3.0, completed = false, lastWatchedAt = null, durationMs = 300_000)
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
            durationMs = 300_000,
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
        val prev = VideoProgressRow("v1", 300_000, 100.0, completed = true, lastWatchedAt = null, durationMs = 300_000)

        val merged = mergeVideoProgress(stale, prev)

        assertEquals(300_000L, merged.lastPositionMs)
        assertEquals(100.0, merged.percentWatched, 0.0001)
        assertTrue(merged.completed)
        assertFalse(merged == stale)
    }
}

/**
 * Duration survives a sync that doesn't know it.
 *
 * The catalogue reports `duration_ms` as null until the backend has probed the
 * media, so a length the device worked out for itself — from the player, or by
 * reading the media directly — would otherwise be erased on the very next sync,
 * and the list would flicker back to showing none.
 */
class AssignedVideoDurationMergeTest {

    private fun row(durationMs: Long) = AssignedVideoEntity(
        videoId = "v1",
        chwId = "chw-1",
        title = "T",
        durationMs = durationMs,
    )

    private fun prev(durationMs: Long) = VideoProgressRow(
        videoId = "v1",
        lastPositionMs = 0,
        percentWatched = 0.0,
        completed = false,
        lastWatchedAt = null,
        durationMs = durationMs,
    )

    @Test
    fun `a locally-known duration survives a catalogue row that has none`() {
        val merged = mergeVideoProgress(row(durationMs = 0), prev(durationMs = 420_000))
        assertEquals(420_000L, merged.durationMs)
    }

    @Test
    fun `the backend value wins once it arrives`() {
        val merged = mergeVideoProgress(row(durationMs = 500_000), prev(durationMs = 420_000))
        assertEquals(500_000L, merged.durationMs)
    }

    @Test
    fun `unknown on both sides stays unknown`() {
        assertEquals(0L, mergeVideoProgress(row(durationMs = 0), prev(durationMs = 0)).durationMs)
    }

    @Test
    fun `a first-time row keeps whatever the catalogue supplied`() {
        assertEquals(420_000L, mergeVideoProgress(row(durationMs = 420_000), prev = null).durationMs)
    }
}
