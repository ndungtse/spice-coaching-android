package com.medtroniclabs.microcoaching.domain.gaps

import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpiceReferralComplianceEvaluatorTest {

    private val gap = BehaviouralGapEntity(
        gapId = "gap-anc-1",
        gapCode = "referral_anc_emergency_acute",
        description = null,
        domain = "referral",
        severityDefault = "high",
        status = "active",
        detectionRule = null,
        lastSynced = 0L,
    )

    private val evaluator = SpiceReferralComplianceEvaluator()

    /** Build a compliance envelope from a `when` JSON fragment. */
    private fun rule(whenJson: String): DetectionRuleEnvelope =
        DetectionRuleEnvelope.parseOrNull(
            """
            {"schema_version":1,"evaluator":"spice_referral_compliance",
             "when":$whenJson,
             "metadata":{"tier":"subcondition","referral_type":"emergency"}}
            """.trimIndent(),
        )!!

    private fun fires(whenJson: String, state: Map<String, Any?>): Boolean =
        evaluator.evaluate(state, rule(whenJson), gap) != null

    // ------------------------------------------------------------- operators --

    @Test
    fun `contains_any fires when the resolved list intersects values`() {
        val w = """{"op":"contains_any","path":"recommended.referredReason","values":["X","Y"]}"""
        assertEquals(true, fires(w, mapOf("recommended" to mapOf("referredReason" to listOf("Y")))))
        assertEquals(false, fires(w, mapOf("recommended" to mapOf("referredReason" to listOf("Z")))))
        // missing path → empty list → no fire
        assertEquals(false, fires(w, mapOf("recommended" to emptyMap<String, Any?>())))
    }

    @Test
    fun `missed_referral fires only when recommended referred and CHW did not`() {
        val w = """{"op":"missed_referral"}"""
        assertEquals(
            true,
            fires(w, mapOf("recommended" to mapOf("isReferred" to true), "actual" to mapOf("didRefer" to false))),
        )
        assertEquals(
            false,
            fires(w, mapOf("recommended" to mapOf("isReferred" to true), "actual" to mapOf("didRefer" to true))),
        )
        // not recommended → never a missed referral
        assertEquals(
            false,
            fires(w, mapOf("recommended" to mapOf("isReferred" to false), "actual" to mapOf("didRefer" to false))),
        )
    }

    @Test
    fun `mismatch_contains_any fires when recommended hits values but actual does not`() {
        val w = """{"op":"mismatch_contains_any","recommended_path":"recommended.referredReason",
                    "actual_path":"actual.referralReasons","values":["High risk pregnant woman"]}"""
        // recommended hit, actual missed → fire
        assertEquals(
            true,
            fires(
                w,
                mapOf(
                    "recommended" to mapOf("referredReason" to listOf("High risk pregnant woman")),
                    "actual" to mapOf("referralReasons" to listOf("Something else")),
                ),
            ),
        )
        // both hit → no fire
        assertEquals(
            false,
            fires(
                w,
                mapOf(
                    "recommended" to mapOf("referredReason" to listOf("High risk pregnant woman")),
                    "actual" to mapOf("referralReasons" to listOf("High risk pregnant woman")),
                ),
            ),
        )
    }

    @Test
    fun `mismatch_urgency fires only when actual urgency is present and differs`() {
        val w = """{"op":"mismatch_urgency","recommended_urgency":"URGENT","actual_path":"actual.isUrgent"}"""
        assertEquals(true, fires(w, mapOf("actual" to mapOf("isUrgent" to false))))
        // Absent actual urgency = can't tell → NO fire (SPICE doesn't capture
        // it, so firing would be a false positive on a correct referral).
        assertEquals(false, fires(w, mapOf("actual" to emptyMap<String, Any?>())))
        assertEquals(false, fires(w, mapOf("actual" to mapOf("isUrgent" to true))))
    }

    @Test
    fun `mismatch operators do not fire when the actual side is absent`() {
        // recommended present, actual.* entirely absent → no false positives.
        val state = mapOf("recommended" to mapOf("referredReason" to listOf("High risk pregnant woman")))
        assertEquals(
            false,
            fires(
                """{"op":"mismatch_contains_any","recommended_path":"recommended.referredReason",
                    "actual_path":"actual.referralReasons","values":["High risk pregnant woman"]}""",
                state,
            ),
        )
        assertEquals(
            false,
            fires(
                """{"op":"mismatch_eq","recommended_path":"recommended.referralFacilityType",
                    "actual_path":"actual.destinationTier"}""",
                mapOf("recommended" to mapOf("referralFacilityType" to "Upazila Health Complex")),
            ),
        )
        assertEquals(
            false,
            fires("""{"op":"mismatch_urgency","recommended_urgency":"URGENT","actual_path":"actual.isUrgent"}""", state),
        )
    }

    @Test
    fun `not, exists, array_nonempty, map_key_nonempty`() {
        assertEquals(
            true,
            fires("""{"op":"exists","path":"recommended.referredReason"}""",
                mapOf("recommended" to mapOf("referredReason" to listOf("X")))),
        )
        assertEquals(
            false,
            fires("""{"op":"not","condition":{"op":"exists","path":"recommended.referredReason"}}""",
                mapOf("recommended" to mapOf("referredReason" to listOf("X")))),
        )
        assertEquals(
            true,
            fires("""{"op":"array_nonempty","path":"actual.referralReasons"}""",
                mapOf("actual" to mapOf("referralReasons" to listOf("X")))),
        )
        assertEquals(
            true,
            fires("""{"op":"map_key_nonempty","path":"recommended.assessmentDetails.anc.summary.highRiskPregnantWoman","key":"URGENT"}""",
                mapOf("recommended" to mapOf("assessmentDetails" to mapOf("anc" to mapOf("summary" to
                    mapOf("highRiskPregnantWoman" to mapOf("URGENT" to listOf("High Fever")))))))),
        )
    }

    @Test
    fun `unknown op and missing when never fire (fail-safe)`() {
        assertEquals(false, fires("""{"op":"teleport"}""", emptyMap()))
        // envelope with no `when`
        val noWhen = DetectionRuleEnvelope.parseOrNull(
            """{"schema_version":1,"evaluator":"spice_referral_compliance","metadata":{}}""",
        )!!
        assertNull(evaluator.evaluate(emptyMap(), noWhen, gap))
    }

    // -------------------------------------------- real v3 gap (anc emergency) --

    /** The exact `when` tree from ignored/v3/behavioural_gap.json. */
    private val ancEmergencyWhen = """
        {"op":"and","conditions":[
          {"op":"contains_any",
           "path":"recommended.assessmentDetails.anc.summary.highRiskPregnantWoman.URGENT",
           "values":["High Fever","Abnormal Pulse","Abnormal weight gain"]},
          {"op":"or","conditions":[
            {"op":"and","conditions":[
              {"op":"contains_any",
               "path":"recommended.assessmentDetails.anc.summary.highRiskPregnantWoman.URGENT",
               "values":["High Fever","Abnormal Pulse","Abnormal weight gain"]},
              {"op":"missed_referral"}]},
            {"op":"mismatch_contains_any","values":["High risk pregnant woman"],
             "actual_path":"actual.referralReasons","recommended_path":"recommended.referredReason"},
            {"op":"mismatch_urgency","actual_path":"actual.isUrgent","recommended_urgency":"URGENT"}]}]}
    """.trimIndent()

    private fun ancState(
        urgent: List<String>,
        isReferred: Boolean,
        recReasons: List<String>,
        didRefer: Boolean,
        actualReasons: List<String>,
        actualUrgent: Boolean,
    ): Map<String, Any?> = mapOf(
        "recommended" to mapOf(
            "isReferred" to isReferred,
            "referredReason" to recReasons,
            "assessmentDetails" to mapOf(
                "anc" to mapOf("summary" to mapOf("highRiskPregnantWoman" to mapOf("URGENT" to urgent))),
            ),
        ),
        "actual" to mapOf(
            "didRefer" to didRefer,
            "referralReasons" to actualReasons,
            "isUrgent" to actualUrgent,
        ),
    )

    @Test
    fun `anc emergency fires when an urgent referral was recommended but not made`() {
        val result = evaluator.evaluate(
            ancState(
                urgent = listOf("High Fever"),
                isReferred = true, recReasons = listOf("High risk pregnant woman"),
                didRefer = false, actualReasons = emptyList(), actualUrgent = false,
            ),
            rule(ancEmergencyWhen),
            gap,
        )
        assertNotNull(result)
        assertEquals("incorrect", result!!.outcome)
        assertEquals("spice_referral_compliance", result.ruleType)
        assertEquals("emergency", result.evidence["referral_type"]) // metadata-only evidence
    }

    @Test
    fun `anc emergency fires on urgency mismatch even when CHW referred`() {
        assertNotNull(
            evaluator.evaluate(
                ancState(
                    urgent = listOf("Abnormal Pulse"),
                    isReferred = true, recReasons = listOf("High risk pregnant woman"),
                    didRefer = true, actualReasons = listOf("High risk pregnant woman"), actualUrgent = false,
                ),
                rule(ancEmergencyWhen), gap,
            ),
        )
    }

    @Test
    fun `anc emergency does NOT fire when CHW referred urgently with matching reasons`() {
        assertNull(
            evaluator.evaluate(
                ancState(
                    urgent = listOf("High Fever"),
                    isReferred = true, recReasons = listOf("High risk pregnant woman"),
                    didRefer = true, actualReasons = listOf("High risk pregnant woman"), actualUrgent = true,
                ),
                rule(ancEmergencyWhen), gap,
            ),
        )
    }

    @Test
    fun `anc emergency does NOT fire when no urgent condition was recommended`() {
        // precondition contains_any is false → whole `and` false regardless of actual
        assertNull(
            evaluator.evaluate(
                ancState(
                    urgent = emptyList(),
                    isReferred = true, recReasons = emptyList(),
                    didRefer = false, actualReasons = emptyList(), actualUrgent = false,
                ),
                rule(ancEmergencyWhen), gap,
            ),
        )
    }

    // ------------------------------------------- location / facility-tier gap --

    @Test
    fun `mismatch_eq fires only when both sides present and differ`() {
        val w = """{"op":"mismatch_eq","recommended_path":"recommended.referralFacilityType",
                    "actual_path":"actual.destinationTier"}"""
        // both present + differ → fire
        assertEquals(true, fires(w, mapOf(
            "recommended" to mapOf("referralFacilityType" to "Upazila Health Complex"),
            "actual" to mapOf("destinationTier" to "Community Clinic"))))
        // both present + equal → no fire
        assertEquals(false, fires(w, mapOf(
            "recommended" to mapOf("referralFacilityType" to "Upazila Health Complex"),
            "actual" to mapOf("destinationTier" to "Upazila Health Complex"))))
        // recommended absent (actual present) → no fire (the null-recommended guard)
        assertEquals(false, fires(w, mapOf("actual" to mapOf("destinationTier" to "Community Clinic"))))
    }

    /** referral_location_upazila — the real v3 shape (eq precondition + mismatch_eq OR). */
    private val locationUpazilaWhen = """
        {"op":"and","conditions":[
          {"op":"or","conditions":[
            {"op":"eq","path":"recommended.referralFacilityType","value":"Upazila Health Complex"},
            {"op":"eq","path":"recommended.assessmentDetails.referralFacilityType","value":"Upazila Health Complex"}]},
          {"op":"or","conditions":[
            {"op":"missed_referral"},
            {"op":"mismatch_eq","actual_path":"actual.destinationTier","recommended_path":"recommended.referralFacilityType"},
            {"op":"mismatch_eq","actual_path":"actual.destinationTier","recommended_path":"recommended.assessmentDetails.referralFacilityType"}]}]}
    """.trimIndent()

    @Test
    fun `location upazila fires when CHW picked a different tier`() {
        assertNotNull(
            evaluator.evaluate(
                mapOf(
                    "recommended" to mapOf("referralFacilityType" to "Upazila Health Complex", "isReferred" to true),
                    "actual" to mapOf("didRefer" to true, "destinationTier" to "Community Clinic"),
                ),
                rule(locationUpazilaWhen), gap,
            ),
        )
    }

    @Test
    fun `location upazila does NOT fire when tier matches`() {
        assertNull(
            evaluator.evaluate(
                mapOf(
                    "recommended" to mapOf("referralFacilityType" to "Upazila Health Complex", "isReferred" to true),
                    "actual" to mapOf("didRefer" to true, "destinationTier" to "Upazila Health Complex"),
                ),
                rule(locationUpazilaWhen), gap,
            ),
        )
    }

    @Test
    fun `location upazila does NOT false-fire when destinationTier is absent`() {
        // The unresolved `recommended.assessmentDetails.referralFacilityType`
        // mismatch_eq branch must NOT fire just because actual is absent.
        assertNull(
            evaluator.evaluate(
                mapOf(
                    "recommended" to mapOf("referralFacilityType" to "Upazila Health Complex", "isReferred" to true),
                    "actual" to mapOf("didRefer" to true),
                ),
                rule(locationUpazilaWhen), gap,
            ),
        )
    }
}
