package com.medtroniclabs.microcoaching.sync

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide single-flight gates for the sync workers.
 *
 * Outbound work is enqueued under three different unique WorkManager names
 * (periodic, the one-shot chain head, and the `_flush` hook path) and inbound
 * under two (periodic and the chained one-shot), so WorkManager's unique-work
 * dedup alone cannot prevent two workers of the same direction running
 * concurrently. Concurrent runs don't corrupt data, but each one loads the
 * full pending set / module catalogue into memory at the same time — exactly
 * the allocation spike that pushed low-RAM field devices into OOM when several
 * triggers fired together (connectivity restore + pull-to-refresh + a hook).
 *
 * The workers serialize on these mutexes instead: a straggler that enters
 * after the first run finishes sees nothing pending (or an advanced watermark)
 * and returns cheaply.
 */
internal object SyncGate {
    /** Serializes [OutboundSyncWorker] runs across all outbound work names. */
    val outbound = Mutex()

    /** Serializes [InboundSyncWorker] runs across both inbound work names. */
    val inbound = Mutex()
}
