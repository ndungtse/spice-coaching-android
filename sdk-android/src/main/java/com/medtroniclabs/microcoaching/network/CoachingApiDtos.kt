package com.medtroniclabs.microcoaching.network

import com.medtroniclabs.microcoaching.data.localized.LocalizedText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Request/response models for [CoachingApiService]. Same package as the interface,
 * which stays limited to the endpoint surface.
 */

// ── Telemetry batch (POST /telemetry/events) ──────────────────────────────────

/**
 * Backend-aligned batch envelope. Every event type is flattened into [events].
 *
 * [chwId] and [tenantId] are Strings because callers forward whatever the host
 * already holds, but the backend types both as integers and rejects the whole
 * batch if either fails to parse as one.
 */
@Serializable
data class TelemetryBatch(
    @SerialName("events") val events: List<TelemetryEventPayload>,
    @SerialName("sdk_version") val sdkVersion: String,
    @SerialName("chw_id") val chwId: String,
    @SerialName("tenant_id") val tenantId: String? = null,
)

/**
 * Unified event payload matching the backend TelemetryEvent schema.
 *
 * All three local entity types (CoachingEvent, LlmTrace, DigitalProficiency) are
 * serialised into this shape. LlmTrace and DigitalProficiency store entity-specific
 * fields in [payloadJson].
 *
 * [eventDate] is derived from [timestampLocal] at mapper time (YYYY-MM-DD, UTC).
 */
@Serializable
data class TelemetryEventPayload(
    @SerialName("id") val id: String,
    @SerialName("event_family") val eventFamily: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("event_date") val eventDate: String,
    @SerialName("event_schema_version") val eventSchemaVersion: Int = 1,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("patient_visit_id") val patientVisitId: String? = null,
    @SerialName("patient_track_id") val patientTrackId: String? = null,
    @SerialName("patient_id_hash") val patientIdHash: String? = null,
    @SerialName("village_id") val villageId: String? = null,
    @SerialName("upazila_id") val upazilaId: String? = null,
    @SerialName("module_family_id") val moduleFamilyId: String? = null,
    @SerialName("module_id") val moduleId: String? = null,
    @SerialName("card_family_id") val cardFamilyId: String? = null,
    @SerialName("quiz_id") val quizFamilyId: String? = null,
    @SerialName("module_version") val moduleVersion: Int? = null,
    @SerialName("quiz_score_pct") val quizScorePct: Float? = null,
    @SerialName("clinical_domain") val clinicalDomain: String? = null,
    @SerialName("card_type") val cardType: String? = null,
    @SerialName("trigger_type") val triggerType: String? = null,
    @SerialName("inference_mode") val inferenceMode: String? = null,
    @SerialName("outcome") val outcome: String? = null,
    @SerialName("validator_status") val validatorStatus: String? = null,
    @SerialName("fallback_used") val fallbackUsed: Boolean? = null,
    @SerialName("network_state") val networkState: String? = null,
    @SerialName("payload_json") val payloadJson: JsonObject = JsonObject(emptyMap()),
    @SerialName("timestamp_utc") val timestampUtc: Long? = null,
    @SerialName("timestamp_local") val timestampLocal: Long,
)

/** Backend acknowledgment. [accepted]/[rejected]/[duplicates]/[buffered] are event IDs, not counts. */
@Serializable
data class TelemetryAckResponse(
    @SerialName("accepted") val accepted: List<String> = emptyList(),
    @SerialName("rejected") val rejected: List<String> = emptyList(),
    @SerialName("duplicates") val duplicates: List<String> = emptyList(),
    @SerialName("buffered") val buffered: List<String> = emptyList(),
    @SerialName("errors") val errors: List<String> = emptyList(),
)

/** Shared constants for the `since`-cursor sync endpoints. */
object SyncDefaults {
    /** ISO 8601 epoch — used as the `since` cursor on first sync. */
    const val EPOCH_ISO: String = "1970-01-01T00:00:00Z"
}

// ── Modules sync (GET /sync/modules) ──────────────────────────────────────────

/**
 * Backend response shape — mirrors `ModulesSyncBundle` in the deployed
 * coaching-platform OpenAPI spec.
 */
