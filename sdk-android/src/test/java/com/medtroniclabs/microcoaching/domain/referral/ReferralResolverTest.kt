package com.medtroniclabs.microcoaching.domain.referral

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferralResolverTest {

    // ── Path A: real SPICE data present ───────────────────────────────────────

    @Test
    fun `Path A — full match across all axes`() {
        val outcome = ReferralResolver.evaluate(
            mapOf(
                "system_referral_status" to "Referred",
                "system_referral_reasons" to "FACILITY_TYPE_UPAZILA,NCD",
                "is_referred" to true,
                "referred_reason" to "FACILITY_TYPE_UPAZILA,NCD",
            ),
        )
        assertTrue(outcome.correctReferral)
        assertTrue(outcome.correctReferralLocation)
        assertTrue(outcome.correctReferralType)
    }

    @Test
    fun `Path A — location mismatch only`() {
        // System says refer to UHC; CHW marked Community Clinic.
        val outcome = ReferralResolver.evaluate(
            mapOf(
                "system_referral_status" to "Referred",
                "system_referral_reasons" to "FACILITY_TYPE_UPAZILA,NCD",
                "is_referred" to true,
                "referred_reason" to "FACILITY_TYPE_COMMUNITY_CLINIC,NCD",
            ),
        )
        assertTrue(outcome.correctReferral)
        assertFalse(outcome.correctReferralLocation)
        assertTrue(outcome.correctReferralType)
    }

    @Test
    fun `Path A — type mismatch only`() {
        val outcome = ReferralResolver.evaluate(
            mapOf(
                "system_referral_status" to "Referred",
                "system_referral_reasons" to "FACILITY_TYPE_UPAZILA,NCD",
                "is_referred" to true,
                "referred_reason" to "FACILITY_TYPE_UPAZILA,TB",
            ),
        )
        assertTrue(outcome.correctReferral)
        assertTrue(outcome.correctReferralLocation)
        assertFalse(outcome.correctReferralType)
    }

    @Test
    fun `Path A — status that is not Referred and CHW did not refer`() {
        val outcome = ReferralResolver.evaluate(
            mapOf(
                "system_referral_status" to "OnTreatment",
                "is_referred" to false,
            ),
        )
        // shouldRefer = false (status not "Referred"); isReferred = false → correct
        assertTrue(outcome.correctReferral)
        assertTrue(outcome.correctReferralLocation)
        assertTrue(outcome.correctReferralType)
    }

    @Test
    fun `Path A — top-level mismatch collapses location and type to false`() {
        // System prescribed referral; CHW did not refer. Location/type are
        // moot — reported as false regardless of token comparison.
        val outcome = ReferralResolver.evaluate(
            mapOf(
                "system_referral_status" to "Referred",
                "system_referral_reasons" to "FACILITY_TYPE_UPAZILA,NCD",
                "is_referred" to false,
                "referred_reason" to "",
            ),
        )
        assertFalse(outcome.correctReferral)
        assertFalse(outcome.correctReferralLocation)
        assertFalse(outcome.correctReferralType)
    }

    @Test
    fun `Path A — system status case-insensitive`() {
        val outcome = ReferralResolver.evaluate(
            mapOf(
                "system_referral_status" to "referred",  // lowercase
                "is_referred" to true,
            ),
        )
        assertTrue(outcome.correctReferral)
    }

    @Test
    fun `Path A — token list with whitespace and empty entries`() {
        val outcome = ReferralResolver.evaluate(
            mapOf(
                "system_referral_status" to "Referred",
                "system_referral_reasons" to " FACILITY_TYPE_UPAZILA , , NCD ",
                "is_referred" to true,
                "referred_reason" to "FACILITY_TYPE_UPAZILA,NCD",
            ),
        )
        assertTrue(outcome.correctReferral)
        assertTrue(outcome.correctReferralLocation)
        assertTrue(outcome.correctReferralType)
    }

    // ── Path B: fallback heuristic (no system data) ───────────────────────────

    @Test
    fun `Path B — risk HIGH and referred → correct`() {
        val outcome = ReferralResolver.evaluate(
            mapOf(
                "risk_level" to "HIGH",
                "is_referred" to true,
            ),
        )
        assertTrue(outcome.correctReferral)
        assertTrue(outcome.correctReferralLocation)
        assertTrue(outcome.correctReferralType)
    }

    @Test
    fun `Path B — risk HIGH and not referred → incorrect`() {
        val outcome = ReferralResolver.evaluate(
            mapOf(
                "risk_level" to "HIGH",
                "is_referred" to false,
            ),
        )
        assertFalse(outcome.correctReferral)
        assertFalse(outcome.correctReferralLocation)
        assertFalse(outcome.correctReferralType)
    }

    @Test
    fun `Path B — risk ROUTINE and not referred → correct`() {
        val outcome = ReferralResolver.evaluate(
            mapOf(
                "risk_level" to "ROUTINE",
                "is_referred" to false,
            ),
        )
        assertTrue(outcome.correctReferral)
    }

    @Test
    fun `Path B — empty map defaults to no-referral-no-action correct`() {
        val outcome = ReferralResolver.evaluate(emptyMap())
        // No risk_level (shouldRefer=false), no is_referred (default false) → match
        assertTrue(outcome.correctReferral)
    }

    @Test
    fun `Path B — EMERGENCY risk treated as HIGH`() {
        val outcome = ReferralResolver.evaluate(
            mapOf(
                "risk_level" to "EMERGENCY",
                "is_referred" to true,
            ),
        )
        assertTrue(outcome.correctReferral)
    }
}
