package com.medtroniclabs.microcoaching.domain.telemetry

import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.mapper.toPayload
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the `video_progress_updated` telemetry event (docs/_events/video.md):
 *  1. it maps to the `coaching` family so the backend ingests it as a typed enum;
 *  2. its `payload_json` surfaces the required fields
 *     (`source_document_id`, `last_position_ms`, `percent_watched`, `completed`)
 *     flat and with the right types through [CoachingEventEntity.toPayload].
 */
class VideoProgressEventTest {

    @Test
    fun `video_progress_updated is in the coaching family`() {
        assertEquals("coaching", eventFamilyFor("video_progress_updated"))
    }

    /** Builds the entity exactly as `EventRecorder.recordVideoProgress` does. */
    private fun videoProgressEntity(
        sourceDocumentId: String,
        lastPositionMs: Long,
        percentWatched: Double,
        completed: Boolean,
    ) = CoachingEventEntity(
        eventId = "evt-vp-1",
        sdkVersion = "test",
        eventFamily = eventFamilyFor("video_progress_updated"),
        sessionId = "video-player",
        chwId = "chw-1",
        eventType = "video_progress_updated",
        triggerType = "workflow_event",
        outcome = if (completed) "completed" else null,
        networkState = "online",
        payloadJson = buildJsonObject {
            put("source_document_id", sourceDocumentId)
            put("last_position_ms", lastPositionMs)
            put("percent_watched", percentWatched)
            put("completed", completed)
        }.toString(),
        timestampLocal = 1_700_000_000_000L,
    )

    @Test
    fun `in-progress event carries the required payload fields`() {
        val payload = videoProgressEntity("doc-1", 125_000, 42.5, completed = false).toPayload()

        assertEquals("video_progress_updated", payload.eventType)
        assertEquals("coaching", payload.eventFamily)
        assertEquals("workflow_event", payload.triggerType)
        assertEquals("doc-1", payload.payloadJson["source_document_id"]?.jsonPrimitive?.content)
        assertEquals(125_000L, payload.payloadJson["last_position_ms"]?.jsonPrimitive?.long)
        assertEquals(42.5, payload.payloadJson["percent_watched"]?.jsonPrimitive?.double!!, 0.0001)
        assertEquals(false, payload.payloadJson["completed"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `completion event sets outcome and full progress`() {
        val payload = videoProgressEntity("doc-1", 300_000, 100.0, completed = true).toPayload()

        assertEquals("completed", payload.outcome)
        assertTrue(payload.payloadJson["completed"]?.jsonPrimitive?.boolean!!)
        assertEquals(100.0, payload.payloadJson["percent_watched"]?.jsonPrimitive?.double!!, 0.0001)
    }
}
