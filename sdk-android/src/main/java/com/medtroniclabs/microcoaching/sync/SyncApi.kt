package com.medtroniclabs.microcoaching.sync

import android.util.Log
import androidx.room.withTransaction
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.AssignedModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChatFaqEntity
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.DigitalProficiencyEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.LlmTraceEntity
import com.medtroniclabs.microcoaching.data.db.entity.decodeSourceDocumentRefs
import com.medtroniclabs.microcoaching.data.mapper.parseIsoMillis
import com.medtroniclabs.microcoaching.data.mapper.toConfigEntities
import com.medtroniclabs.microcoaching.data.mapper.toEntity
import com.medtroniclabs.microcoaching.data.mapper.toPayload
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.network.CoachingApiService
import com.medtroniclabs.microcoaching.data.db.entity.SourceDocumentThumbnailEntity
import com.medtroniclabs.microcoaching.data.localized.toJsonString
import com.medtroniclabs.microcoaching.network.SyncDefaults
import com.medtroniclabs.microcoaching.network.TelemetryBatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.util.UUID

/**
 * Domain-level sync gateway used by [OutboundSyncWorker] and [InboundSyncWorker].
 *
 * Holds the state every pull and push shares: the API client, the database, and
 * the `sync_attempt` bookkeeping. The pulls and pushes themselves are extension
 * functions on this class, grouped by domain in sibling files.
 *
 * Wire↔entity mapping lives in [com.medtroniclabs.microcoaching.data.mapper].
 *
 * All functions are suspend — call from an IO dispatcher.
 */
class SyncApi(
    internal val apiService: CoachingApiService,
    internal val db: MicroCoachingDatabase,
    internal val sessionId: String,
    internal val chwId: String,
    internal val tenantId: String? = null,
    internal val sdkVersion: String = BuildConfig.SDK_VERSION,
    internal val syncPrefs: SyncPrefs? = null,
) {

    /** Running count of telemetry inserts that themselves failed (see [recordSyncAttempt]). */
    private var failedSyncAttemptInserts: Int = 0


    // ── Private helpers ───────────────────────────────────────────────────────

    internal suspend fun recordSyncAttempt(
        success: Boolean,
        errorType: String? = null,
        networkState: String? = null,
    ) {
        try {
            db.digitalProficiencyEventDao().insert(
                DigitalProficiencyEventEntity(
                    id = UUID.randomUUID().toString(),
                    sdkVersion = sdkVersion,
                    sessionId = sessionId,
                    chwId = chwId,
                    eventType = "sync_attempt",
                    success = success,
                    errorType = errorType,
                    networkState = networkState,
                )
            )
        } catch (e: Exception) {
            // A telemetry insert failing is itself a field-observability signal —
            // count it rather than swallowing it entirely.
            failedSyncAttemptInserts++
            Log.w(TAG, "Failed to record sync_attempt event (#$failedSyncAttemptInserts): ${e.message}")
        }
    }

    /**
     * Record a failed *inbound* pull as a `sync_attempt` so inbound failures are
     * observable in the field, not just a `Log.w`. [stage] (e.g.
     * `inbound_modules`) is folded into `error_type` so the failing pull is
     * identifiable downstream.
     *
     * Inbound **successes** are intentionally NOT recorded: every inbound pull
     * runs on each sync cycle, so success rows would dominate `sync_attempt`
     * volume and skew the CHW digital-proficiency success-rate, which is meant to
     * reflect CHW-driven outbound sync. Only the failure signal is needed.
     */
    internal suspend fun recordInboundFailure(stage: String, error: String?, offline: Boolean = false) {
        recordSyncAttempt(
            success = false,
            errorType = listOfNotNull(stage, error).joinToString(": "),
            networkState = if (offline) "offline" else "online",
        )
    }

    /**
     * The identical success/HTTP-error/`IOException`/`Exception` envelope that every
     * single-response inbound pull repeated verbatim. [call] performs any watermark
     * pre-work and issues the request; [onSuccess] maps a successful body to a result;
     * [onFailure] builds the endpoint's result from an error string + kind.
     *
     * Behaviour is byte-for-byte identical to the hand-written envelopes: [label] reproduces
     * the "`<label> sync {server|network|unexpected} error`" logcat lines, and [failureStage]
     * (null for endpoints that never recorded one, e.g. morning cards) reproduces the
     * [recordInboundFailure] call. Not used by the paginated/outbound paths, whose shape differs.
     */
    internal suspend fun <T, R : SyncResult> safeInbound(
        label: String,
        failureStage: String?,
        call: suspend () -> retrofit2.Response<T>,
        onSuccess: suspend (T) -> R,
        onFailure: (error: String, kind: SyncErrorKind) -> R,
    ): R {
        return try {
            val response = call()
            if (response.isSuccessful) {
                onSuccess(response.body()!!)
            } else {
                val errorMsg = "HTTP ${response.code()}"
                failureStage?.let { recordInboundFailure(it, errorMsg) }
                Log.w(TAG, "$label sync server error: $errorMsg")
                onFailure(errorMsg, httpKindFor(response.code()))
            }
        } catch (e: IOException) {
            failureStage?.let { recordInboundFailure(it, e.javaClass.simpleName, offline = true) }
            Log.w(TAG, "$label sync network error: ${e.message}")
            onFailure(e.message ?: "network error", SyncErrorKind.NETWORK)
        } catch (e: Exception) {
            failureStage?.let { recordInboundFailure(it, e.javaClass.simpleName) }
            Log.w(TAG, "$label sync unexpected error: ${e.message}", e)
            onFailure(e.message ?: "unexpected error", SyncErrorKind.UNEXPECTED)
        }
    }

    companion object {
        private const val TAG = "SyncApi"

        // Outbound/asset batch + retention + pagination constants moved to the collaborator
        // files that use them (OutboundSyncApi / AssetSyncApi) as file-private copies.

        /** Expire a few seconds early so a thumbnail URL never lapses mid-load. */
    }
}
