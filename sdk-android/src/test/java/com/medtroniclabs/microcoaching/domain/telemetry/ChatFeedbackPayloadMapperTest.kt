package com.medtroniclabs.microcoaching.domain.telemetry

import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.mapper.toPayload
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies a `chat_feedback_*` event round-trips through
 * [CoachingEventEntity.toPayload] with `event_type` / `event_family` preserved and
 * the served answer surfaced flat under `payload_json.response` (the shape the
 * backend ingests for Events Modelling 1.4 digital feedback).
 */
class ChatFeedbackPayloadMapperTest {

    private fun feedbackEntity(
        eventType: String,
        response: String,
    ) = CoachingEventEntity(
        eventId = "evt-fb-1",
        sdkVersion = "1.4.x",
        eventFamily = "digital",
        sessionId = "sess-1",
        chwId = "chw-1",
        eventType = eventType,
        triggerType = "workflow_event",
        inferenceMode = "online",
        validatorStatus = "pass",
        fallbackUsed = false,
        networkState = "online",
        moduleId = "mod-version-uuid",
        payloadJson = buildJsonObject { put("response", response) }.toString(),
        timestampLocal = 1_700_000_000_000L,
    )

    @Test
    fun `positive feedback maps type family and response into payload_json`() {
        val payload = feedbackEntity("chat_feedback_positive", "Take the tablet with food.").toPayload()

        assertEquals("chat_feedback_positive", payload.eventType)
        assertEquals("digital", payload.eventFamily)
        assertEquals("mod-version-uuid", payload.moduleId)
        assertEquals(
            "Take the tablet with food.",
            payload.payloadJson["response"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `negative feedback preserves event_type`() {
        val payload = feedbackEntity("chat_feedback_negative", "Wrong answer.").toPayload()
        assertEquals("chat_feedback_negative", payload.eventType)
        assertEquals("Wrong answer.", payload.payloadJson["response"]?.jsonPrimitive?.content)
    }
}
