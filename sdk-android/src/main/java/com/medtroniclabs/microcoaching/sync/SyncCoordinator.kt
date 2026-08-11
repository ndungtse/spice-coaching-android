package com.medtroniclabs.microcoaching.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Orchestrates [OutboundSyncWorker] and [InboundSyncWorker] via WorkManager.
 *
 * Sync triggers:
 *   1. Periodic — every [PERIODIC_INTERVAL_MINUTES] minutes when network is available.
 *      Scheduled once at SDK init and re-registered idempotently on each app launch.
 *   2. Immediate — triggered by [triggerNow] on connectivity restore ([onConnectivityRestored])
 *      or on explicit SDK call.
 *
 * Worker ordering: outbound runs first (flush events), then inbound (fetch scenarios).
 * WorkManager's chaining ensures inbound starts only after outbound completes.
 *
 * Retry policy: exponential backoff starting at [BACKOFF_DELAY_SECONDS] seconds,
 * capped by WorkManager's default maximum (~5 hours).
 *
 * Network constraint: [NetworkType.CONNECTED] — workers only run when online.
 * Queued work is deferred automatically until connectivity is restored.
 */
class SyncCoordinator(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Schedules periodic background sync. Call once from [com.medtroniclabs.microcoaching.MicroCoachingSDK.Builder.build].
     * Uses [ExistingPeriodicWorkPolicy.KEEP] — safe to call on every app launch.
     */
    fun schedulePeriodic() {
        val outboundRequest = PeriodicWorkRequestBuilder<OutboundSyncWorker>(
            PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()

        val inboundRequest = PeriodicWorkRequestBuilder<InboundSyncWorker>(
            PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            OutboundSyncWorker.WORK_NAME + "_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            outboundRequest,
        )
        workManager.enqueueUniquePeriodicWork(
            InboundSyncWorker.WORK_NAME + "_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            inboundRequest,
        )

        Log.d(TAG, "Periodic sync scheduled (${PERIODIC_INTERVAL_MINUTES}m interval).")
    }

    /**
     * Triggers an immediate sync cycle: outbound first, then inbound.
     *
     * Uses [ExistingWorkPolicy.REPLACE] for the outbound leg — if a sync is already
     * in progress, it is cancelled and restarted. This ensures fresh events captured
     * after connectivity restore are always included in the next batch.
     *
     * Call from:
     *   - [com.medtroniclabs.microcoaching.MicroCoachingSDK.onConnectivityRestored]
     *   - TP-8 [com.medtroniclabs.microcoaching.MicroCoachingSDK.onConnectivityChanged] (Phase E)
     */
    fun triggerNow() {
        val outboundRequest = OneTimeWorkRequestBuilder<OutboundSyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()

        val inboundRequest = OneTimeWorkRequestBuilder<InboundSyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()

        workManager
            .beginUniqueWork(
                OutboundSyncWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                outboundRequest,
            )
            .then(inboundRequest)
            .enqueue()

        Log.d(TAG, "One-shot sync enqueued (outbound → inbound).")
    }

    /**
     * Fire-and-forget outbound flush — used when a single event needs to ship
     * promptly (e.g. a quiz answer just landed). Avoids the cost of the full
     * inbound bundle pull that [triggerNow] does.
     *
     * Uses [ExistingWorkPolicy.APPEND_OR_REPLACE] so back-to-back calls
     * coalesce: the second answer's flush waits behind the first instead of
     * cancelling it.
     */
    fun triggerOutboundNow() {
        val request = OneTimeWorkRequestBuilder<OutboundSyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            OutboundSyncWorker.WORK_NAME + "_flush",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        Log.d(TAG, "Outbound flush enqueued.")
    }

    /** Cancels all pending sync workers. Useful for testing or explicit SDK reset. */
    fun cancelAll() {
        workManager.cancelUniqueWork(OutboundSyncWorker.WORK_NAME)
        workManager.cancelUniqueWork(InboundSyncWorker.WORK_NAME)
        workManager.cancelUniqueWork(OutboundSyncWorker.WORK_NAME + "_periodic")
        workManager.cancelUniqueWork(InboundSyncWorker.WORK_NAME + "_periodic")
        // The hook-triggered flush leg (triggerOutboundNow) has its own unique
        // name — it was previously left running across SDK shutdowns.
        workManager.cancelUniqueWork(OutboundSyncWorker.WORK_NAME + "_flush")
    }

    companion object {
        private const val TAG = "SyncCoordinator"
        const val PERIODIC_INTERVAL_MINUTES = 15L
        const val BACKOFF_DELAY_SECONDS = 30L
    }
}
