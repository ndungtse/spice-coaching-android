package com.medtroniclabs.microcoaching.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.ui.chat.ChatFaqRepository
import kotlinx.coroutines.sync.withLock

/**
 * WorkManager worker that pulls every inbound resource — modules, source
 * documents, gaps, triggers, config, chat FAQs and morning cards.
 *
 * Each pull is independent and non-fatal, so one failing endpoint doesn't block
 * the rest; each reports its own verdict so a UI section can scope its error to
 * the table it reads. Watermark-driven pulls advance their cursor in [SyncPrefs]
 * only on success, so a failure re-fetches from the same point.
 *
 * Scheduled by [SyncCoordinator]:
 *   - Periodic: every 15 minutes when network is available
 *   - One-shot: immediately on connectivity restore (after outbound sync completes)
 */
class InboundSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!MicroCoachingSDK.isInitialized()) {
            Log.w(TAG, "SDK not initialized — retrying inbound sync later.")
            return Result.retry()
        }

        val sdk = MicroCoachingSDK.getInstance()
        val config = sdk.config

        if (config.backendUrl.isBlank()) {
            Log.d(TAG, "backendUrl not configured — skipping inbound sync.")
            return Result.success()
        }

        val db = MicroCoachingDatabase.getInstance(applicationContext)
        val syncPrefs = SyncPrefs(applicationContext)
        // chwId scopes the per-CHW writes these pulls make; the backend derives
        // the CHW it returns data for from the auth token, not from us.
        // apiService is the SDK's cached instance — a per-run client would add a
        // thread pool and connection pool per 15-minute tick.
        val syncApi = SyncApi(
            apiService = sdk.apiService,
            db = db,
            sessionId = "inbound-sync",
            chwId = sdk.currentCHWId.orEmpty(),
            syncPrefs = syncPrefs,
        )

        // Single-flight: the periodic and chained inbound work names don't dedupe
        // against each other — serialize so two workers can't each deserialize the
        // full catalogue (and race the watermark writes) at once. See [SyncGate].
        return SyncGate.inbound.withLock { runInboundSync(sdk, syncApi, syncPrefs) }
    }

    private suspend fun runInboundSync(
        sdk: MicroCoachingSDK,
        syncApi: SyncApi,
        syncPrefs: SyncPrefs,
    ): Result {
        val config = sdk.config
        val db = MicroCoachingDatabase.getInstance(applicationContext)

        // Each pull is non-fatal so one 404/500 doesn't block the others. The
        // worker returns retry only if every core pull failed and every failure
        // was a transient network error — see shouldRetryInbound below.
        // Periodically force a full-catalogue fetch so terminally-retired modules
        // get reconciled out of the cache — an incremental delta can never carry a
        // retirement (the family just stops appearing). Bounded to once per
        // interval so steady-state syncs stay incremental / low-bandwidth.
        val now = System.currentTimeMillis()
        val startedAt = now

        // Each pull's verdict, published once at the end so UI sections can scope their
        // error state to the table they read (see SyncStatusStore).
        val outcomes = LinkedHashMap<SyncDomain, SyncOutcome>()
        fun record(domain: SyncDomain, result: SyncResult) {
            if (!result.success) {
                Log.w(TAG, "$domain sync failed (non-fatal): ${result.error}")
            }
            outcomes[domain] = result.toOutcome(System.currentTimeMillis())
        }

        val reconcileDue = now - syncPrefs.lastModulesReconcileAt >= MODULES_RECONCILE_INTERVAL_MS
        val modulesResult = syncApi.pullModules(
            syncPrefs.modulesWatermark,
            forceFullCatalogue = reconcileDue,
        )
        if (modulesResult.success) {
            modulesResult.newWatermark?.let { syncPrefs.modulesWatermark = it }
            if (reconcileDue) syncPrefs.lastModulesReconcileAt = now
            if (modulesResult.prunedCount > 0) {
                Log.i(TAG, "Modules reconcile pruned ${modulesResult.prunedCount} stale row(s).")
            }
        }
        record(SyncDomain.MODULES, modulesResult)

        // Source-document catalogue — one call backing both the Knowledge section
        // and the Training sub-tab, so it reports into two domains. Non-fatal and
        // not part of the retry predicate: on failure the previous contents stay
        // intact so both still render offline. The video half is skipped
        // automatically when no CHW is signed in.
        val sourceDocsResult = syncApi.pullSourceDocuments()
        record(SyncDomain.PUBLISHED_DOCS, sourceDocsResult.published)
        record(SyncDomain.ASSIGNED_VIDEOS, sourceDocsResult.assignedVideos)

        val gapsResult = syncApi.pullGaps(syncPrefs.gapsWatermark)
        if (gapsResult.success) {
            gapsResult.newWatermark?.let { syncPrefs.gapsWatermark = it }
            // Partial-completion rows feed the to-reinforce set used by the
            // morning-cards filter — re-apply it so a fresh-device CHW sees
            // the right modules surface before they answer anything locally.
            if (gapsResult.partialUpserted > 0) {
                sdk.currentCHWId?.let { chwId -> sdk.refilterMorningModules(chwId) }
            }
        }
        record(SyncDomain.GAPS, gapsResult)

        val triggersResult = syncApi.pullTriggers(syncPrefs.triggersWatermark)
        if (triggersResult.success) {
            triggersResult.newWatermark?.let { syncPrefs.triggersWatermark = it }
        }
        record(SyncDomain.TRIGGERS, triggersResult)

        val configResult = syncApi.pullConfig()
        if (configResult.success) {
            configResult.newWatermark?.let { syncPrefs.configWatermark = it }
        }
        record(SyncDomain.CONFIG, configResult)

        // Chat FAQ suggestions — non-fatal and not in the retry predicate; on
        // failure the previous cache stays intact so suggestions still render.
        val chatFaqsResult = syncApi.pullChatFaqs(syncPrefs.chatFaqsWatermark)
        if (chatFaqsResult.success) {
            chatFaqsResult.newWatermark?.let { syncPrefs.chatFaqsWatermark = it }
            // Backfill English on-device now that the (possibly new) FAQs are
            // cached: ensures the pack (downloading if needed) then translates
            // bn→en. Non-fatal — a passthrough or failure is retried on chat open.
            runCatching { ChatFaqRepository(db.chatFaqDao()).translatePending(sdk.translator) }
                .onFailure { Log.w(TAG, "Chat FAQ translation failed (non-fatal): ${it.message}") }
        }
        record(SyncDomain.CHAT_FAQS, chatFaqsResult)

        val pulls = listOf(modulesResult, gapsResult, triggersResult, configResult)
        if (shouldRetryInbound(pulls)) {
            Log.w(TAG, "All core sync pulls failed with transient network errors — retrying.")
            sdk.syncStatus.publishRun(
                InboundRunSummary(startedAt, System.currentTimeMillis(), outcomes),
            )
            return Result.retry()
        }
        val anyPermanentFailure = pulls.any { !it.success && it.errorKind != SyncErrorKind.NETWORK }
        if (anyPermanentFailure) {
            // Permanent failures (HTTP 4xx, deserialization) won't fix themselves on
            // backoff retries — let the next periodic worker run try again instead of
            // burning battery here.
            Log.w(TAG, "Some core sync pulls failed permanently — deferring to next scheduled run.")
        }

        // Morning cards — non-fatal; on failure the previous cache stays intact.
        val morningResult = syncApi.pullMorningCards()
        record(SyncDomain.MORNING_CARDS, morningResult)

        val finishedAt = System.currentTimeMillis()
        val summary = InboundRunSummary(startedAt, finishedAt, outcomes)
        // "Last synced" must mean "everything landed". Advancing it after a partial failure
        // is what made a failed pull-to-refresh look successful.
        if (summary.allSucceeded) syncPrefs.lastInboundSyncAt = finishedAt
        sdk.syncStatus.publishRun(summary)
        Log.i(
            TAG,
            "Inbound sync complete. modules=${modulesResult.upsertedCount}, " +
                "assigned=${modulesResult.assignedCount}, " +
                "publishedDocs=${sourceDocsResult.published.count}, " +
                "assignedVideos=${sourceDocsResult.assignedVideos.count}, " +
                "gaps=${gapsResult.upsertedCount}, " +
                "triggers=${triggersResult.triggerCount} bindings=${triggersResult.bindingCount}, " +
                "config=${configResult.upsertedCount}, chatFaqs=${chatFaqsResult.upsertedCount}, " +
                "morningCards=${morningResult.count}.",
        )
        return Result.success()
    }

    /**
     * Retry only when every pull failed AND every failure was a transient network
     * error (IOException family). A mix of HTTP 4xx, HTTP 5xx, deserialization
     * crashes, etc. is treated as permanent for retry purposes — these don't
     * resolve under exponential backoff, and burning battery against a
     * misconfigured backend is worse than waiting for the next 15-minute tick.
     */
    private fun shouldRetryInbound(pulls: List<SyncResult>): Boolean =
        pulls.isNotEmpty() && pulls.all { !it.success && it.errorKind == SyncErrorKind.NETWORK }

    companion object {
        const val TAG = "InboundSyncWorker"
        const val WORK_NAME = "micro_coaching_inbound_sync"

        /**
         * How often to force a full-catalogue modules fetch for retirement
         * reconcile. The worker itself ticks every ~15 min; this throttles the
         * heavier full pull to once a day so a retired module disappears within a
         * day without paying full-bundle bandwidth on every tick.
         */
        const val MODULES_RECONCILE_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
