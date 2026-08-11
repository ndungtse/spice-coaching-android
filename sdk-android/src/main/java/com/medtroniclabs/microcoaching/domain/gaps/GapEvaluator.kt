package com.medtroniclabs.microcoaching.domain.gaps

import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity

/**
 * One evaluator per `rule_type` per GAP_DETECTION_SDK.md §4. Returns a
 * [GapDetectionResult] when the rule fires on the given event; returns null
 * when the rule doesn't apply or the event is in the "correct action" path.
 *
 * Per design §1 we only emit telemetry on *fired* gaps — clean events are not
 * recorded as separate `correct` rows because the backend just counts incorrect
 * occurrences. Evaluators are pure (no I/O on the hot path apart from the
 * facility lookup, which is a Room read inside [evaluate]).
 */
interface GapEvaluator {
    /** Matches `rule_type` in [DetectionRuleEnvelope]. */
    val ruleType: String

    suspend fun evaluate(
        assessmentData: Map<String, Any>,
        rule: DetectionRuleEnvelope,
        gap: BehaviouralGapEntity,
    ): GapDetectionResult?
}
