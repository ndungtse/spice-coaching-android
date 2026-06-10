package com.medtroniclabs.microcoaching.data.mapper

import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.DigitalProficiencyEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.LlmTraceEntity
import com.medtroniclabs.microcoaching.network.TelemetryEventPayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Extension functions that map Room entities to [TelemetryEventPayload] for the backend.
 *
 * All three entity types (CoachingEvent, LlmTrace, DigitalProficiency) collapse into
 * the unified backend schema. LlmTrace and DigitalProficiency store entity-specific
 * fields in [TelemetryEventPayload.payloadJson].
 */

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private fun epochToDate(epochMillis: Long): String = dateFormat.format(Date(epochMillis))

fun CoachingEventEntity.toPayload(): TelemetryEventPayload = TelemetryEventPayload(
    id = eventId,
    eventFamily = eventFamily,
    eventType = eventType,
    eventDate = epochToDate(timestampLocal),
    eventSchemaVersion = eventSchemaVersion,
    sessionId = sessionId,
    patientVisitId = patientVisitId,
    patientTrackId = patientTrackId,
    patientIdHash = patientIdHash,
    villageId = villageId,
    upazilaId = upazilaId,
    moduleFamilyId = moduleFamilyId,
    moduleId = moduleId,
    cardFamilyId = cardFamilyId,
    quizFamilyId = quizFamilyId,
    moduleVersion = moduleVersion,
    quizScorePct = quizScorePct,
    clinicalDomain = clinicalDomain,
    cardType = cardType,
    triggerType = triggerType,
    inferenceMode = inferenceMode,
    outcome = outcome,
    validatorStatus = validatorStatus,
    fallbackUsed = fallbackUsed,
    networkState = networkState,
    payloadJson = buildJsonObject {
        // Structured gap payload — echoes the top-level fields the backend
        // uses in its gap-state detection rule alongside the gap identifier.
        if (behaviouralGapId != null) {
            cardType?.let { put("card_type", it) }
            triggerType?.let { put("trigger_type", it) }
            inferenceMode?.let { put("inference_mode", it) }
            put("behavioural_gap_id", behaviouralGapId)
        } else if (payloadJson != null) {
            put("raw", payloadJson)
        }
    },
    timestampUtc = timestampUtc,
    timestampLocal = timestampLocal,
)

fun LlmTraceEntity.toPayload(): TelemetryEventPayload = TelemetryEventPayload(
    id = id,
    eventFamily = "system",
    eventType = "llm_inference",
    eventDate = epochToDate(timestampLocal),
    eventSchemaVersion = eventSchemaVersion,
    payloadJson = buildJsonObject {
        put("coaching_event_id", coachingEventId)
        put("model_id", modelId)
        put("prompt_template_id", promptTemplateId)
        put("prompt_template_version", promptTemplateVersion)
        put("validated", validated)
        validatorFailure?.let { put("validator_failure", it) }
        put("fallback_used", fallbackUsed)
        latencyMs?.let { put("latency_ms", it) }
        inputTokens?.let { put("input_tokens", it) }
        outputTokens?.let { put("output_tokens", it) }
    },
    timestampLocal = timestampLocal,
)

fun DigitalProficiencyEventEntity.toPayload(): TelemetryEventPayload = TelemetryEventPayload(
    id = id,
    eventFamily = "digital",
    eventType = eventType,
    eventDate = epochToDate(timestampLocal),
    eventSchemaVersion = eventSchemaVersion,
    sessionId = sessionId,
    payloadJson = buildJsonObject {
        put("success", success)
        errorType?.let { put("error_type", it) }
    },
    networkState = networkState,
    timestampLocal = timestampLocal,
)