@Serializable
data class ModulesSyncBundle(
    @SerialName("modules") val modules: List<ModuleSyncPayload> = emptyList(),
    @SerialName("module_families") val moduleFamilies: List<ModuleFamilySyncPayload> = emptyList(),
    /**
     * Module families the backend has terminally retired (no published version
     * remains) since the requested `since`. Optional / forward-looking: today's
     * backend does not send it, so it defaults to empty and is a no-op. When the
     * backend adds it (the family-level "no published version remains" signal —
     * NOT version-level `deprecated_at`), [com.medtroniclabs.microcoaching.sync.SyncApi.pullModules]
     * deletes these incrementally without waiting for the periodic full-catalogue
     * reconcile.
     */
    @SerialName("retired_family_ids") val retiredFamilyIds: List<String> = emptyList(),
    /**
     * The authenticated user's assigned modules (the version `module.id`, the
     * backend's unit of assignment) — the full current set across every
     * assignment_type (individual / po_sk / geographical / group). Drives the
     * `assigned_module` table; the Training screen filters to these while
     * chat/BM25 keeps the full `modules` catalogue.
     *
     * Held as raw [JsonElement]s and parsed tolerantly by
     * [com.medtroniclabs.microcoaching.sync.SyncApi.parseAssignedRefs], which
     * accepts both bare id strings and `{ module_id, assigned_at }` objects.
     * Pinning one element type would fail the entire bundle — taking `modules`
     * down with it — if the backend sent the other.
     */
    @SerialName("assigned_module_ids") val assignedModuleIds: List<JsonElement> = emptyList(),
    /**
     * The CHW's training-request history as the server knows it. Supplements the
     * device's local `module_requested` event log so requests raised on another
     * device still show up; the local log stays the offline-first source.
     */
    @SerialName("requested_modules") val requestedModules: List<RequestedModulePayload> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String,
)

/** One training request the CHW has already submitted, per the server. */
@Serializable
data class RequestedModulePayload(
    @SerialName("request_id") val requestId: String,
    @SerialName("module_id") val moduleId: String? = null,
    @SerialName("requested_module_name") val requestedModuleName: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
)

/**
 * One module version row. `id` is the version UUID, stable across the
 * lifetime of a single published module; `module_family_id` groups versions.
 *
 * `cards` are opaque to Room and parsed at UI render time. `quiz` is typed.
 */
@Serializable
data class ModuleSyncPayload(
    @SerialName("id") val id: String,
    @SerialName("module_family_id") val moduleFamilyId: String,
    @SerialName("version") val version: Int,
    @SerialName("title") val title: LocalizedText? = null,
    @SerialName("title_bn") val titleBnLegacy: String? = null,
    @SerialName("title_en") val titleEnLegacy: String? = null,
    @SerialName("description") val description: LocalizedText? = null,
    @SerialName("description_bn") val descriptionBnLegacy: String? = null,
    @SerialName("description_en") val descriptionEnLegacy: String? = null,
    @SerialName("domain") val domain: String,
    @SerialName("sub_domain") val subDomain: String? = null,
    /**
     * Content-domain taxonomy: `clinical` | `digital` | `operational`. Categorises
     * the module for the SK/PO Learning Library & Practice Zone content-domain tag
     * Distinct from [domain] (clinical topic) and [moduleType]. Null on
     * legacy payloads → treated as `clinical` (the documented default).
     */
    @SerialName("content_domain") val contentDomain: String? = null,
    @SerialName("module_type") val moduleType: String,
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("estimated_minutes") val estimatedMinutes: Int,
    @SerialName("difficulty_level") val difficultyLevel: String,
    @SerialName("pass_threshold_override") val passThresholdOverride: Float? = null,
    @SerialName("clinically_reviewed") val clinicallyReviewed: Boolean,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("cards") val cards: List<JsonObject> = emptyList(),
    /** Opaque quiz rows — preserved verbatim in `quiz_json` (new + legacy shapes). */
    @SerialName("quiz") val quiz: List<JsonObject> = emptyList(),
    /**
     * UUIDs of training-PDF source documents the module was authored from.
     * Kept only as a fallback for backends that predate [sourceDocuments].
     */
    @SerialName("source_document_ids") val sourceDocumentIds: List<String> = emptyList(),
    /**
     * Source-document references, each carrying the document id plus display
     * metadata (`title`, `original_filename`). Surfaced as chat citation chips;
     * the URL to open one comes from the source-document catalogue.
     */
    @SerialName("source_documents") val sourceDocuments: List<SourceDocumentRef> = emptyList(),
    /** Whether this module version has a thumbnail image available. */
    @SerialName("has_thumbnail") val hasThumbnail: Boolean = false,
    /**
     * Presigned URL for the module thumbnail, delivered inline with the module so
     * no follow-up call is needed to render a tile. Null when the module has no
     * thumbnail, or on a backend that predates the field.
     */
    @SerialName("thumbnail_presigned_url") val thumbnailPresignedUrl: String? = null,
    /**
     * Lifetime of [thumbnailPresignedUrl] in seconds, converted to an absolute
     * epoch-second expiry when persisted.
     */
    @SerialName("thumbnail_presigned_expires_seconds") val thumbnailPresignedExpiresSeconds: Long? = null,
    /**
     * Module-level author/clinician-curated retrieval hints (`keywords_en`,
     * `keywords_bn`, `search_phrases_en/bn`, `synonyms_en`, `topic_tags`,
     * `clinical_conditions`, …). Kept opaque here and parsed structurally on the
     * device by [com.medtroniclabs.microcoaching.ai.retrieval.ModuleKnowledgeIndex]
     * — the shape carries many optional sub-keys and is additive, so we don't
     * pin a typed model. Null/absent for legacy payloads.
     */
    @SerialName("search_metadata") val searchMetadata: JsonObject? = null,
    /** The module's primary behavioural gap; null when the module has none. */
    @SerialName("primary_gap_id") val primaryGapId: String? = null,
    /** All behavioural-gap UUIDs this module addresses (primary + secondary). */
    @SerialName("behavioural_gap_ids") val behaviouralGapIds: List<String> = emptyList(),
) {
    /** Merged title from nested `title` map or legacy flat keys. */
    fun resolvedTitle(): LocalizedText {
        if (title != null && !title.isBlank()) return title
        return LocalizedText.fromBnEn(titleBnLegacy, titleEnLegacy)
    }

    /** Merged description from nested `description` map or legacy flat keys. */
    fun resolvedDescription(): LocalizedText {
        if (description != null && !description.isBlank()) return description
        return LocalizedText.fromBnEn(descriptionBnLegacy, descriptionEnLegacy)
    }
}

