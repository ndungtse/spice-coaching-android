package com.medtroniclabs.microcoaching.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Result types + tolerant parse helpers for [SyncApi], extracted verbatim
// (behaviour-preserving). Same package, so no consumer imports change.

// ── Result types ──────────────────────────────────────────────────────────────

/**
 * Why a sync attempt failed. Drives WorkManager retry decisions in
 * [InboundSyncWorker] and [OutboundSyncWorker] — see each worker's `doWork()`
 * for the mapping from kind to [androidx.work.ListenableWorker.Result].
 */
enum class SyncErrorKind {
    /** I/O failure — DNS, timeout, connection reset. Transient; worth retrying. */
    NETWORK,

    /** Backend returned 4xx. Permanent (auth, malformed request, missing endpoint). */
    HTTP_CLIENT,

    /** Backend returned 5xx. Transient (server load, deploy in progress). */
    HTTP_SERVER,

    /** Unexpected runtime exception (deserialization, NPE). Treat as permanent. */
    UNEXPECTED,
}

/** A parsed `assigned_module_ids` entry — tolerant of the field's shape drift. */
internal data class AssignedRef(val moduleId: String, val assignedAtIso: String?)

/**
 * Parse `assigned_module_ids` regardless of shape: the object form
 * `{ "module_id": …, "assigned_at": … }` or the legacy bare-string id. Malformed
 * or unknown elements are skipped, never thrown — a shape drift on this field must
 * not break the modules bundle (which carries `module_cache`). Top-level + pure so
 * it's unit-testable without constructing [SyncApi].
 */
internal fun parseAssignedRefs(elements: List<JsonElement>): List<AssignedRef> =
    elements.mapNotNull { el ->
        when (el) {
            is JsonObject -> {
                val id = (el["module_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val at = (el["assigned_at"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                AssignedRef(id, at)
            }
            // Legacy bare-id shape: only a string is a valid id — skip stray
            // numbers/booleans so malformed entries never masquerade as module ids.
            is JsonPrimitive ->
                if (el.isString) el.contentOrNull?.takeIf { it.isNotBlank() }?.let { AssignedRef(it, null) } else null
            else -> null
        }
    }

/** Resolve a URL slightly before its signature lapses, so it never expires mid-load. */
private const val EXPIRY_SAFETY_MARGIN_SEC = 10L

/**
 * Relative URL lifetime → absolute epoch-second expiry, trimmed by the safety
 * margin. Top-level so the sync pulls and the entity mappers that persist these
 * expiries compute them the same way.
 */
internal fun absoluteExpiry(nowSec: Long, expiresSeconds: Long): Long =
    nowSec + (expiresSeconds - EXPIRY_SAFETY_MARGIN_SEC).coerceAtLeast(0)

/**
 * Classify an HTTP status code into the kind that drives WorkManager retry
 * decisions. 4xx → permanent (client misconfiguration, won't fix itself);
 * 5xx → transient (server hiccup, worth retrying with backoff).
 *
 * Top-level + pure so the UI-side error taxonomy can reuse it without
 * constructing [SyncApi].
 */
internal fun httpKindFor(code: Int): SyncErrorKind =
    if (code in 400..499) SyncErrorKind.HTTP_CLIENT else SyncErrorKind.HTTP_SERVER

/**
 * Shared shape across every result type so workers can apply a uniform retry
 * predicate without caring which endpoint produced the result.
 *
 * [error] is the raw failure text for logs/telemetry — it is developer-facing
 * (OkHttp/backend prose) and must never be rendered to a CHW.
 */
interface SyncResult {
    val success: Boolean
    val errorKind: SyncErrorKind?
    val error: String?
}

data class OutboundResult(
    val syncedCount: Int = 0,
    val failedCount: Int = 0,
    val skipped: Boolean = false,
    override val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null && !skipped
}

data class ModulesResult(
    val upsertedCount: Int = 0,
    val prunedCount: Int = 0,
    /** Rows written to `assigned_module` for the CHW from this pull's `assigned_module_ids`. */
    val assignedCount: Int = 0,
    /**
     * Assigned module ids whose content isn't in `module_cache` after this pull —
     * i.e. the assignment landed but its module row was absent from the (watermark)
     * delta and wasn't previously cached, so it can't render. Drives the
     * self-heal full-catalogue hydrate in [SyncApi.pullModules].
     */
    val unresolvedAssignedCount: Int = 0,
    /** True when this pull fetched the full catalogue (`since=EPOCH`), not a delta. */
    val wasFullCatalogue: Boolean = false,
    val newWatermark: String? = null,
    override val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

/**
 * Whether an incremental modules pull should be followed by a full-catalogue
 * fetch to hydrate assigned modules whose content is missing from the cache.
 *
 * Pure so the self-heal decision in [SyncApi.pullModules] is unit-testable
 * without a Room/network harness:
 *  - only after an *incremental* pull ([wasFullCatalogue] = false) — a full pull
 *    is already authoritative, so re-fetching wouldn't add anything;
 *  - only when at least one assigned module is unresolved.
 */
internal fun shouldHydrateFullCatalogue(wasFullCatalogue: Boolean, unresolvedAssignedCount: Int): Boolean =
    !wasFullCatalogue && unresolvedAssignedCount > 0

data class GapsResult(
    val upsertedCount: Int = 0,
    val prunedCount: Int = 0,
    /**
     * Number of `chw_module_partial_completion` rows upserted in this pull.
     * Consumed by [InboundSyncWorker] to decide whether to refilter the
     * morning-modules list, since partial-completion state contributes to the
     * "to-reinforce" set used by the morning-cards filter.
     */
    val partialUpserted: Int = 0,
    val newWatermark: String? = null,
    override val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

data class TriggersResult(
    val triggerCount: Int = 0,
    val bindingCount: Int = 0,
    val prunedCount: Int = 0,
    val newWatermark: String? = null,
    override val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

data class ConfigResult(
    val upsertedCount: Int = 0,
    val newWatermark: String? = null,
    override val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

data class ChatFaqsResult(
    val upsertedCount: Int = 0,
    val newWatermark: String? = null,
    override val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

data class MorningCardsResult(
    val count: Int = 0,
    override val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

data class VideoProgressResult(
    val count: Int = 0,
    /** True when no CHW was signed in, so there was nothing to scope progress to. */
    val skipped: Boolean = false,
    val newWatermark: String? = null,
    override val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

data class PublishedSourceDocumentsResult(
    val count: Int = 0,
    override val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

/**
 * One source-document pull feeds two independently-reported domains, so it
 * carries both verdicts. A transport failure fails both alike; only the
 * per-table write can succeed for one and skip for the other.
 */
data class SourceDocumentsResult(
    val published: PublishedSourceDocumentsResult,
    val assignedVideos: AssignedVideosResult,
) {
    companion object {
        fun failed(error: String, kind: SyncErrorKind?) = SourceDocumentsResult(
            published = PublishedSourceDocumentsResult(error = error, errorKind = kind),
            assignedVideos = AssignedVideosResult(error = error, errorKind = kind),
        )
    }
}

data class AssignedVideosResult(
    val count: Int = 0,
    /** True when the pull ran (a CHW was signed in) vs. was skipped. */
    val skipped: Boolean = false,
    override val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

data class BadgesResult(
    /** Rows written to `badge` — the tenant catalogue unioned with the CHW's earned badges. */
    val count: Int = 0,
    /** How many of [count] the CHW has earned, for the log line. */
    val earnedCount: Int = 0,
    /** True when the pull was skipped because no CHW was signed in. */
    val skipped: Boolean = false,
    override val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}
