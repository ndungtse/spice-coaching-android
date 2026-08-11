package com.medtroniclabs.microcoaching.sync

/**
 * One inbound pull. Maps 1:1 onto the results [InboundSyncWorker] already computes, so a UI
 * section can ask about exactly the table it reads instead of inferring from an aggregate.
 */
enum class SyncDomain {
    /** `module_cache` + `assigned_module` — the Learning Library and Practice Zone. */
    MODULES,

    /** `published_source_document` — the Knowledge sub-tab. */
    PUBLISHED_DOCS,

    /** `assigned_video` — the Training sub-tab. */
    ASSIGNED_VIDEOS,
    GAPS,
    TRIGGERS,
    CONFIG,
    CHAT_FAQS,
    MORNING_CARDS,
}

/** Outcome of the last attempt at one [SyncDomain] in this process. */
sealed interface SyncOutcome {
    /**
     * Not attempted since the process started. Deliberately distinct from success: a cold
     * start has no verdict yet, so consumers render loading/last-known content — never an
     * error. (This is the cost of keeping status in memory, and it's the right trade: a
     * resurrected three-day-old failure would be actively misleading.)
     */
    data object Unknown : SyncOutcome

    data class Succeeded(val atMs: Long) : SyncOutcome

    /** [rawError] is developer-facing; classify via `CoachingError.from(kind, offline)` for UI. */
    data class Failed(val kind: SyncErrorKind, val rawError: String?, val atMs: Long) : SyncOutcome
}

/** Pure — testable without Android or WorkManager. */
internal fun SyncResult.toOutcome(nowMs: Long): SyncOutcome =
    if (success) {
        SyncOutcome.Succeeded(nowMs)
    } else {
        SyncOutcome.Failed(errorKind ?: SyncErrorKind.UNEXPECTED, error, nowMs)
    }

/**
 * The verdict of one completed inbound run. [startedAtMs] lets a manual refresh await *its
 * own* run rather than matching a replayed earlier one.
 */
data class InboundRunSummary(
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val outcomes: Map<SyncDomain, SyncOutcome>,
) {
    val anyFailure: Boolean get() = outcomes.values.any { it is SyncOutcome.Failed }
    val allSucceeded: Boolean get() =
        outcomes.isNotEmpty() && outcomes.values.all { it is SyncOutcome.Succeeded }
}
