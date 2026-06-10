package com.medtroniclabs.microcoaching.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.domain.telemetry.sha256Short
import com.medtroniclabs.microcoaching.network.NetworkModule

/**
 * WorkManager worker that pushes pending SDK events to the Knowledge Layer backend.
 *
 * Batches all `pending` rows from:
 *   - `coaching_event` (card interactions, quiz answers, session markers)
 *   - `llm_trace` (inference audit records)
 *   - `digital_proficiency_event` (sync and digital interaction signals)
 *
 * On success: marks rows `synced` in Room.
 * On server error (5xx) or network failure: returns [Result.retry()] with
 * exponential backoff (WorkManager manages the backoff schedule).
 * On client error (4xx): returns [Result.failure()] — no retry.
 *
 * Records a `sync_attempt` [DigitalProficiencyEventEntity] row via [SyncApi]
 * when outbound execution starts, enabling digital proficiency signal capture (SDK-032).
 *
 * Scheduled by [SyncCoordinator]:
 *   - Periodic: every 15 minutes when network is available
 *   - One-shot: immediately on connectivity restore
 */
class OutboundSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!MicroCoachingSDK.isInitialized()) {
            Log.w(TAG, "SDK not initialized — retrying outbound sync later.")
            return Result.retry()
        }

        val sdk = MicroCoachingSDK.getInstance()
        val config = sdk.config

        if (config.backendUrl.isBlank()) {
            Log.d(TAG, "backendUrl not configured — skipping outbound sync.")
            return Result.success()
        }
        val currentChwId = sdk.currentCHWId
        if (currentChwId.isNullOrBlank()) {
            Log.w(TAG, "currentCHWId not set — skipping outbound sync.")
            return Result.success()
        }

        val db = MicroCoachingDatabase.getInstance(applicationContext)
        val apiService = NetworkModule.createApiService(config)
        val syncApi = SyncApi(
            apiService = apiService,
            db = db,
            sessionId = "sync-${currentChwId.sha256Short()}",
            chwId = currentChwId,
        )

        val result = syncApi.pushPendingEvents()

        return when {
            result.skipped -> Result.success()
            result.success -> Result.success()
            isClientError(result.error) -> {
                Log.e(TAG, "Outbound sync client error — not retrying: ${result.error}")
                Result.failure()
            }
            else -> {
                Log.w(TAG, "Outbound sync failed — will retry: ${result.error}")
                Result.retry()
            }
        }
    }

    private fun isClientError(error: String?): Boolean {
        if (error == null) return false
        return error.startsWith("HTTP 4")
    }

    companion object {
        const val TAG = "OutboundSyncWorker"
        const val WORK_NAME = "micro_coaching_outbound_sync"
    }
}
