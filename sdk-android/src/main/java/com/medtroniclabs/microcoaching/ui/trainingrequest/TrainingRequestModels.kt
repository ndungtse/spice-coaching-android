package com.medtroniclabs.microcoaching.ui.trainingrequest

import com.medtroniclabs.microcoaching.data.localized.LocalizedText
import com.medtroniclabs.microcoaching.util.LenientJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Backend `reason` max length — mirrored client-side on the input field. */
const val TRAINING_REQUEST_REASON_MAX_CHARS = 2000

/** Cap for the free-text new-module topic suggestion. */
const val TRAINING_REQUEST_CUSTOM_TITLE_MAX_CHARS = 200

/**
 * One row on the training-requests hub screen. The list API does not return a
 * review status, so the hub shows only what the request was for and when.
 */
data class TrainingRequestRow(
    val requestId: String,
    val moduleTitle: String,
    val reason: String?,
    val submittedDateLabel: String?,
)

/** One entry in the module picker (slim projection of the local module cache). */
data class ModulePickerItem(
    /** Module version id — what the training-request API takes. */
    val moduleId: String,
    /** Stable family id — what UI routes carry (e.g. the ModuleDetail lock). */
    val moduleFamilyId: String,
    val title: LocalizedText,
    val domain: String,
    val thumbnailUrl: String?,
)

/**
 * Modules the CHW may request: every cached family EXCEPT those already
 * assigned to them (the picker is for training they don't yet have). Mirrors
 * the Training-list match in `CoachingModuleStore` — a module counts as
 * assigned when its version id is in [assignedModuleIds] OR its family id is in
 * [assignedFamilyIds] (families the assignment payload keyed by family).
 */
fun List<ModulePickerItem>.excludingAssigned(
    assignedModuleIds: Set<String>,
    assignedFamilyIds: Set<String>,
): List<ModulePickerItem> = filter {
    it.moduleId !in assignedModuleIds && it.moduleFamilyId !in assignedFamilyIds
}

/**
 * In-memory picker search: matches the Bangla title verbatim or the English
 * title case-insensitively. Blank query returns the full list.
 */
fun List<ModulePickerItem>.filterByQuery(query: String): List<ModulePickerItem> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { item ->
        item.title.bn?.contains(q) == true ||
            item.title.en?.contains(q, ignoreCase = true) == true
    }
}

/** Trims the free-text reason; blank collapses to null so no field is sent. */
fun normalizeReason(raw: String): String? = raw.trim().ifEmpty { null }

/**
 * Free-text topic of a new-module suggestion, from a `module_requested`
 * event's `payload_json`. Null when absent/malformed. Used by the hub list and
 * the form's duplicate guard.
 */
fun parseRequestedModuleName(payloadJson: String?): String? =
    payloadStringField(payloadJson, "requested_module_name")

/** The optional `reason` a `module_requested` event carries in its `payload_json`. */
fun parseRequestedReason(payloadJson: String?): String? =
    payloadStringField(payloadJson, "reason")

/** Reads one top-level string field from a JSON-object payload string. */
private fun payloadStringField(payloadJson: String?, key: String): String? {
    if (payloadJson.isNullOrBlank()) return null
    return runCatching {
        (LenientJson.parseToJsonElement(payloadJson) as? JsonObject)
            ?.get(key)
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
