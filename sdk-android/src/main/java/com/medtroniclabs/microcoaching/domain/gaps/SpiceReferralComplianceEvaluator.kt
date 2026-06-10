package com.medtroniclabs.microcoaching.domain.gaps

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Evaluator for `evaluator: "spice_referral_compliance"` rules
 * (DETECTION_RULE_SCHEMA.md — the shape the v3 backend ships). Walks the rule's
 * `when` predicate tree against a compliance state and fires
 * (`outcome="incorrect"`) when the tree is true — i.e. the rule engine
 * **recommended** one referral action and the CHW **actually** did something
 * different.
 *
 * ### Compliance state
 * The `assessmentData` map is expected to carry two nested branches that the
 * rule paths resolve into:
 *  - `recommended.*` — the rule-engine recommendation, e.g.
 *    `recommended.assessmentDetails.anc.summary.highRiskPregnantWoman.URGENT`
 *    (list), `recommended.referredReason` (list), `recommended.isReferred`.
 *  - `actual.*` — what the CHW did: `actual.didRefer` (bool),
 *    `actual.referralReasons` (list), `actual.isUrgent` (bool).
 *
 * SPICE must assemble these two branches before calling the SDK hook. Missing
 * paths resolve to null and the operators treat that as "no value" — so a rule
 * whose data isn't available simply doesn't fire (fail-safe).
 *
 * ### Operators (DETECTION_RULE_SCHEMA.md)
 * Logical: `and`, `or`, `not`. Preconditions: `eq`, `neq`, `exists`,
 * `contains_any`, `contains_all`, `array_nonempty`, `map_key_nonempty`,
 * `array_contains_substring`. Mismatch: `missed_referral`, `mismatch_eq`,
 * `mismatch_contains_any`, `mismatch_urgency`.
 *
 * Pure (no I/O). Unknown ops / malformed nodes evaluate to **false** — a rule
 * we can't understand never fires. Evidence carries only rule-level `metadata`
 * (never patient clinical data).
 */
class SpiceReferralComplianceEvaluator {

    fun evaluate(
        assessmentData: Map<String, Any?>,
        rule: DetectionRuleEnvelope,
        gap: BehaviouralGapEntity,
    ): GapDetectionResult? {
        val whenClause = rule.whenClause
        if (whenClause == null) {
            Log.d(TAG, "Skip ${gap.gapCode}: compliance rule has no `when` clause")
            return null
        }

        val fired = try {
            eval(whenClause, assessmentData)
        } catch (e: Exception) {
            Log.w(TAG, "Compliance eval threw on ${gap.gapCode}: ${e.message}")
            false
        }

        if (!fired) {
            Log.d(TAG, "${gap.gapCode}: compliance `when` false (no signal)")
            return null
        }

        Log.i(TAG, "${gap.gapCode} FIRED — spice_referral_compliance")
        return GapDetectionResult(
            gapId = gap.gapId,
            gapCode = gap.gapCode,
            ruleType = DetectionRuleEnvelope.EVALUATOR_SPICE_REFERRAL_COMPLIANCE,
            outcome = OUTCOME_INCORRECT,
            evidence = metadataEvidence(rule.metadata),
        )
    }

    // ------------------------------------------------------------------ tree --