/**
 * One rich source-document reference from the module sync payload's
 * `source_documents` array. Only the fields the SDK renders/dereferences are
 * modelled; the rest of the backend object is ignored (`ignoreUnknownKeys`).
 *
 * Reused as the persisted + UI shape (module_cache / chat_messages
 * `source_documents_json`, [com.medtroniclabs.microcoaching.ui.chat.ChatMessage]),
 * so the same serializer round-trips wire → Room → UI.
 */
@Serializable
data class SourceDocumentRef(
    @SerialName("source_document_id") val id: String,
    @SerialName("title") val title: String? = null,
    @SerialName("original_filename") val originalFilename: String? = null,
    @SerialName("has_thumbnail") val hasThumbnail: Boolean = false,
)

// ── Source-document catalogue (GET /sync/source-documents) ────────────────────

/**
 * Both halves of the source-document catalogue in one response.
 *
 * [sourceDocuments] are the documents linked to published modules, filtered by the
 * request's `since`. [assignedDocuments] is the caller's complete assignment
 * snapshot and ignores `since`, so it is safe to reconcile against wholesale.
 * A document can appear in both.
 */
@Serializable
data class SourceDocumentsSyncBundle(
    @SerialName("source_documents") val sourceDocuments: List<SourceDocumentSyncDownloadItem> = emptyList(),
    @SerialName("assigned_documents") val assignedDocuments: List<SourceDocumentSyncDownloadItem> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String? = null,
)

/**
 * One source document with its inline presigned URLs. The `*_expires_seconds`
 * fields are URL lifetimes (relative seconds), converted to absolute epoch-second
 * expiries when persisted.
 *
 * [sourceType] (`pdf` | `pptx` | `docx` | `audio` | `video`) is what separates a
 * training video from a knowledge document. [assignedAt] is set only on rows from
 * `assigned_documents`.
 *
 * Every backend-optional scalar is nullable rather than defaulted: kotlinx rejects
 * an explicit `null` for a non-nullable field even when it has a default (the
 * default only covers an ABSENT key), which would fail the whole response.
 */
