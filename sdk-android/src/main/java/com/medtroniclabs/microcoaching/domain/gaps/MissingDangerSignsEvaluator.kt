package com.medtroniclabs.microcoaching.domain.gaps

import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity

/**
 * GAP_DETECTION_SDK.md §4.4 — `missing_danger_signs_record`.
 *
 * Two scoped variants per design §6:
 *   - `neonatal_danger_signs_missed` (scope: `RMNCHNeonateAssessment`)
 *   - `danger_signs_documentation_skipped` (broad scope, all assessment types)
 *
 * The dispatch uses `rule.match.spice_event_codes` / `assessment_types` to
 * route to the right scope — the SAME evaluator instance handles both, since
 * the read logic (null/empty danger-signs field) is identical.
 *
 * TODO: implement after **C-SDK-2** is confirmed by the SPICE lead:
 *   - Exact key for the danger-signs list in `assessmentDetails`.
 *   - Is the key consistent across NCD / RMNCH / Neonate assessments? (Note:
 *     AssessmentDefinedParams has `dangerSignsExperienced12`,
 *     `dangerSignsExperienced13To27`, `dangerSignsExperienced28To40`, plus
 *     `GROUP_DANGER_SIGNS_RISK_IDENTIFICATION` — the structured key needs
 *     confirmation.)
 */
class MissingDangerSignsEvaluator : GapEvaluator {

    override val ruleType: String = "missing_danger_signs_record"

    override suspend fun evaluate(
        assessmentData: Map<String, Any>,
        rule: DetectionRuleEnvelope,
        gap: BehaviouralGapEntity,
    ): GapDetectionResult? = null
}
