package com.medtroniclabs.microcoaching.domain.gaps.evidence

import com.medtroniclabs.microcoaching.domain.telemetry.PatientIdHasher
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Helpers for building de-identified `evidence` payloads per
 * GAP_DETECTION_SDK.md §5. Each rule evaluator owns the construction of its
 * own evidence map — there's no central allowlist (intentional per §5.5).
 *
 * What this util exists to enforce:
 *   - Identifier fields **MUST** route through [hashId]. Direct `evidence
 *     mapOf("facility_id" to "raw-id")` from an evaluator is a code-review fail.
 *   - Free-text fields **MUST NOT** appear — clinical free-text, GPS, names.
 *   - Stay under 2 KB total when serialised (§5.4). This is not enforced in
 *     code today; reviewers eyeball it.
 */
object EvidenceBuilder {

    /** SHA-256 the given identifier. Reuses [PatientIdHasher] — same scheme. */
    fun hashId(rawIdentifier: String): String = PatientIdHasher.hash(rawIdentifier)

    /**
     * Convert an evidence map to JSON, dropping null values. The dispatcher
     * passes the resulting String through to `EventRecorder.recordSpiceActionObserved`
     * as `evidenceJson`.
     */
    fun toJsonString(evidence: Map<String, Any?>): String =
        toJsonObject(evidence).toString()

    fun toJsonObject(evidence: Map<String, Any?>): JsonObject = buildJsonObject {
        evidence.forEach { (key, value) ->
            val element: JsonElement = when (value) {
                null -> JsonNull
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
            put(key, element)
        }
    }
}
