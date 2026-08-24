package com.medtroniclabs.microcoaching.domain.lifecycle

import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.dao.RetryCountRow
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitCompletedHandlerTest {

    @Test
    fun `backfills patient_visit_id and flushes, writing no event of its own`() = runBlocking {
        val dao = FakeCoachingEventDao()
        val handler = VisitCompletedHandler(coachingEventDao = dao)

        var flushed = false
        handler.handle(
            chwId = "chw-1",
            encounterId = "visit-42",
            flush = { flushed = true },
        )

        assertEquals("sdk-hook", dao.lastBackfillSessionId)
        assertEquals("chw-1", dao.lastBackfillChwId)
        assertEquals("visit-42", dao.lastBackfillEncounterId)
        // Visit close stamps existing rows; it is not itself an event.
        assertTrue(dao.inserted.isEmpty())
        assertTrue("expected flush() to be invoked", flushed)
    }

    @Test
    fun `skips work entirely when encounterId is blank`() = runBlocking {
        val dao = FakeCoachingEventDao()
        val handler = VisitCompletedHandler(coachingEventDao = dao)

        var flushed = false
        handler.handle(
            chwId = "chw-1",
            encounterId = "",
            flush = { flushed = true },
        )

        assertNull(dao.lastBackfillEncounterId)
        assertTrue(dao.inserted.isEmpty())
        // Blank encounter — no work, no flush needed.
        assertEquals(false, flushed)
    }

    /**
     * Captures the args to [backfillPatientVisitId] and any inserts. Only the
     * methods the handler touches are implemented meaningfully.
     */
    private class FakeCoachingEventDao : CoachingEventDao {
        var lastBackfillSessionId: String? = null
        var lastBackfillChwId: String? = null
        var lastBackfillEncounterId: String? = null
        val inserted = mutableListOf<CoachingEventEntity>()

        override suspend fun insert(event: CoachingEventEntity) {
            inserted += event
        }

        override suspend fun backfillPatientVisitId(
            sessionId: String,
            chwId: String,
            encounterId: String,
        ): Int {
            lastBackfillSessionId = sessionId
            lastBackfillChwId = chwId
            lastBackfillEncounterId = encounterId
            return 0
        }

        // ── Unused by the handler path under test ─────────────────────────────
        override suspend fun getPending(): List<CoachingEventEntity> = emptyList()
        override suspend fun getPending(limit: Int): List<CoachingEventEntity> = emptyList()
        override suspend fun getLatestCorrectQuestionIds(chwId: String, moduleFamilyId: String): List<String> =
            emptyList()
        override suspend fun getLatestWrongQuestionIds(chwId: String, moduleFamilyId: String): List<String> =
            emptyList()
        override suspend fun getLatestCorrectQuestionIdsSince(
            chwId: String,
            moduleFamilyId: String,
            sinceMillis: Long,
        ): List<String> = emptyList()
        override suspend fun getReplayableForGapState(chwId: String): List<CoachingEventEntity> = emptyList()
        override suspend fun getUnsyncedQuizAttempts(chwId: String): List<CoachingEventEntity> = emptyList()
        override fun getEventCountFlow(): Flow<Int> = flowOf(0)
        override fun observeModuleRequested(chwId: String): Flow<List<CoachingEventEntity>> = flowOf(emptyList())
        override suspend fun getModuleRequested(chwId: String): List<CoachingEventEntity> = emptyList()
        override suspend fun countDistinctCardsViewed(chwId: String, moduleFamilyId: String): Int = 0
        override suspend fun markSynced(eventIds: List<String>, syncedAt: Long) = Unit
        override suspend fun markFailed(eventIds: List<String>) = Unit
        override suspend fun incrementRetryCount(eventIds: List<String>) = Unit
        override suspend fun getRetryCounts(eventIds: List<String>): List<RetryCountRow> = emptyList()
        override suspend fun getBySession(sessionId: String): List<CoachingEventEntity> = emptyList()
        override suspend fun getAll(): List<CoachingEventEntity> = emptyList()
        override suspend fun deleteSynced() = Unit
        override suspend fun deleteAll() = Unit
    }
}
