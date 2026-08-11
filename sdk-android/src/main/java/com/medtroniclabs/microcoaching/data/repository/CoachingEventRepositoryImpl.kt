package com.medtroniclabs.microcoaching.data.repository

import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.domain.telemetry.eventFamilyFor
import java.util.UUID

/**
 * Repository for coaching event persistence and exposure via [CoachingDataRepository].
 *
 * Schema follows DataDesign v1.1 §4.1.
 */
open class CoachingEventRepositoryImpl(
    private val dao: CoachingEventDao,
    private val config: MicroCoachingConfig,
) {

    suspend fun getPendingCoachingEvents(): List<Map<String, Any>> =
        dao.getPending().map { it.toMap() }

    suspend fun markEventsSynced(eventIds: List<String>) {
        dao.markSynced(eventIds)
    }

    // ── Internal write API ────────────────────────────────────────────────────

    /**
     * Record a coaching event. Generates a UUID event_id automatically.
     *
     * @param sessionId  The active SDK session UUID.
     * @param chwId      CHW identifier from SPICE auth context.
     * @param eventType  Event taxonomy value — see [CoachingEventEntity] KDoc.
     * @param patientIdHash  SHA-256 of raw patient_id. Null for non-patient events.
     * @param patientVisitId Mapped from SPICE encounterId. Null for non-visit events.
     * @param moduleFamilyId Module family associated with this event, if any.
     * @param networkState   Device connectivity at event time: online|offline|restored.
     * @param payloadJson    Extra data not covered by typed columns (event-type-specific JSON).
     */
    suspend fun recordEvent(
        sessionId: String,
        chwId: String,
        eventType: String,
        patientIdHash: String? = null,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        moduleFamilyId: String? = null,
        clinicalDomain: String? = null,
        cardType: String? = null,
        triggerType: String? = null,
        inferenceMode: String? = null,
        outcome: String? = null,
        networkState: String? = null,
        payloadJson: String? = null,
    ) {
        val entity = CoachingEventEntity(
            eventId = UUID.randomUUID().toString(),
            eventSchemaVersion = 1,
            sdkVersion = BuildConfig.SDK_VERSION,
            eventFamily = eventFamilyFor(eventType),
            sessionId = sessionId,
            chwId = chwId,
            tenantId = config.tenantId.toIntOrNull(),
            eventType = eventType,
            patientIdHash = patientIdHash,
            patientVisitId = patientVisitId,
            patientTrackId = patientTrackId,
            moduleFamilyId = moduleFamilyId,
            clinicalDomain = clinicalDomain,
            cardType = cardType,
            triggerType = triggerType,
            inferenceMode = inferenceMode,
            outcome = outcome,
            networkState = networkState,
            payloadJson = payloadJson,
        )
        dao.insert(entity)

        config.dataCallback?.let { cb ->
            // Page the callback payload: the unbounded getPending() re-materialised
            // the ENTIRE backlog as maps on every single insert — O(N²) allocation
            // over an offline session. The oldest page still surfaces first; the
            // rest follows on subsequent inserts / sync cycles.
            val pending = dao.getPending(DATA_CALLBACK_PAGE_SIZE).map { it.toMap() }
            if (pending.isNotEmpty()) cb.onCoachingEventsReady(pending)
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private fun CoachingEventEntity.toMap(): Map<String, Any> = buildMap {
        put("event_id", eventId)
        put("event_schema_version", eventSchemaVersion)
        put("sdk_version", sdkVersion)
        put("session_id", sessionId)
        put("chw_id", chwId)
        put("event_type", eventType)
        put("timestamp_local", timestampLocal)
        put("sync_status", syncStatus)
        patientVisitId?.let { put("patient_visit_id", it) }
        patientTrackId?.let { put("patient_track_id", it) }
        patientIdHash?.let { put("patient_id_hash", it) }
        tenantId?.let { put("tenant_id", it) }
        villageId?.let { put("village_id", it) }
        upazilaId?.let { put("upazila_id", it) }
        moduleFamilyId?.let { put("module_family_id", it) }
        clinicalDomain?.let { put("clinical_domain", it) }
        cardType?.let { put("card_type", it) }
        triggerType?.let { put("trigger_type", it) }
        inferenceMode?.let { put("inference_mode", it) }
        quizQuestionId?.let { put("quiz_question_id", it) }
        selectedOption?.let { put("selected_option", it) }
        isCorrect?.let { put("is_correct", it) }
        outcome?.let { put("outcome", it) }
        validatorStatus?.let { put("validator_status", it) }
        fallbackUsed?.let { put("fallback_used", it) }
        payloadJson?.let { put("payload_json", it) }
        networkState?.let { put("network_state", it) }
        timestampUtc?.let { put("timestamp_utc", it) }
        syncedAt?.let { put("synced_at", it) }
        put("retry_count", retryCount)
    }

    private companion object {
        /** Max pending rows delivered per [MicroCoachingDataCallback.onCoachingEventsReady] call. */
        const val DATA_CALLBACK_PAGE_SIZE = 500
    }
}
