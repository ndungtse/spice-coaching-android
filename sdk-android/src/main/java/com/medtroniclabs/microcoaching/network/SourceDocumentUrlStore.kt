package com.medtroniclabs.microcoaching.network

import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingSDK

/**
 * Looks up the presigned URL for a source document among the rows the last sync
 * wrote, so opening a document costs no network call and keeps working offline
 * for as long as the stored URL is valid.
 *
 * Two tables can hold it: `published_source_document` for anything in the
 * Knowledge grid, and `assigned_video` for the CHW's assigned media. A document
 * can be in both, so the published copy is preferred simply because it is
 * refreshed on every sync regardless of who is signed in. A document in neither —
 * cited by chat but linked to no published module and assigned to nobody —
 * resolves to null here; its URL arrives on the citation itself instead.
 *
 * A URL past its expiry is reported as absent rather than returned: handing back
 * a stale signature would surface as an opaque storage error instead of the
 * "not downloaded yet" state the callers already handle. [renew] re-signs from a
 * storage path without waiting for the next sync.
 */
internal object SourceDocumentUrlStore {

    private const val TAG = "SourceDocUrlStore"

    /** A stored URL together with the absolute epoch second it stops working. */
    data class PresignedRef(val url: String, val expiresAtEpochSec: Long)

    /** The freshest usable URL for [sourceDocumentId], or null if none is valid. */
    suspend fun resolve(sourceDocumentId: String?): PresignedRef? {
        val id = sourceDocumentId?.takeIf { it.isNotBlank() } ?: return null
        val sdk = runCatching { MicroCoachingSDK.getInstance() }.getOrNull()
        if (sdk == null) {
            Log.w(TAG, "SDK not initialised — cannot resolve source-document id=$id")
            return null
        }
        val nowSec = System.currentTimeMillis() / 1000L

        val published = runCatching {
            sdk.database.publishedSourceDocumentDao().getById(id)
        }.getOrNull()
        fresh(published?.presignedUrl, published?.presignedExpiresAt, nowSec)?.let { return it }

        // Assigned rows are per-CHW, so this half only resolves for the signed-in
        // user — which is also the only user who can have been assigned the media.
        val chwId = sdk.currentCHWId
        if (!chwId.isNullOrBlank()) {
            val video = runCatching {
                sdk.database.assignedVideoDao().getById(id, chwId)
            }.getOrNull()
            fresh(video?.presignedUrl, video?.presignedExpiresAt, nowSec)?.let { return it }
        }

        Log.d(TAG, "No fresh presigned URL cached for $id — next sync will refresh it.")
        return null
    }

    /** Convenience for callers that only need the URL. */
    suspend fun presignedUrlFor(sourceDocumentId: String?): String? = resolve(sourceDocumentId)?.url

    /**
     * Re-sign this document from a storage path, ignoring whatever is cached.
     *
     * This is the escape hatch for a URL that has lapsed: the stored expiry can
     * still look fresh while object storage has already stopped honouring the
     * signature, and a URL carried on a chat citation goes stale within the hour.
     *
     * The path comes from the document's catalogue row, falling back to
     * [fallbackStoragePath] for a document in neither catalogue — the caller's own
     * copy of the path, which is all a chat-only citation has. Returns null when
     * neither yields one.
     */
    suspend fun renew(sourceDocumentId: String?, fallbackStoragePath: String? = null): String? {
        val id = sourceDocumentId?.takeIf { it.isNotBlank() } ?: return null
        val sdk = runCatching { MicroCoachingSDK.getInstance() }.getOrNull() ?: return null

        val storagePath = runCatching {
            sdk.database.publishedSourceDocumentDao().getById(id)?.storagePath
                ?: sdk.currentCHWId?.takeIf { it.isNotBlank() }?.let { chwId ->
                    sdk.database.assignedVideoDao().getById(id, chwId)?.storagePath
                }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: fallbackStoragePath?.takeIf { it.isNotBlank() }
        if (storagePath == null) {
            Log.d(TAG, "No storage path known for $id — cannot re-sign.")
            return null
        }

        return runCatching {
            val response = sdk.apiService.getPresignedUrls(StoragePathsPresignRequest(listOf(storagePath)))
            if (!response.isSuccessful) {
                Log.w(TAG, "Re-sign failed for $id: HTTP ${response.code()}")
                return@runCatching null
            }
            val body = response.body()
            val entry = body?.urls?.firstOrNull { it.storagePath == storagePath }
            if (entry == null || entry.presignedUrl.isBlank()) {
                Log.w(TAG, "Re-sign declined for $id (missing_paths=${body?.missingPaths})")
                return@runCatching null
            }
            entry.presignedUrl
        }.onFailure { Log.w(TAG, "Re-sign error for $id: ${it.message}", it) }.getOrNull()
    }

    private fun fresh(url: String?, expiresAt: Long?, nowSec: Long): PresignedRef? {
        if (url.isNullOrBlank() || expiresAt == null || expiresAt <= nowSec) return null
        return PresignedRef(url, expiresAt)
    }
}
