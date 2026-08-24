package com.medtroniclabs.microcoaching.network

import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves a rich-card media reference (image / video node) to a loadable URL.
 *
 * Media nodes arrive in one of two shapes:
 *  - a direct `url` (used as-is), or
 *  - an `object_name` (e.g. `media/<uuid>_<file>.png`) exchanged for a short-lived
 *    presigned GET URL via [CoachingApiService.getPresignedUrls].
 *
 * That endpoint expects a bucket-prefixed storage path while a card carries only
 * the bucket-relative object name, so these currently come back declined. It is
 * still the right route to be on: the admin-gated alternative 403s for every SK
 * and PO, which is what broke card images in the field.
 *
 * Presigned URLs are cached in-memory by object name until shortly before they
 * expire so repeated or revisited cards don't re-hit the backend each frame. The
 * cache is process-scoped and deliberately not persisted, since the URLs are
 * ephemeral; pass `forceFresh` to bypass it when a download has already failed.
 */
object MediaUrlResolver {

    private const val TAG = "MediaUrlResolver"

    /** Resolve a few seconds early so a URL never expires mid-load. */
    private const val EXPIRY_SAFETY_MARGIN_SEC = 10L

    private data class CachedUrl(val url: String, val expiresAtEpochSec: Long)

    private val cache = mutableMapOf<String, CachedUrl>()
    private val mutex = Mutex()

    /**
     * Return a loadable URL for the media node, or null when it can't be resolved
     * (no source, no network, or backend miss). Safe to call from the main thread —
     * the network round-trip is suspending.
     */
    suspend fun resolve(src: String?, objectName: String?, forceFresh: Boolean = false): String? {
        if (!src.isNullOrBlank()) {
            Log.d(TAG, "resolve: using direct src=$src")
            return src
        }
        val key = objectName?.takeIf { it.isNotBlank() }
        if (key == null) {
            Log.w(TAG, "resolve: no src and no object_name — nothing to resolve")
            return null
        }

        if (!forceFresh) {
            mutex.withLock { cache[key] }
                ?.takeIf { it.expiresAtEpochSec > nowEpochSec() }
                ?.let {
                    Log.d(TAG, "resolve: cache hit object_name=$key → ${it.url}")
                    return it.url
                }
        }

        val sdk = runCatching { MicroCoachingSDK.getInstance() }.getOrNull()
        if (sdk == null) {
            Log.w(TAG, "SDK not initialised — cannot resolve media object_name=$key")
            return null
        }
        if (!sdk.isNetworkAvailable()) {
            Log.w(TAG, "resolve: network unavailable — cannot resolve object_name=$key")
            return null
        }

        val resolved = runCatching {
            val response = sdk.apiService.getPresignedUrls(StoragePathsPresignRequest(listOf(key)))
            if (!response.isSuccessful) {
                Log.w(
                    TAG,
                    "Media presign failed: code=${response.code()} body=${response.errorBody()?.string()}",
                )
                return@runCatching null
            }
            val body = response.body()
            val entry = body?.urls?.firstOrNull { it.storagePath == key }
            if (entry == null || entry.presignedUrl.isBlank()) {
                // A declined path, not a transport fault. Card media carries a
                // bucket-relative `object_name` while the endpoint wants a
                // bucket-prefixed storage path, so these land here until the
                // backend either accepts one or ships the other.
                Log.w(TAG, "Media presign declined object_name=$key (missing_paths=${body?.missingPaths})")
                return@runCatching null
            }
            CachedUrl(
                url = entry.presignedUrl,
                expiresAtEpochSec = nowEpochSec() + (entry.expiresSeconds - EXPIRY_SAFETY_MARGIN_SEC).coerceAtLeast(0),
            )
        }.onFailure { Log.w(TAG, "Media presign error for $key: ${it.message}", it) }.getOrNull()
            ?: return null

        mutex.withLock { cache[key] = resolved }
        Log.d(TAG, "resolve: resolved object_name=$key")
        return resolved.url
    }

    /**
     * Resolve a source-document id (an assigned video's canonical id) to its
     * presigned media URL, read from what the last sync stored — see
     * [SourceDocumentUrlStore]. Unlike [resolve] this needs no network, so a
     * previously-synced video still plays offline.
     *
     * Returns null when no unexpired URL is cached, which the callers surface as
     * unavailable-media. The in-memory entry is kept in a namespace distinct from
     * the `object_name` entries above, and spares repeat playbacks a Room read
     * rather than a request.
     */
    suspend fun resolveSourceDocument(sourceDocumentId: String?): String? {
        val id = sourceDocumentId?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "resolveSourceDocument: blank id — nothing to resolve")
            return null
        }
        val key = "srcdoc:$id"

        mutex.withLock { cache[key] }
            ?.takeIf { it.expiresAtEpochSec > nowEpochSec() }
            ?.let {
                Log.d(TAG, "resolveSourceDocument: cache hit id=$id → ${it.url}")
                return it.url
            }

        val ref = SourceDocumentUrlStore.resolve(id) ?: return null
        // Shave the same safety margin off the stored expiry as the on-demand path
        // does, so we stop serving a URL slightly before the signature lapses.
        mutex.withLock {
            cache[key] = CachedUrl(
                url = ref.url,
                expiresAtEpochSec = (ref.expiresAtEpochSec - EXPIRY_SAFETY_MARGIN_SEC).coerceAtLeast(0),
            )
        }
        Log.d(TAG, "resolveSourceDocument: resolved id=$id from sync cache")
        return ref.url
    }

    private fun nowEpochSec(): Long = System.currentTimeMillis() / 1000L
}
