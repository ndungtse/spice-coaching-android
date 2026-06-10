package com.medtroniclabs.microcoaching.domain.gaps

import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity
import com.medtroniclabs.microcoaching.domain.telemetry.PatientIdHasher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WrongFacilityTierEvaluatorTest {

    private val gap = BehaviouralGapEntity(
        gapId = "gap-uuid-1",
        gapCode = "incorrect_referral_destination",
        description = null,
        domain = "referral",
        severityDefault = "moderate",
        status = "active",
        detectionRule = null,
        lastSynced = 0L,
    )

    private val rule = DetectionRuleEnvelope(
        schemaVersion = 1,
        ruleType = "wrong_facility_tier",
        params = JsonObject(emptyMap()),
        match = DetectionRuleEnvelope.MatchClause(),
    )

    private val evaluator = WrongFacilityTierEvaluator()

    @Test
    fun `fires when picked_facility_type differs from referralFacilityType`() = runBlocking {
        val result = evaluator.evaluate(
            assessmentData = mapOf(
                "referralFacilityType" to "Upazila Health Complex",
                "picked_facility_type" to "Community Clinic",
                "referred_site_id" to "fac-A",
            ),
            rule = rule,
            gap = gap,
        )

        assertNotNull(result)
        assertEquals("incorrect", result!!.outcome)
        assertEquals("wrong_facility_tier", result.ruleType)
        assertEquals("Upazila Health Complex", result.evidence["expected_tier"])
        assertEquals("Community Clinic", result.evidence["actual_tier"])
        // facility_id_hash is the SHA-256 of the raw id — never the raw id.
        assertEquals(PatientIdHasher.hash("fac-A"), result.evidence["facility_id_hash"])
    }

    @Test
    fun `omits facility_id_hash from evidence when referred_site_id is absent`() = runBlocking {
        val result = evaluator.evaluate(
            assessmentData = mapOf(
                "referralFacilityType" to "Upazila Health Complex",
                "picked_facility_type" to "Community Clinic",
            ),
            rule = rule,
            gap = gap,
        )

        assertNotNull(result)
        assertFalse(result!!.evidence.containsKey("facility_id_hash"))
    }

    @Test
    fun `does not fire when tiers match`() = runBlocking {
        val result = evaluator.evaluate(
            assessmentData = mapOf(
                "referralFacilityType" to "Upazila Health Complex",
                "picked_facility_type" to "Upazila Health Complex",
            ),
            rule = rule,
            gap = gap,
        )
        assertNull(result)
    }

    @Test
    fun `tier comparison is case-insensitive`() = runBlocking {
        val result = evaluator.evaluate(
            assessmentData = mapOf(
                "referralFacilityType" to "Upazila Health Complex",
                "picked_facility_type" to "UPAZILA HEALTH COMPLEX",
            ),
            rule = rule,
            gap = gap,
        )
        assertNull(result)
    }

    @Test
    fun `does not fire when expected tier is missing`() = runBlocking {
        val result = evaluator.evaluate(
            assessmentData = mapOf("picked_facility_type" to "Community Clinic"),
            rule = rule,
            gap = gap,
        )
        assertNull(result)
    }

    @Test
    fun `does not fire when picked tier is missing`() = runBlocking {
        // This is the current stock SPICE 2.0 state — picker doesn't capture tier.
        val result = evaluator.evaluate(
            assessmentData = mapOf("referralFacilityType" to "Community Clinic"),
            rule = rule,
            gap = gap,
        )
        assertNull(result)
    }

    @Test
    fun `also reads paediatric childReferralFacilityType key when present`() = runBlocking {
        val result = evaluator.evaluate(
            assessmentData = mapOf(
                "childReferralFacilityType" to "Upazila Health Complex",
                "picked_facility_type" to "Community Clinic",
            ),
            rule = rule,
            gap = gap,
        )
        assertNotNull(result)
        assertEquals("incorrect", result!!.outcome)
    }
}
