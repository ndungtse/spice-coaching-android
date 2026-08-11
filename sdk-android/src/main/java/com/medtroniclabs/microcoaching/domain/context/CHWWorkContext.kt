package com.medtroniclabs.microcoaching.domain.context

import kotlinx.serialization.Serializable

/**
 * Anonymised summary of a CHW's recent screening activity.
 * Injected into the AI chat system prompt so the LLM can answer questions like
 * "how many patients did I screen today?" or "what conditions did I see recently?".
 *
 * No patient names or IDs — only conditions and risk level.
 */
@Serializable
data class CHWWorkContext(
    val screenedTodayCount: Int = 0,
    val recentPatients: List<RecentPatientSummary> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Anonymised summary for a single recently-screened patient.
 */
@Serializable
data class RecentPatientSummary(
    val conditions: List<String>,  // e.g. ["DIABETES", "HYPERTENSION"]
    val riskLevel: String?,        // "HIGH" / "MEDIUM" / "LOW"
    val screenedAtMs: Long,
    val villageId: String? = null,
)
