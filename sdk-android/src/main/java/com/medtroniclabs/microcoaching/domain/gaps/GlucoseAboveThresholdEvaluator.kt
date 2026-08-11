package com.medtroniclabs.microcoaching.domain.gaps

import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity

/**
 * GAP_DETECTION_SDK.md §4.3 — `glucose_above_threshold_no_referral`.
 *
 * TODO: implement after **C-SDK-1** is confirmed by the SPICE lead:
 *   - Exact key in assessment data for glucose value (`fbsBloodGlucose` vs
 *     `rbsBloodGlucose`). Are both present per assessment, or one or the
 *     other per workflow?
 *   - Unit: mmol/L confirmed (vs mg/dL on some forms)?
 *
 * Reference thresholds (SPICE constants, for awareness — backend ships exact
 * values in `rule.params`):
 *   - `UPAZILA_FBS_RBS_MAXIMUM_VALUE_BD` = 15 mmol/L
 *   - `RBS_MAXIMUM_VALUE_BD` = 11.1 mmol/L
 *   - `FBS_MAXIMUM_VALUE_BD` = 7.0 mmol/L
 */
class GlucoseAboveThresholdEvaluator : GapEvaluator {

    override val ruleType: String = "glucose_above_threshold_no_referral"

    override suspend fun evaluate(
        assessmentData: Map<String, Any>,
        rule: DetectionRuleEnvelope,
        gap: BehaviouralGapEntity,
    ): GapDetectionResult? = null
}
