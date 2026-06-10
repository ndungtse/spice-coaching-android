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
 *  - an `object_name` (e.g. `media/<uuid>_<file>.png`) that must be exchanged for a
 *    short-lived presigned GET URL via [CoachingApiService.getMediaPresignedUrls].
 *
 * Presigned URLs are cached in-memory by object name until shortly before they
 * expire so a card with the same media repeated (or revisited cards) doesn't
 * re-hit the backend each frame. The cache is process-scoped and intentionally
 * not persisted — presigned URLs are deliberately ephemeral.
 *
 * Mirrors the presigned flow already used by
 * [com.medtroniclabs.microcoaching.ui.document.DocumentPreviewActivity].
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
    suspend fun resolve(src: String?, objectName: String?): String? {
        if (!src.isNullOrBlank()) {
            Log.d(TAG, "resolve: using direct src=$src")
            return src
        }
        val key = objectName?.takeIf { it.isNotBlank() }
        if (key == null) {
            Log.w(TAG, "resolve: no src and no object_name — nothing to resolve")
            return null
        }

        mutex.withLock { cache[key] }
            ?.takeIf { it.expiresAtEpochSec > nowEpochSec() }
            ?.let {
                Log.d(TAG, "resolve: cache hit object_name=$key → ${it.url}")
                return it.url
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

        Log.d(TAG, "resolve: requesting presigned URL → GET admin/v3/files/presigned-url?object_name=$key")
        val resolved = runCatching {
            val response = sdk.apiService.getMediaPresignedUrl(objectName = key)
            if (!response.isSuccessful) {
                Log.w(
                    TAG,
                    "Media presigned fetch failed: code=${response.code()} " +
                        "body=${response.errorBody()?.string()}",
                )
                return@runCatching null
            }
            val entry = response.body()
            Log.d(
                TAG,
                "resolve: presigned response code=${response.code()} url=${entry?.url} " +
                    "bucket=${entry?.bucketName} expires=${entry?.expiresSeconds}",
            )
            if (entry == null || entry.url.isBlank()) {
                Log.w(TAG, "Media presigned URL missing in response: $key")
                return@runCatching null
            }
            CachedUrl(
                url = entry.url,
                expiresAtEpochSec = nowEpochSec() + (entry.expiresSeconds - EXPIRY_SAFETY_MARGIN_SEC).coerceAtLeast(0),
            )
        }.onFailure { Log.w(TAG, "Media presigned fetch error for $key: ${it.message}", it) }.getOrNull()
            ?: return null

        mutex.withLock { cache[key] = resolved }
        Log.d(TAG, "resolve: resolved object_name=$key → ${resolved.url}")
        return resolved.url
    }

    private fun nowEpochSec(): Long = System.currentTimeMillis() / 1000L
}
