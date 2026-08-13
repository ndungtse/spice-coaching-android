package com.medtroniclabs.microcoaching.domain.telemetry

import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.dao.RetryCountRow
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Events Modelling 1.4 chat-feedback contract:
 *   - `eventFamilyFor` buckets both new event types into `"digital"`.
 *   - [EventRecorder.recordChatFeedback] writes a `coaching_event` row with the
 *     correct `event_type`, `trigger_type`, echoed response context, and the
 *     served answer + CHW question mirrored into `payload_json`
 *     (`{"question": …, "response": …}`, Events-Modelling 1.7).
 */
class ChatFeedbackEventRecorderTest {

    @Test
    fun `eventFamilyFor maps both feedback types to digital`() {
        assertEquals("digital", eventFamilyFor("chat_feedback_positive"))
        assertEquals("digital", eventFamilyFor("chat_feedback_negative"))
    }

    @Test
    fun `recordChatFeedback positive builds a chat_feedback_positive digital event`() = runBlocking {
        val dao = CapturingDao()
        val recorder = EventRecorder(dao = dao, sessionId = "sess-1", chwId = "chw-1")

        recorder.recordChatFeedback(
            positive = true,
            responseJson = "Take the tablet with food.",
            moduleId = "mod-version-uuid",
            inferenceMode = "online",
            validatorStatus = "pass",
            fallbackUsed = false,
            networkState = "online",
        )

        val row = dao.inserted.single()
        assertEquals("chat_feedback_positive", row.eventType)
        assertEquals("digital", row.eventFamily)
        assertEquals("workflow_event", row.triggerType)
        assertEquals("mod-version-uuid", row.moduleId)
        assertEquals("online", row.inferenceMode)
        assertEquals("pass", row.validatorStatus)
        assertEquals(false, row.fallbackUsed)
        assertEquals("online", row.networkState)

        val response = Json.parseToJsonElement(row.payloadJson!!)
            .jsonObject["response"]?.jsonPrimitive?.content
        assertEquals("Take the tablet with food.", response)
    }

    @Test
    fun `recordChatFeedback negative builds a chat_feedback_negative event`() = runBlocking {
        val dao = CapturingDao()
        val recorder = EventRecorder(dao = dao, sessionId = "sess-1", chwId = "chw-1")

        recorder.recordChatFeedback(positive = false, responseJson = "Wrong answer.")

        val row = dao.inserted.single()
        assertEquals("chat_feedback_negative", row.eventType)
        assertEquals("digital", row.eventFamily)
        // fallbackUsed defaults to false when the caller passes null.
        assertEquals(false, row.fallbackUsed)
        assertTrue(row.payloadJson!!.contains("Wrong answer."))
    }

    @Test
    fun `recordChatFeedback carries the free-text note in payload feedback`() = runBlocking {
        val dao = CapturingDao()
        val recorder = EventRecorder(dao = dao, sessionId = "sess-1", chwId = "chw-1")

        recorder.recordChatFeedback(
            positive = false,
            responseJson = "Give 500mg twice daily.",
            feedbackText = "Dose is wrong for children.",
        )

        val payload = Json.parseToJsonElement(dao.inserted.single().payloadJson!!).jsonObject
        assertEquals("Give 500mg twice daily.", payload["response"]?.jsonPrimitive?.content)
        assertEquals("Dose is wrong for children.", payload["feedback"]?.jsonPrimitive?.content)
    }

    @Test
    fun `recordChatFeedback mirrors the CHW question into payload question`() = runBlocking {
        val dao = CapturingDao()
        val recorder = EventRecorder(dao = dao, sessionId = "sess-1", chwId = "chw-1")

        recorder.recordChatFeedback(
            positive = false,
            responseJson = "Give 500mg twice daily.",
            question = "What dose of paracetamol for a child?",
            feedbackText = "Dose is wrong for children.",
        )

        val payload = Json.parseToJsonElement(dao.inserted.single().payloadJson!!).jsonObject
        assertEquals(
            "What dose of paracetamol for a child?",
            payload["question"]?.jsonPrimitive?.content,
        )
        assertEquals("Give 500mg twice daily.", payload["response"]?.jsonPrimitive?.content)
    }

    @Test
    fun `null question omits the question key`() = runBlocking {
        val dao = CapturingDao()
        val recorder = EventRecorder(dao = dao, sessionId = "sess-1", chwId = "chw-1")

        recorder.recordChatFeedback(positive = true, responseJson = "Helpful.", question = null)

        val payload = Json.parseToJsonElement(dao.inserted.single().payloadJson!!).jsonObject
        assertNull(payload["question"])
    }

    @Test
    fun `no note omits the feedback key`() = runBlocking {
        val dao = CapturingDao()
        val recorder = EventRecorder(dao = dao, sessionId = "sess-1", chwId = "chw-1")

        recorder.recordChatFeedback(positive = true, responseJson = "Helpful.", feedbackText = null)

        val payload = Json.parseToJsonElement(dao.inserted.single().payloadJson!!).jsonObject
        assertNull(payload["feedback"])
    }

    /** Fake capturing only [insert]; everything else is unused by these tests. */
    private class CapturingDao : CoachingEventDao {
        val inserted = mutableListOf<CoachingEventEntity>()
        override suspend fun insert(event: CoachingEventEntity) { inserted += event }

        override suspend fun getPending(): List<CoachingEventEntity> = error("unused")
        override suspend fun getPending(limit: Int): List<CoachingEventEntity> = error("unused")
        override suspend fun getLatestCorrectQuestionIds(chwId: String, moduleFamilyId: String): List<String> = error("unused")
        override suspend fun getLatestWrongQuestionIds(chwId: String, moduleFamilyId: String): List<String> = error("unused")
        override suspend fun getLatestCorrectQuestionIdsSince(chwId: String, moduleFamilyId: String, sinceMillis: Long): List<String> = error("unused")
        override fun getEventCountFlow(): Flow<Int> = error("unused")
        override fun observeModuleRequested(chwId: String): Flow<List<CoachingEventEntity>> = error("unused")
        override suspend fun getModuleRequested(chwId: String): List<CoachingEventEntity> = error("unused")
        override suspend fun markSynced(eventIds: List<String>, syncedAt: Long) = error("unused")
        override suspend fun markFailed(eventIds: List<String>) = error("unused")
        override suspend fun incrementRetryCount(eventIds: List<String>) = error("unused")
        override suspend fun getRetryCounts(eventIds: List<String>): List<RetryCountRow> = error("unused")
        override suspend fun getBySession(sessionId: String): List<CoachingEventEntity> = error("unused")
        override suspend fun backfillPatientVisitId(sessionId: String, chwId: String, encounterId: String): Int = error("unused")
        override suspend fun getReplayableForGapState(chwId: String): List<CoachingEventEntity> = error("unused")
        override suspend fun getUnsyncedQuizAttempts(chwId: String): List<CoachingEventEntity> = error("unused")
        override suspend fun getAll(): List<CoachingEventEntity> = error("unused")
        override suspend fun deleteSynced() = error("unused")
        override suspend fun deleteAll() = error("unused")
        override suspend fun countDistinctCardsViewed(chwId: String, moduleFamilyId: String): Int = 0
    }
}
