package com.medtroniclabs.microcoaching.domain.gaps

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity
import com.medtroniclabs.microcoaching.domain.gaps.evidence.EvidenceBuilder

/**
 * GAP_DETECTION_SDK.md §4.1 — `wrong_facility_tier`. The flagship rule.
 *
 * SPICE pre-computes the *expected* facility tier and forwards it under
 * `referralFacilityType` (paeds: `childReferralFacilityType`). When the CHW
 * confirms a referral and picks a facility, SPICE writes the tier of that
 * picked facility under `picked_facility_type` — straight string comparison
 * here (no local lookup, no facility cache).
 *
 * The rule fires when expected ≠ actual. Identical tiers, missing expected,
 * or missing picked → no signal (skip with diagnostic log).
 *
 * Evidence shipped: expected_tier, actual_tier, facility_id_hash (when
 * `referred_site_id` is also forwarded; SHA-256 of the raw id, never the
 * raw id).
 *
 * Note on Path A: this evaluator does not depend on any local facility
 * registry. The picked tier comes through as a string in the assessment
 * map. See [docs/gaps/GAPS_TEST.md §F](../../../../../docs/gaps/GAPS_TEST.md)
 * for the SPICE-side wiring that produces `picked_facility_type`.
 */
class WrongFacilityTierEvaluator : GapEvaluator {

    override val ruleType: String = "wrong_facility_tier"

    override suspend fun evaluate(
        assessmentData: Map<String, Any>,
        rule: DetectionRuleEnvelope,
        gap: BehaviouralGapEntity,
    ): GapDetectionResult? {
        val expectedTier = assessmentData[KEY_EXPECTED_TIER] as? String
            ?: assessmentData[KEY_EXPECTED_TIER_CHILD] as? String
        if (expectedTier == null) {
            // After the 2026-05 SPICE patch, `AssessmentEntityExt.toSdkAssessmentMap`
            // parses `assessmentDetails` and forwards this key whenever
            // `ReferralResultGenerator` set `REFERRAL_FACILITY_TYPE`. If you
            // see this skip on a referral-triggering assessment, the SPICE
            // patch isn't deployed or the assessment didn't trip a referral.
            Log.d(TAG, "Skip ${gap.gapCode}: no expectedTier in assessmentData")
            return null
        }

        val actualTier = assessmentData[KEY_PICKED_FACILITY_TYPE] as? String
        if (actualTier == null) {
            // Picked tier isn't surfaced by SPICE yet — see GAPS_TEST.md §F.2.
            // Until SPICE writes this key at picker confirmation time, the
            // rule cannot fire end-to-end on stock SPICE 2.0.
            Log.d(
                TAG,
                "Skip ${gap.gapCode}: no $KEY_PICKED_FACILITY_TYPE in assessmentData " +
                    "(SPICE-side picker capture not yet wired — see GAPS_TEST.md §F.2)",
            )
            return null
        }

        if (actualTier.equals(expectedTier, ignoreCase = true)) {
            Log.d(
                TAG,
                "${gap.gapCode}: tier match — expected=$expectedTier actual=$actualTier (no signal)",
            )
            return null
        }

        Log.i(
            TAG,
            "${gap.gapCode} FIRED — expected=$expectedTier actual=$actualTier",
        )
        val facilityId = assessmentData[KEY_REFERRED_SITE_ID] as? String
        val evidence = buildMap<String, Any?> {
            put("expected_tier", expectedTier)
            put("actual_tier", actualTier)
            facilityId?.let { put("facility_id_hash", EvidenceBuilder.hashId(it)) }
        }
        return GapDetectionResult(
            gapId = gap.gapId,
            gapCode = gap.gapCode,
            ruleType = ruleType,
            outcome = OUTCOME_INCORRECT,
            evidence = evidence,
        )
    }

    private companion object {
        const val TAG = "WrongFacilityTierEval"

        // SPICE AssessmentDefinedParams.REFERRAL_FACILITY_TYPE — the *expected* tier.
        const val KEY_EXPECTED_TIER = "referralFacilityType"
        // Paeds pathway: AssessmentDefinedParams.ID_CHILD_REFERRAL_FACILITY_TYPE.
        const val KEY_EXPECTED_TIER_CHILD = "childReferralFacilityType"
        // Picked facility's tier — Path A. Written by SPICE at picker confirm.
        const val KEY_PICKED_FACILITY_TYPE = "picked_facility_type"
        // Picked facility's id — optional, only used to hash into evidence.
        const val KEY_REFERRED_SITE_ID = "referred_site_id"

        const val OUTCOME_INCORRECT = "incorrect"
    }
}
