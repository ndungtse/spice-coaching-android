package com.medtroniclabs.microcoaching.domain.referral

import android.util.Log

/**
 * Referral-correctness evaluator used by the `spice_action_observed` event
 * in [com.medtroniclabs.microcoaching.MicroCoachingSDK.onAssessmentSubmitted].
 *
 * The backend's `clinical_observed` family carries three booleans
 * (`correctReferral`, `correctReferralLocation`, `correctReferralType`) per
 * the v1.1 events spec. Two evaluation paths:
 *
 * **Path A — real SPICE data** (preferred). When the host passes the
 * system-prescribed signals on `assessmentData` (`system_referral_status`
 * and `system_referral_reasons`), this resolver compares them against the
 * CHW's actual action (`is_referred`, `referred_reason`) on all three
 * axes independently:
 *
 *  - `correctReferral`         — did the CHW take the right top-level action
 *                                (refer / not refer)?
 *  - `correctReferralLocation` — same facility type (UPAZILA vs
 *                                COMMUNITY_CLINIC)? Substring match against
 *                                facility-type tokens in `referred_reason`.
 *  - `correctReferralType`     — same condition categories (NCD / CBS / TB /
 *                                ANC etc.)? Compares set membership of the
 *                                non-facility tokens.
 *
 * **Path B — fallback heuristic**. Non-SPICE hosts (sample app, future
 * integrators) and SPICE builds that pre-date the system-prescribed
 * surfacing fall back to the original truth table:
 *
 * | risk_level         | is_referred | correct |
 * |--------------------|-------------|---------|
 * | high / emergency   | true        | true    |
 * | high / emergency   | false       | false   |
 * | routine / moderate | false       | true    |
 * | routine / moderate | true        | false   |
 * | (anything else)    | false       | true    |  ← treat as "no referral expected"
 * | (anything else)    | true        | false   |
 *
 * All three axes collapse to the same value in Path B — the dashboard tile
 * still gets a usable correct/incorrect signal, just without per-axis
 * differentiation. Path A is strictly more informative when it's available.
 *
 * Every call logs which path ran and the inputs/outputs (Log.d) so the
 * `spice_action_observed` payload's correctness booleans can be traced
 * back to their source on-device. Renamed from `ReferralRulesStub` once
 * Path A landed — Path A is no longer a stub.
 */
internal object ReferralResolver {

    private const val TAG = "ReferralResolver"

    data class ReferralOutcome(
        val correctReferral: Boolean,
        val correctReferralLocation: Boolean,
        val correctReferralType: Boolean,
    )

    fun evaluate(assessmentData: Map<String, Any>): ReferralOutcome {
        val isReferred = (assessmentData["is_referred"] as? Boolean) ?: false
        val systemStatus = assessmentData["system_referral_status"] as? String

        return if (systemStatus != null) {
            evaluateWithSystemPrescription(systemStatus, isReferred, assessmentData)
        } else {
            evaluateFromRiskLevel(isReferred, assessmentData)
        }
    }

    // ── Path A: real SPICE data ───────────────────────────────────────────────

    private fun evaluateWithSystemPrescription(
        systemStatus: String,
        isReferred: Boolean,
        assessmentData: Map<String, Any>,
    ): ReferralOutcome {
        val systemReasons = splitTokens(assessmentData["system_referral_reasons"] as? String)
        val actualReasons = splitTokens(assessmentData["referred_reason"] as? String)

        val shouldRefer = systemStatus.equals("Referred", ignoreCase = true)
        val correctReferral = shouldRefer == isReferred

        // Location axis: pick out facility-type tokens with a substring
        // match so SPICE-side string transformations (lowercasing, prefixes)
        // don't break the comparison.
        val systemLocation = systemReasons.firstOrNull(::isFacilityToken)
        val actualLocation = actualReasons.firstOrNull(::isFacilityToken)
        val correctReferralLocation = when {
            !correctReferral -> false
            systemLocation == null -> true  // no location prescribed → trivially correct
            else -> systemLocation == actualLocation
        }

        // Type axis: condition categories (NCD / CBS / TB / ANC etc.) — the
        // non-facility tokens. Compare as sets so order doesn't matter.
        val systemTypeTokens = systemReasons.filterNot(::isFacilityToken).toSet()
        val actualTypeTokens = actualReasons.filterNot(::isFacilityToken).toSet()
        val correctReferralType = when {
            !correctReferral -> false
            systemTypeTokens.isEmpty() -> true  // no type prescribed → trivially correct
            else -> systemTypeTokens == actualTypeTokens
        }

        val outcome = ReferralOutcome(correctReferral, correctReferralLocation, correctReferralType)
        Log.d(
            TAG,
            "path=A(real) systemStatus=$systemStatus shouldRefer=$shouldRefer isReferred=$isReferred " +
                "systemLocation=$systemLocation actualLocation=$actualLocation " +
                "systemTypes=$systemTypeTokens actualTypes=$actualTypeTokens " +
                "→ correctReferral=$correctReferral location=$correctReferralLocation type=$correctReferralType",
        )
        return outcome
    }

    // ── Path B: fallback heuristic ────────────────────────────────────────────

    private fun evaluateFromRiskLevel(
        isReferred: Boolean,
        assessmentData: Map<String, Any>,
    ): ReferralOutcome {
        val risk = (assessmentData["risk_level"] as? String).orEmpty().lowercase()
        val shouldRefer = risk == "high" || risk == "emergency"
        val correct = shouldRefer == isReferred
        Log.d(
            TAG,
            "path=B(fallback) reason=no_system_referral_status risk_level=$risk " +
                "shouldRefer=$shouldRefer isReferred=$isReferred → all axes=$correct",
        )
        return ReferralOutcome(
            correctReferral = correct,
            correctReferralLocation = correct,
            correctReferralType = correct,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun splitTokens(commaJoined: String?): List<String> =
        commaJoined?.split(",")?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

    /**
     * Lenient substring match for facility-type tokens. SPICE produces
     * `FACILITY_TYPE_UPAZILA` / `FACILITY_TYPE_COMMUNITY_CLINIC` plus a few
     * derived forms; matching on the bare uppercase substrings tolerates
     * future renames (e.g. lowercased, suffixed) without code churn.
     */
    private fun isFacilityToken(token: String): Boolean =
        token.contains("UPAZILA") || token.contains("COMMUNITY_CLINIC")
}