@Serializable
data class SourceDocumentSyncDownloadItem(
    @SerialName("source_document_id") val sourceDocumentId: String,
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("original_filename") val originalFilename: String? = null,
    /**
     * Bucket-prefixed object path (`{bucket}/{key}`). Persisted so an expired
     * [presignedUrl] can be re-signed via
     * [CoachingApiService.getPresignedUrls] without a full re-sync.
     */
    @SerialName("storage_path") val storagePath: String? = null,
    @SerialName("thumbnail_storage_path") val thumbnailStoragePath: String? = null,
    @SerialName("assigned_at") val assignedAt: String? = null,
    /** Playable length in ms; null until the backend has probed the media. */
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("presigned_url") val presignedUrl: String? = null,
    @SerialName("presigned_expires_seconds") val presignedExpiresSeconds: Long? = null,
    @SerialName("thumbnail_presigned_url") val thumbnailPresignedUrl: String? = null,
    @SerialName("thumbnail_presigned_expires_seconds") val thumbnailPresignedExpiresSeconds: Long? = null,
) {
    /** True for streamable media (audio or video) — both play via ExoPlayer. */
    val isPlayableMedia: Boolean
        get() = isVideo || sourceType.equals("audio", ignoreCase = true)

    /**
     * True only for video. Video is what backs the Training sub-tab; audio and every
     * other type (pdf/pptx/docx) belong to the Knowledge grid.
     */
    val isVideo: Boolean
        get() = sourceType.equals("video", ignoreCase = true)
}

// ── Video watch progress (GET /sync/video-progress) ───────────────────────────

/**
 * Watch progress the server holds for the CHW's assigned videos, changed since the
 * requested watermark. Videos the CHW has never played are absent rather than zeroed.
 *
 * This is read-only: progress is still written by `video_progress_updated` telemetry.
 * Its purpose is recovering a resume position the device no longer has — after a
 * reinstall, a data clear, or a move to another handset.
 */
@Serializable
data class VideoProgressSyncBundle(
    @SerialName("videos") val videos: List<VideoProgressItem> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String? = null,
)

/** Server-side progress for one video, keyed by its `source_document_id`. */
@Serializable
data class VideoProgressItem(
    @SerialName("source_document_id") val sourceDocumentId: String,
    @SerialName("last_position_ms") val lastPositionMs: Long = 0,
    @SerialName("percent_watched") val percentWatched: Double = 0.0,
    @SerialName("completed") val completed: Boolean = false,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
)

// ── Batch presign (POST /sync/presigned-urls) ─────────────────────────────────

/** At most [STORAGE_PATHS_PRESIGN_BATCH_SIZE] paths per request; the server rejects more. */
@Serializable
data class StoragePathsPresignRequest(
    @SerialName("storage_paths") val storagePaths: List<String>,
)

/**
 * Partial-success response: [urls] holds what the server signed, [missingPaths] what
 * it declined. A declined path is a normal outcome — the object is gone, or the path
 * isn't in a shape the server will sign — so callers report it separately from a
 * transport failure rather than treating the whole call as an error.
 */
@Serializable
data class StoragePathsPresignResponse(
    @SerialName("urls") val urls: List<StoragePathPresignedUrl> = emptyList(),
    @SerialName("missing_paths") val missingPaths: List<String> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String? = null,
)

@Serializable
data class StoragePathPresignedUrl(
    @SerialName("storage_path") val storagePath: String,
    @SerialName("presigned_url") val presignedUrl: String,
    @SerialName("expires_seconds") val expiresSeconds: Long = 0,
)

/** Server-side cap on one presign batch. */
const val STORAGE_PATHS_PRESIGN_BATCH_SIZE: Int = 50

// ── Badge catalogue (GET /sync/badges) ────────────────────────────────────────

/**
 * The tenant's active badges plus the ones this CHW has earned, in one response.
 *
 * A badge normally appears in both lists once earned; [earnedBadges] can also
 * carry a badge that is no longer in [availableBadges] (its definition was
 * deactivated after the CHW earned it), which is why consumers union the two
 * rather than treating [availableBadges] as the whole catalogue.
 */
@Serializable
data class BadgesSyncBundle(
    @SerialName("available_badges") val availableBadges: List<BadgeSyncPayload> = emptyList(),
    @SerialName("earned_badges") val earnedBadges: List<BadgeSyncPayload> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String? = null,
)

