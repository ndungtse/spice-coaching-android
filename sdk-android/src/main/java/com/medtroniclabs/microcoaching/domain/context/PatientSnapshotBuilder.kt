package com.medtroniclabs.microcoaching.domain.context

import android.util.Log
import java.security.MessageDigest

/**
 * Builds a [PatientSnapshot] from the raw assessment data map passed by SPICE hooks.
 *
 * All SPICE field names are marked TEAM-CONFIRM (see TIMELINE_v2.md Q2).
 * Build now with nullable stubs; replace key names once SPICE sandbox access is confirmed.
 *
 * Never logs or stores the raw patient ID — SHA-256 hash only.
 */
object PatientSnapshotBuilder {

    private const val TAG = "PatientSnapshotBuilder"

    fun build(data: Map<String, Any>): PatientSnapshot {
        val rawId = data["patient_track_id"]?.toString() // TEAM-CONFIRM
            ?: data["patient_id"]?.toString()            // TEAM-CONFIRM fallback key
            ?: "unknown"
        return PatientSnapshot(
            patientId = hashId(rawId),
            ageYears = (data["age"] as? Number)?.toInt(),               // TEAM-CONFIRM
            gender = data["gender"]?.toString(),                         // TEAM-CONFIRM: "Male" | "Female"
            conditions = buildConditions(data),
            avgSystolic = (data["avg_systolic"] as? Number)?.toInt(),   // TEAM-CONFIRM
            avgDiastolic = (data["avg_diastolic"] as? Number)?.toInt(), // TEAM-CONFIRM
            bmi = (data["bmi"] as? Number)?.toFloat(),                  // TEAM-CONFIRM
            riskLevel = data["cvd_risk_level"]?.toString(),             // TEAM-CONFIRM
            riskScore = (data["cvd_risk_score"] as? Number)?.toFloat(), // TEAM-CONFIRM
            glucose = (data["fbs_value"] as? Number)?.toFloat(),        // TEAM-CONFIRM
            pregnancyStatus = data["is_pregnant"] as? Boolean,          // TEAM-CONFIRM
            villageId = data["village_id"]?.toString(),                 // TEAM-CONFIRM
            upazilaId = data["upazila_id"]?.toString(),                 // TEAM-CONFIRM
        )
    }

    private fun buildConditions(data: Map<String, Any>): List<String> = buildList {
        if (data["is_diabetes_diagnosis"] == true || data["is_diabetes"] == true) add("DIABETES")     // TEAM-CONFIRM
        if (data["is_htn_diagnosis"] == true || data["is_htn"] == true) add("HYPERTENSION")          // TEAM-CONFIRM
        if (data["is_pregnant"] == true) add("PREGNANT")                                              // TEAM-CONFIRM
    }

    private fun hashId(rawId: String): String = try {
        val bytes = MessageDigest.getInstance("SHA-256").digest(rawId.toByteArray())
        bytes.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.e(TAG, "SHA-256 hash failed, using raw ID placeholder: ${e.message}")
        "hash_error"
    }
}
