package com.medtroniclabs.microcoaching.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-domain sync verdicts that let each UI section scope its own failure state. */
class SyncStatusTest {

    @Test
    fun `a successful result becomes Succeeded`() {
        val outcome = ModulesResult(upsertedCount = 3).toOutcome(nowMs = 500L)

        assertEquals(SyncOutcome.Succeeded(500L), outcome)
    }

    @Test
    fun `a failed result carries its kind and raw text`() {
        val outcome = ModulesResult(error = "boom", errorKind = SyncErrorKind.HTTP_SERVER)
            .toOutcome(nowMs = 500L)

        assertEquals(SyncOutcome.Failed(SyncErrorKind.HTTP_SERVER, "boom", 500L), outcome)
    }

    @Test
    fun `a failure with no kind degrades to UNEXPECTED rather than throwing`() {
        val outcome = ModulesResult(error = "boom").toOutcome(nowMs = 1L)

        assertEquals(SyncErrorKind.UNEXPECTED, (outcome as SyncOutcome.Failed).kind)
    }

    @Test
    fun `run summary reports partial failure`() {
        val summary = InboundRunSummary(
            startedAtMs = 0L,
            finishedAtMs = 10L,
            outcomes = mapOf(
                SyncDomain.MODULES to SyncOutcome.Succeeded(5L),
                SyncDomain.PUBLISHED_DOCS to SyncOutcome.Failed(SyncErrorKind.NETWORK, null, 6L),
            ),
        )

        assertTrue(summary.anyFailure)
        assertFalse(summary.allSucceeded)
    }

    @Test
    fun `an empty run is not treated as fully succeeded`() {
        val summary = InboundRunSummary(startedAtMs = 0L, finishedAtMs = 1L, outcomes = emptyMap())

        assertFalse(summary.allSucceeded)
        assertFalse(summary.anyFailure)
    }

    @Test
    fun `unpublished domains read as Unknown, never as a failure`() = runBlocking {
        val store = SyncStatusStore()

        assertEquals(SyncOutcome.Unknown, store.outcomeFor(SyncDomain.MODULES).first())
    }

    @Test
    fun `publishing a run exposes its per-domain outcomes`() = runBlocking {
        val store = SyncStatusStore()
        val failure = SyncOutcome.Failed(SyncErrorKind.HTTP_SERVER, "500", 9L)
        store.publishRun(
            InboundRunSummary(
                startedAtMs = 1L,
                finishedAtMs = 9L,
                outcomes = mapOf(
                    SyncDomain.MODULES to SyncOutcome.Succeeded(9L),
                    SyncDomain.ASSIGNED_VIDEOS to failure,
                ),
            ),
        )

        assertEquals(SyncOutcome.Succeeded(9L), store.outcomeFor(SyncDomain.MODULES).first())
        assertEquals(failure, store.outcomeFor(SyncDomain.ASSIGNED_VIDEOS).first())
        // A domain the run didn't cover stays Unknown rather than inheriting a verdict.
        assertEquals(SyncOutcome.Unknown, store.outcomeFor(SyncDomain.CONFIG).first())
    }

    @Test
    fun `a later run overlays earlier outcomes without clearing untouched domains`() = runBlocking {
        val store = SyncStatusStore()
        store.publishRun(
            InboundRunSummary(1L, 2L, mapOf(SyncDomain.MODULES to SyncOutcome.Succeeded(2L))),
        )
        store.publishRun(
            InboundRunSummary(
                3L, 4L,
                mapOf(SyncDomain.ASSIGNED_VIDEOS to SyncOutcome.Failed(SyncErrorKind.NETWORK, null, 4L)),
            ),
        )

        assertEquals(SyncOutcome.Succeeded(2L), store.outcomeFor(SyncDomain.MODULES).first())
        assertTrue(store.outcomeFor(SyncDomain.ASSIGNED_VIDEOS).first() is SyncOutcome.Failed)
    }

    /**
     * `runs` replays the last summary, so a refresh must match on its own start time —
     * otherwise a stale run resolves the spinner instantly and reports the wrong verdict.
     */
    @Test
    fun `the start-time predicate rejects a replayed earlier run`() = runBlocking {
        val store = SyncStatusStore()
        store.publishRun(InboundRunSummary(startedAtMs = 100L, finishedAtMs = 200L, outcomes = emptyMap()))

        val refreshStartedAt = 300L
        val matched = store.runs.replayCache.firstOrNull { it.startedAtMs >= refreshStartedAt }

        assertNull(matched)
    }
}