/**
 * One badge. [imagePresignedExpiresSeconds] is a URL lifetime (relative seconds),
 * converted to an absolute epoch-second expiry when persisted.
 *
 * [earnedAt] is the only field that separates the two lists — it is absent on
 * `available_badges` rows. [sequence] orders the badge grid and the Your Journey
 * path; [moduleIds] are the modules the badge is awarded for.
 *
 * Every backend-optional scalar is nullable rather than defaulted, for the same
 * reason as [SourceDocumentSyncDownloadItem]: a default only covers an absent key,
 * so an explicit `null` on a non-nullable field would fail the whole response.
 */
@Serializable
data class BadgeSyncPayload(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String? = null,
    @SerialName("domain") val domain: String? = null,
    @SerialName("image_storage_path") val imageStoragePath: String? = null,
    @SerialName("image_presigned_url") val imagePresignedUrl: String? = null,
    @SerialName("image_presigned_expires_seconds") val imagePresignedExpiresSeconds: Long? = null,
    @SerialName("sequence") val sequence: Int? = null,
    @SerialName("module_ids") val moduleIds: List<String> = emptyList(),
    @SerialName("earned_at") val earnedAt: String? = null,
)


@Serializable
data class ModuleFamilySyncPayload(
    @SerialName("id") val id: String,
    @SerialName("module_code") val moduleCode: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("current_published_module_id") val currentPublishedModuleId: String? = null,
)

// Note: module quiz rows are carried verbatim in `ModuleSyncPayload.quiz`
// (opaque `List<JsonObject>`) and parsed on-device at render time by
// `parseInlineQuiz()` — which reads the nested `question/options/explanation/
// case_setup: {bn, en}` shape with a legacy flat-key fallback. There is no typed
// quiz DTO here; a former `ModuleQuizQuestionPayload` (flat `question_bn`/… keys)
// was removed as dead code.

// ── Gaps sync (GET /sync/gaps) ────────────────────────────────────────────────

/**
 * Backend bundle: the gap taxonomy plus optional per-CHW state when [chwId]
 * was passed on the request.
 */
@Serializable
data class GapsSyncBundle(
    @SerialName("behavioural_gaps") val behaviouralGaps: List<BehaviouralGapSyncPayload> = emptyList(),
    @SerialName("chw_behavioural_gap_states") val chwBehaviouralGapStates: List<ChwBehaviouralGapStateSyncPayload> = emptyList(),
    @SerialName("chw_quiz_question_states") val chwQuizQuestionStates: List<ChwQuizQuestionStateSyncPayload> = emptyList(),
    @SerialName("chw_module_completions") val chwModuleCompletions: List<ChwModuleCompletionSyncPayload> = emptyList(),
    @SerialName("chw_module_partial_completions") val chwModulePartialCompletions: List<ChwModulePartialCompletionSyncPayload> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String,
)