    private fun eval(node: JsonObject, state: Map<String, Any?>): Boolean {
        return when (val op = node["op"]?.jsonPrimitive?.contentOrNull) {
            "and" -> conditions(node).all { eval(it, state) }
            "or" -> conditions(node).any { eval(it, state) }
            "not" -> node["condition"]?.let { !eval(it.jsonObject, state) } ?: false

            "eq" -> resolve(state, path(node)) == ruleValue(node["value"])
            "neq" -> resolve(state, path(node)) != ruleValue(node["value"])
            "exists" -> isPresent(resolve(state, path(node)))

            "contains_any" -> {
                val list = asStringList(resolve(state, path(node)))
                val values = ruleValues(node)
                list.any { it in values }
            }
            "contains_all" -> {
                val list = asStringList(resolve(state, path(node))).toSet()
                val values = ruleValues(node)
                values.isNotEmpty() && values.all { it in list }
            }
            "array_nonempty" -> asStringList(resolve(state, path(node))).isNotEmpty()
            "map_key_nonempty" -> {
                val map = resolve(state, path(node)) as? Map<*, *>
                val key = node["key"]?.jsonPrimitive?.contentOrNull
                asStringList(map?.get(key)).isNotEmpty()
            }
            "array_contains_substring" -> {
                val needle = node["value"]?.jsonPrimitive?.contentOrNull
                needle != null && asStringList(resolve(state, path(node))).any { it.contains(needle) }
            }

            // CHW was recommended a referral but did not refer.
            "missed_referral" ->
                resolve(state, "recommended.isReferred") == true &&
                    resolve(state, "actual.didRefer") != true

            "mismatch_eq" -> {
                val recPath = strField(node, "recommended_path")
                val actPath = strField(node, "actual_path")
                // Fire only when the actual value is PRESENT and differs. An
                // absent actual = "can't tell" → no fire. SPICE often can't
                // capture the actual side (destinationTier/reasons/urgency), so
                // firing on absent would be a false positive. NOTE: this is a
                // deliberate divergence from the schema's literal "differ
                // including one null" — in this integration, null = uncaptured,
                // not "CHW omitted it". See docs/gaps/COMPLIANCE_TEST_SPEC.md.
                val actual = actPath?.let { resolve(state, it) }
                val recommended = recPath?.let { resolve(state, it) }
                // Both operands must be present to be a real mismatch. Either
                // side absent → "can't tell" → no fire (avoids false positives
                // when a recommended_path doesn't resolve, or actual is uncaptured).
                if (recommended == null || actual == null) false
                else recommended != actual
            }

            // Recommended reasons hit `values`, but the CHW's actual reasons did
            // not. Requires actual reasons to be present (absent → no fire).
            "mismatch_contains_any" -> {
                val recPath = strField(node, "recommended_path")
                val actPath = strField(node, "actual_path")
                val actualReasons = actPath?.let { resolve(state, it) }
                if (recPath == null || actualReasons == null) {
                    false
                } else {
                    val values = ruleValues(node)
                    val recHit = asStringList(resolve(state, recPath)).any { it in values }
                    val actHit = asStringList(actualReasons).any { it in values }
                    recHit && !actHit
                }
            }

            // Rule-engine urgency (URGENT/NON_URGENT) ≠ the CHW's actual urgency.
            // Requires actual urgency to be present (absent → no fire).
            "mismatch_urgency" -> {
                val actPath = strField(node, "actual_path")
                val actual = actPath?.let { resolve(state, it) } as? Boolean
                if (actual == null) false
                else actual != (strField(node, "recommended_urgency") == "URGENT")
            }

            else -> {
                Log.w(TAG, "Unknown compliance op '$op' — treating as false")
                false
            }
        }
    }

    // --------------------------------------------------------------- helpers --

    private fun conditions(node: JsonObject): List<JsonObject> =
        (node["conditions"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()

    private fun path(node: JsonObject): String = strField(node, "path").orEmpty()

    private fun strField(node: JsonObject, name: String): String? =
        node[name]?.jsonPrimitive?.contentOrNull

    private fun ruleValues(node: JsonObject): Set<String> =
        (node["values"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            .orEmpty()

    /** Rule literal for `eq`/`neq`: boolean if it parses, otherwise string. */
    private fun ruleValue(el: JsonElement?): Any? {
        val prim = el as? JsonPrimitive ?: return null
        return prim.booleanOrNull ?: prim.contentOrNull
    }

    /** Resolve a dot-path over the nested-map state. */
    private fun resolve(state: Map<String, Any?>, path: String): Any? {
        if (path.isEmpty()) return null
        var current: Any? = state
        for (segment in path.split(".")) {
            current = (current as? Map<*, *>)?.get(segment) ?: return null
        }
        return current
    }

    private fun asStringList(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is List<*> -> value.mapNotNull { it?.toString() }
        is String -> listOf(value)
        else -> listOf(value.toString())
    }

    private fun isPresent(value: Any?): Boolean = when (value) {
        null -> false
        is String -> value.isNotEmpty()
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    /** Rule-level metadata only — never patient clinical data. */
    private fun metadataEvidence(metadata: JsonObject): Map<String, Any?> =
        metadata.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }

    private companion object {
        const val TAG = "ComplianceEval"
        const val OUTCOME_INCORRECT = "incorrect"
    }
}
