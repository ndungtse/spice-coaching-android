package com.medtroniclabs.microcoaching.domain.context

import kotlinx.serialization.Serializable

/**
 * Bounded context snapshot passed from SPICE to the SDK.
 *
 * This is the ONLY patient data the SDK ever receives — it does not access
 * SPICE's NCDMergerDatabase directly.
 *
 * Use [patientId] = hashed/anonymized patientTrackId from SPICE.
 * Never pass raw patient names, NID numbers, or phone numbers.
 *
 * In SPICE, build this from PatientDetailsModel:
 * ```kotlin
 * fun PatientDetailsModel.toPatientSnapshot() = PatientSnapshot(
 *     patientId = patientTrackId?.toString() ?: _id.toString(),
 *     ageYears = age?.toIntOrNull(),
 *     gender = gender,
 *     conditions = buildList {
 *         if (isDiabetesDiagnosis) add("DIABETES")
 *         if (isHtnDiagnosis) add("HYPERTENSION")
 *     },
 *     avgSystolic = avgSystolic?.toIntOrNull(),
 *     avgDiastolic = avgDiastolic?.toIntOrNull(),
 *     riskLevel = cvdRiskLevel,
 * )
 * ```
 */
@Serializable
data class PatientSnapshot(
    /**
     * Anonymized patient identifier. Use patientTrackId from SPICE.
     * The SDK stores a SHA-256 hash of this for telemetry.
     */
    val patientId: String,
    val ageYears: Int? = null,
    /** "Male" | "Female" | null */
    val gender: String? = null,
    /** Active clinical conditions. E.g. ["DIABETES", "HYPERTENSION"] */
    val conditions: List<String> = emptyList(),
    val avgSystolic: Int? = null,
    val avgDiastolic: Int? = null,
    /** BMI if available */
    val bmi: Float? = null,
    /** CVD risk level string from SPICE (e.g. "High", "Very High") */
    val riskLevel: String? = null,
    /** CVD risk score percentage */
    val riskScore: Float? = null,
    /** Fasting/random blood glucose (mmol/L). TEAM-CONFIRM: SPICE field name */
    val glucose: Float? = null,
    /** True if patient has active pregnancy. TEAM-CONFIRM: SPICE field name */
    val pregnancyStatus: Boolean? = null,
    /** Village identifier from SPICE geography. TEAM-CONFIRM: field name */
    val villageId: String? = null,
    /** Upazila identifier from SPICE geography. TEAM-CONFIRM: field name */
    val upazilaId: String? = null,
)
