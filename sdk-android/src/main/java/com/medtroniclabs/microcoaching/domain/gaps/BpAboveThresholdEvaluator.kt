package com.medtroniclabs.microcoaching.domain.gaps

import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity

/**
 * GAP_DETECTION_SDK.md §4.2 — `bp_above_threshold_no_referral`.
 *
 * TODO: implement when wiring the next pilot rule. Reads
 * `bpLog.avgSystolic` / `avgDiastolic` from assessment data; fires when
 * either reading is ≥ the threshold in `rule.params` AND `isReferred == false`.
 *
 * Thresholds today (per SPICE AssessmentDefinedParams):
 *   - `UpperLimitSystolic` = 140 (Community Clinic referral)
 *   - `UpperLimitDiastolic` = 90
 *   - `UPAZILA_UPPER_LIMIT_SYSTOLIC` = 160 (Upazila escalation)
 *   - `UPAZILA_UPPER_LIMIT_DIASTOLIC` = 100
 *
 * Backend `rule.params` is expected to carry the exact thresholds, not these
 * constants — we read from `params` to allow per-tenant tuning.
 */
class BpAboveThresholdEvaluator : GapEvaluator {

    override val ruleType: String = "bp_above_threshold_no_referral"

    override suspend fun evaluate(
        assessmentData: Map<String, Any>,
        rule: DetectionRuleEnvelope,
        gap: BehaviouralGapEntity,
    ): GapDetectionResult? = null
}