@Serializable
data class BehaviouralGapSyncPayload(
    @SerialName("id") val id: String,
    @SerialName("gap_code") val gapCode: String,
    @SerialName("description") val description: String,
    @SerialName("domain") val domain: String,
    @SerialName("severity_default") val severityDefault: String,
    @SerialName("detection_rule_jsonb") val detectionRule: JsonObject = JsonObject(emptyMap()),
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ChwBehaviouralGapStateSyncPayload(
    @SerialName("chw_id") val chwId: String,
    @SerialName("behavioural_gap_id") val behaviouralGapId: String,
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("severity_current") val severityCurrent: String,
    @SerialName("first_observed_at") val firstObservedAt: String? = null,
    @SerialName("last_observed_at") val lastObservedAt: String? = null,
    @SerialName("last_reinforced_at") val lastReinforcedAt: String? = null,
    @SerialName("occurrence_count") val occurrenceCount: Int,
    @SerialName("failed_attempts_count") val failedAttemptsCount: Int,
    @SerialName("last_failed_attempt_at") val lastFailedAttemptAt: String? = null,
    @SerialName("escalated_to_supervisor") val escalatedToSupervisor: Boolean,
    @SerialName("status") val status: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** Quiz-level refresher state (`chw_quiz_question_state`); quiz-mode `/sync/gaps`. */
@Serializable
data class ChwQuizQuestionStateSyncPayload(
    @SerialName("chw_id") val chwId: String,
    @SerialName("quiz_id") val quizId: String,
    @SerialName("module_id") val moduleId: String,
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("failed_attempts_count") val failedAttemptsCount: Int,
    @SerialName("last_failed_attempt_at") val lastFailedAttemptAt: String? = null,
    @SerialName("first_attempt_at") val firstAttemptAt: String? = null,
    @SerialName("last_attempt_at") val lastAttemptAt: String? = null,
    @SerialName("escalated_to_supervisor") val escalatedToSupervisor: Boolean,
    @SerialName("status") val status: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ChwModuleCompletionSyncPayload(
    @SerialName("chw_id") val chwId: String,
    @SerialName("module_family_id") val moduleFamilyId: String,
    @SerialName("latest_completed_module_id") val latestCompletedModuleId: String? = null,
    @SerialName("latest_attempt_module_id") val latestAttemptModuleId: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("latest_attempt_at") val latestAttemptAt: String? = null,
    @SerialName("latest_quiz_score") val latestQuizScore: Float? = null,
    @SerialName("latest_attempt_passed") val latestAttemptPassed: Boolean,
    @SerialName("attempts_since_last_pass") val attemptsSinceLastPass: Int,
    @SerialName("reinforcement_due_at") val reinforcementDueAt: String? = null,
    @SerialName("tenant_id") val tenantId: String? = null,
)

/**
 * Server-derived per-CHW per-module set of question IDs the CHW still needs to
 * answer correctly. Hydrated from per-question `module_quiz_attempted` events
 * (any question never attempted or whose latest answer was wrong). Authoritative
 * source for cross-device "to-reinforce" recovery — see `ToReinforceResolver`.
 */
@Serializable
data class ChwModulePartialCompletionSyncPayload(
    @SerialName("chw_id") val chwId: String,
    @SerialName("module_id") val moduleId: String? = null,
    @SerialName("module_family_id") val moduleFamilyId: String,
    @SerialName("incomplete_quiz_ids") val incompleteQuizIds: List<String> = emptyList(),
    @SerialName("tenant_id") val tenantId: String? = null,
)

// ── Triggers sync (GET /sync/triggers) ────────────────────────────────────────

@Serializable
data class TriggersSyncBundle(
    @SerialName("triggers") val triggers: List<TriggerDefinitionSyncPayload> = emptyList(),
    @SerialName("bindings") val bindings: List<ModuleTriggerBindingSyncPayload> = emptyList(),
    @SerialName("server_time_utc") val serverTimeUtc: String,
)

@Serializable
data class TriggerDefinitionSyncPayload(
    @SerialName("id") val id: String,
    @SerialName("trigger_kind") val triggerKind: String,
    @SerialName("trigger_code") val triggerCode: String,
    @SerialName("description") val description: String? = null,
    @SerialName("predicate_jsonb") val predicate: JsonObject = JsonObject(emptyMap()),
    @SerialName("predicate_schema_version") val predicateSchemaVersion: Int = 1,
    @SerialName("status") val status: String,
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ModuleTriggerBindingSyncPayload(
    @SerialName("id") val id: String,
    @SerialName("trigger_definition_id") val triggerDefinitionId: String,
    // Backend binds a trigger to a specific published `module_id`; the SDK resolves
    // it to the module's family (one binding row per family) at sync time.
    @SerialName("module_id") val moduleId: String,
    @SerialName("relationship") val relationship: String,
    @SerialName("priority_weight") val priorityWeight: Int,
    @SerialName("notes") val notes: String? = null,
)

// ── Config sync (GET /sync/config) ────────────────────────────────────────────

/**
 * Backend ships a flat key→value dict of thresholds. Values are mixed types;
 * we hold them as raw JSON elements and coerce at read time.
 */
@Serializable
data class ConfigSyncBundle(
    @SerialName("thresholds") val thresholds: Map<String, JsonElement> = emptyMap(),
    @SerialName("server_time_utc") val serverTimeUtc: String,
)

// ── Chat FAQs sync (GET /sync/chat-faqs) ──────────────────────────────────────

/**
 * Ranked chat-FAQ suggestions. `computed_at` is backend metadata (nullable);
 * `server_time_utc` is the watermark for the next incremental pull.
 */
@Serializable
data class ChatFaqsSyncBundle(
    @SerialName("faqs") val faqs: List<ChatFaqPayload> = emptyList(),
    @SerialName("computed_at") val computedAt: String? = null,
    @SerialName("server_time_utc") val serverTimeUtc: String,
)

/**
 * One frequently-asked chat question. [question] is the bilingual `{bn, en?}`
 * map — Bangla is guaranteed; English may be absent and is backfilled on-device.
 * [rank] drives display order (lower = higher priority).
 */
@Serializable
data class ChatFaqPayload(
    @SerialName("id") val id: String,
    @SerialName("question") val question: LocalizedText = LocalizedText.EMPTY,
    @SerialName("rank") val rank: Int = 0,
    @SerialName("occurrence_count") val occurrenceCount: Int = 0,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

// ── Morning cards (GET /morning/cards) ────────────────────────────────────────

@Serializable
data class MorningCardsResponse(
    @SerialName("items") val items: List<MorningModuleSuggestionItem> = emptyList(),
)

/** A single module suggestion from the morning-cards endpoint. */
@Serializable
data class MorningModuleSuggestionItem(
    @SerialName("module_id") val moduleId: String,
    @SerialName("module_family_id") val moduleFamilyId: String,
    /** "quiz" | "gap" | "fallback" — drives the GAP badge in the refresher list. */
    @SerialName("source") val source: String,
    /** Non-null when source == "gap". Forwarded to telemetry payload_json. */
    @SerialName("behavioural_gap_id") val behaviouralGapId: String? = null,
    /** Non-null when source == "quiz" — the `module_quiz_question.id`. */
    @SerialName("quiz_id") val quizId: String? = null,
)

// ── RAG chat (POST /coaching/rag-query) ───────────────────────────────────────

/** [question] must be at least 3 characters — the backend rejects shorter ones. */
@Serializable
data class RagQueryRequest(
    @SerialName("question") val question: String,
    @SerialName("response_language") val responseLanguage: String,
)

@Serializable
data class RagQueryResponse(
    @SerialName("answer") val answer: String,
    @SerialName("retrieved_modules") val retrievedModules: List<RagRetrievedModule> = emptyList(),
    @SerialName("source_documents") val sourceDocuments: List<RagSourceDocument> = emptyList(),
    @SerialName("model") val model: String? = null,
    @SerialName("cited_module_ids") val citedModuleIds: List<String> = emptyList(),
    /**
     * Contextual follow-up questions generated by the backend in the requested
     * `response_language` (bn). Surfaced as the chat suggestion chips after the
     * answer — see `ChatViewModel.handleBackendRagMessage`. Empty when the backend
     * sends none (chips keep their previous seeds).
     */
    @SerialName("suggested_questions") val suggestedQuestions: List<String> = emptyList(),
)

@Serializable
data class RagRetrievedModule(
    @SerialName("module_id") val moduleId: String,
    @SerialName("title") val title: LocalizedText? = null,
    @SerialName("title_bn") val titleBnLegacy: String? = null,
    @SerialName("title_en") val titleEnLegacy: String? = null,
    @SerialName("domain") val domain: String? = null,
    @SerialName("cosine_distance") val cosineDistance: Double? = null,
) {
    /** Bangla title — nested `title: {bn, en}` first, then legacy flat `title_bn`. */
    val titleBn: String? get() = title?.bn?.takeIf { it.isNotBlank() } ?: titleBnLegacy

    /** English title — nested `title: {bn, en}` first, then legacy flat `title_en`. */
    val titleEn: String? get() = title?.en?.takeIf { it.isNotBlank() } ?: titleEnLegacy
}

@Serializable
data class RagSourceDocument(
    @SerialName("source_document_id") val sourceDocumentId: String,
    @SerialName("title") val title: String? = null,
    @SerialName("original_filename") val originalFilename: String? = null,
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("page_numbers") val pageNumbers: List<Int> = emptyList(),
    @SerialName("source_pages") val sourcePages: List<RagSourcePage> = emptyList(),
    /**
     * Presigned URL returned inline by the backend. Captured for future optimisation
     * (skipping the separate getSourceDocumentPresignedUrls call) but not used yet —
     * DocumentPreviewActivity always fetches via the existing endpoint.
     */
    @SerialName("presigned_url") val presignedUrl: String? = null,
    @SerialName("presigned_expires_seconds") val presignedExpiresSeconds: Int? = null,
    @SerialName("linked_module_ids") val linkedModuleIds: List<String> = emptyList(),
)

@Serializable
data class RagSourcePage(
    @SerialName("page_number") val pageNumber: Int,
    @SerialName("start_ms") val startMs: Int? = null,
    @SerialName("end_ms") val endMs: Int? = null,
)

// A CHW training request has no request DTO of its own — it is a
// `module_requested` telemetry event, carried by TelemetryBatch to
// POST /telemetry/events.
