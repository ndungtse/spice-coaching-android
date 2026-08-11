package com.medtroniclabs.microcoaching.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Per-domain outcome of inbound sync, so a UI section can scope its failure state to the one
 * table it actually reads. [InboundSyncWorker] computes a [SyncResult] for each of its ten
 * pulls; this is where those verdicts become observable instead of being logged and dropped.
 *
 * **In memory, not persisted.** WorkManager runs in the host process, so the worker's writes
 * are visible to the UI directly. State resets to [SyncOutcome.Unknown] on process death,
 * which is the correct default — see [SyncOutcome.Unknown].
 */
class SyncStatusStore internal constructor() {

    private val _outcomes = MutableStateFlow<Map<SyncDomain, SyncOutcome>>(emptyMap())
    val outcomes: StateFlow<Map<SyncDomain, SyncOutcome>> = _outcomes.asStateFlow()

    /**
     * One emission per completed inbound run. `replay = 1` so a collector that subscribes
     * just after a run still sees it — callers awaiting their *own* run must filter on
     * [InboundRunSummary.startedAtMs].
     */
    private val _runs = MutableSharedFlow<InboundRunSummary>(replay = 1, extraBufferCapacity = 4)
    val runs: SharedFlow<InboundRunSummary> = _runs.asSharedFlow()

    /**
     * Publish a finished run's verdicts in one shot — a single state update rather than ten,
     * so subscribers don't recompose against a half-written map.
     */
    internal fun publishRun(summary: InboundRunSummary) {
        _outcomes.value = _outcomes.value + summary.outcomes
        _runs.tryEmit(summary)
    }

    /** The latest verdict for one domain, defaulting to [SyncOutcome.Unknown]. */
    fun outcomeFor(domain: SyncDomain): Flow<SyncOutcome> =
        outcomes.map { it[domain] ?: SyncOutcome.Unknown }.distinctUntilChanged()
}
