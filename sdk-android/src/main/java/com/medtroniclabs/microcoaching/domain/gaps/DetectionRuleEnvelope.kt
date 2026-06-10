package com.medtroniclabs.microcoaching.domain.gaps

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Parsed `detection_rule_jsonb` envelope from the backend's `BehaviouralGapSyncPayload`.
 *
 * Two shapes are supported:
 *
 * **Compliance schema (current, DETECTION_RULE_SCHEMA.md §schema v1)** — the
 * shape the v3 backend actually ships:
 * ```json
 * {
 *   "schema_version": 1,
 *   "evaluator": "spice_referral_compliance",
 *   "when": { "op": "and", "conditions": [ ... ] },
 *   "metadata": { ... reviewer hints, ignored by the evaluator ... }
 * }
 * ```
 *
 * **Legacy rule_type schema** — older envelopes dispatched per `rule_type`:
 * ```json
 * { "schema_version": 1, "rule_type": "...", "params": {...}, "match": {...} }
 * ```
 *
 * Exactly one of [evaluator] / [ruleType] is set. [isCompliance] picks the path
 * the [GapRuleDispatcher] takes.
 */
@Serializable
data class DetectionRuleEnvelope(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("rule_type") val ruleType: String? = null,
    @SerialName("evaluator") val evaluator: String? = null,
    @SerialName("when") val whenClause: JsonObject? = null,
    @SerialName("params") val params: JsonObject = JsonObject(emptyMap()),
    @SerialName("match") val match: MatchClause = MatchClause(),
    @SerialName("metadata") val metadata: JsonObject = JsonObject(emptyMap()),
) {
    @Serializable
    data class MatchClause(
        @SerialName("spice_event_codes") val spiceEventCodes: List<String> = emptyList(),
        @SerialName("assessment_types") val assessmentTypes: List<String> = emptyList(),
    )

    /** True when this is a `spice_referral_compliance` (`when`-tree) envelope. */
    val isCompliance: Boolean
        get() = evaluator == EVALUATOR_SPICE_REFERRAL_COMPLIANCE

    companion object {
        private const val TAG = "DetectionRuleEnvelope"

        const val EVALUATOR_SPICE_REFERRAL_COMPLIANCE = "spice_referral_compliance"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Parse a JSON-serialised envelope. Returns null if the input is null,
         * empty, malformed, or carries a schema version this SDK doesn't support
         * (forward-compat skip-and-warn per design §3).
         */
        fun parseOrNull(raw: String?): DetectionRuleEnvelope? {
            if (raw.isNullOrBlank() || raw == "{}") return null
            return try {
                val parsed = json.decodeFromString<DetectionRuleEnvelope>(raw)
                if (parsed.schemaVersion > 1) {
                    Log.w(
                        TAG,
                        "Skipping rule with schema_version=${parsed.schemaVersion} (>1) " +
                            "evaluator=${parsed.evaluator} ruleType=${parsed.ruleType}",
                    )
                    null
                } else {
                    parsed
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse detection_rule envelope: ${e.message}")
                null
            }
        }
    }
}
