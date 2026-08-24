package com.medtroniclabs.microcoaching.sync

import android.util.Log
import androidx.room.withTransaction
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.AssignedModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChatFaqEntity
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
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

/**
 * Domain-level sync gateway used by [OutboundSyncWorker] and [InboundSyncWorker].
 *
 * Holds the state every pull and push shares: the API client, the database and
 * the batch identity. The pulls and pushes themselves are extension functions on
 * this class, grouped by domain in sibling files.
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

    /**
     * The identical success/HTTP-error/`IOException`/`Exception` envelope that every
     * single-response inbound pull repeated verbatim. [call] performs any watermark
     * pre-work and issues the request; [onSuccess] maps a successful body to a result;
     * [onFailure] builds the endpoint's result from an error string + kind.
     *
     * [label] prefixes the "`<label> sync {server|network|unexpected} error`"
     * logcat lines. Not used by the paginated/outbound paths, whose shape differs.
     */
    internal suspend fun <T, R : SyncResult> safeInbound(
        label: String,
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
                Log.w(TAG, "$label sync server error: $errorMsg")
                onFailure(errorMsg, httpKindFor(response.code()))
            }
        } catch (e: IOException) {
            Log.w(TAG, "$label sync network error: ${e.message}")
            onFailure(e.message ?: "network error", SyncErrorKind.NETWORK)
        } catch (e: Exception) {
            Log.w(TAG, "$label sync unexpected error: ${e.message}", e)
            onFailure(e.message ?: "unexpected error", SyncErrorKind.UNEXPECTED)
        }
    }

    companion object {
        private const val TAG = "SyncApi"
    }
}
