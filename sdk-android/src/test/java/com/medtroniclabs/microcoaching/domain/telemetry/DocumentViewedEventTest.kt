package com.medtroniclabs.microcoaching.domain.telemetry

import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.mapper.toPayload
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * Contract for the `document_viewed` telemetry event: everything module-, quiz-,
 * card- and patient-scoped stays null, and the document rides in `payload_json`
 * under `source_document_id`. The event name is what the backend's rollup keys
 * off, so a rename here silently empties the document-usage dashboard.
 */
class DocumentViewedEventTest {

    @Test
    fun `document_viewed is in the coaching family`() {
        assertEquals("coaching", eventFamilyFor("document_viewed"))
    }

    /** Builds the entity exactly as `EventRecorder.recordDocumentViewed` does. */
    private fun documentViewedEntity(
        sourceDocumentId: String,
        eventId: String = UUID.randomUUID().toString(),
    ) = CoachingEventEntity(
        eventId = eventId,
        sdkVersion = "test",
        eventFamily = eventFamilyFor("document_viewed"),
        sessionId = "sess-1",
        chwId = "chw-1",
        eventType = "document_viewed",
        networkState = "online",
        payloadJson = buildJsonObject {
            put("source_document_id", sourceDocumentId)
        }.toString(),
        timestampLocal = 1_700_000_000_000L,
    )

    @Test
    fun `carries the source document id flat on the wire payload`() {
        val docId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val payload = documentViewedEntity(docId).toPayload()

        assertEquals("document_viewed", payload.eventType)
        assertEquals("coaching", payload.eventFamily)
        assertEquals("online", payload.networkState)
        assertEquals(docId, payload.payloadJson["source_document_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `leaves module quiz card and trigger fields unset`() {
        val payload = documentViewedEntity("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee").toPayload()

        // A knowledge document belongs to no module, so nothing module-scoped is set.
        assertNull(payload.triggerType)
        assertNull(payload.moduleFamilyId)
        assertNull(payload.moduleId)
        assertNull(payload.cardFamilyId)
        assertNull(payload.quizFamilyId)
        assertNull(payload.moduleVersion)
        assertNull(payload.outcome)
        assertNull(payload.inferenceMode)
        assertNull(payload.validatorStatus)
        assertNull(payload.quizScorePct)
        // Geography/patient context is enriched server-side from chw_id.
        assertNull(payload.upazilaId)
        assertNull(payload.villageId)
        assertNull(payload.patientIdHash)
    }

    @Test
    fun `re-opening the same document produces a distinct event id`() {
        val docId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        // Two views of one document must be two rows — the backend counts events,
        // so a reused id would be deduped at ingest and lose the second open.
        val first = documentViewedEntity(docId).toPayload()
        val second = documentViewedEntity(docId).toPayload()

        assertNotEquals(first.id, second.id)
        assertEquals(
            first.payloadJson["source_document_id"]?.jsonPrimitive?.content,
            second.payloadJson["source_document_id"]?.jsonPrimitive?.content,
        )
    }
}
