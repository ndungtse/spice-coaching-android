package com.medtroniclabs.microcoaching.domain.gaps

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.dao.BehaviouralGapDao

/**
 * The orchestrator that loads synced gap rules and dispatches each to the right
 * evaluator. Two rule shapes are supported (see [DetectionRuleEnvelope]):
 *   - **`spice_referral_compliance`** (`evaluator` + `when` tree) — the shape
 *     the v3 backend ships. Routed to [SpiceReferralComplianceEvaluator].
 *   - **legacy `rule_type`** — routed to a per-type [GapEvaluator] from
 *     [evaluators], after the `match` clause filter.
 *
 * Skip rules:
 *   - empty or null `detection_rule` (quiz-only gaps)
 *   - `schema_version > 1` (forward-compat)
 *   - legacy only: `match.*` doesn't include the current event/assessment type,
 *     or unknown `rule_type`
 *
 * Multiple gaps can fire on one SPICE event. The dispatcher returns all fired
 * results; the caller emits one `spice_action_observed` per result.
 */
class GapRuleDispatcher(
    private val gapDao: BehaviouralGapDao,
    private val evaluators: Map<String, GapEvaluator>,
    private val complianceEvaluator: SpiceReferralComplianceEvaluator = SpiceReferralComplianceEvaluator(),
) {

    suspend fun evaluate(
        assessmentData: Map<String, Any>,
        spiceEventCode: String,
        assessmentType: String?,
    ): List<GapDetectionResult> {
        val gaps = try {
            gapDao.getActiveWithRules()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load active gap rules: ${e.message}")
            return emptyList()
        }

        if (gaps.isEmpty()) return emptyList()

        val fired = mutableListOf<GapDetectionResult>()
        for (gap in gaps) {
            val envelope = DetectionRuleEnvelope.parseOrNull(gap.detectionRule) ?: continue

            // Compliance schema (`evaluator: spice_referral_compliance`) — the
            // shape the v3 backend ships. Self-scoping via its `when` tree, so
            // it bypasses the legacy `match`/`rule_type` routing below.
            if (envelope.isCompliance) {
                try {
                    complianceEvaluator.evaluate(assessmentData, envelope, gap)
                        ?.let { fired += it }
                } catch (e: Exception) {
                    Log.w(TAG, "Compliance evaluator threw on gap=${gap.gapCode}: ${e.message}")
                }
                continue
            }

            // Filter by match clause. An empty list in the envelope means "no
            // constraint on this dimension" (more permissive); per design we
            // treat both cases below as "skip if explicit list is non-empty
            // and current value is not in it".
            if (envelope.match.spiceEventCodes.isNotEmpty() &&
                spiceEventCode !in envelope.match.spiceEventCodes
            ) continue

            if (envelope.match.assessmentTypes.isNotEmpty() &&
                assessmentType != null &&
                assessmentType !in envelope.match.assessmentTypes
            ) continue

            val evaluator = envelope.ruleType?.let { evaluators[it] }
            if (evaluator == null) {
                Log.w(
                    TAG,
                    "Skipping gap=${gap.gapCode} — no evaluator " +
                        "(rule_type='${envelope.ruleType}' evaluator='${envelope.evaluator}')",
                )
                continue
            }

            try {
                val result = evaluator.evaluate(assessmentData, envelope, gap)
                if (result != null) fired += result
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Evaluator '${envelope.ruleType}' threw on gap=${gap.gapCode}: ${e.message}",
                )
            }
        }

        if (fired.isNotEmpty()) {
            Log.i(
                TAG,
                "Gap dispatch: ${fired.size}/${gaps.size} fired " +
                    "(${fired.joinToString { "${it.gapCode}=${it.outcome}" }}) " +
                    "spice_event=$spiceEventCode assessment=$assessmentType",
            )
        } else {
            Log.d(
                TAG,
                "Gap dispatch: 0/${gaps.size} fired — " +
                    "spice_event=$spiceEventCode assessment=$assessmentType " +
                    "registered=${evaluators.keys}",
            )
        }
        return fired
    }

    companion object {
        private const val TAG = "GapRuleDispatcher"
    }
}
