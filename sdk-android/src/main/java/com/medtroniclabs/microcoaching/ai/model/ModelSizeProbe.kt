package com.medtroniclabs.microcoaching.ai.model

import android.content.Context
import android.util.Log
import com.medtroniclabs.microcoaching.util.PrefsNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves the real download size of a [ModelVariant] from its host, so the setup
 * screen states the size the user is about to spend rather than the catalog's
 * hand-maintained [ModelVariant.sizeInBytes]. The download progress row already
 * counts against the server's `Content-Length`, so a stale constant makes the
 * card contradict the bar beneath it.
 *
 * Resolution order per variant:
 *  1. Cached value — from an earlier probe, or from the `Content-Length` actually
 *     served during a download ([recordObservedSize]), which supersedes it.
 *  2. `HEAD` on the download URL. `x-linked-size` wins over `Content-Length`
 *     because on an LFS-backed path the latter can describe the pointer file.
 *  3. A one-byte ranged `GET`, reading the total out of `Content-Range`. Covers
 *     hosts that don't answer HEAD.
 *
 * Every failure returns null and the caller falls back to the catalog constant. This is
 * informational: it must never block the setup screen or prevent a download from starting,
 * and nothing here is used to reject a downloaded file — see the ordering in
 * [com.medtroniclabs.microcoaching.ai.download.ResumableHttpDownloader.download].
 */
internal object ModelSizeProbe {

    private const val TAG = "ModelSizeProbe"
    private const val KEY_PREFIX = "resolved_size_bytes_"

    /** Sanity floor — a "size" below this is a pointer file or an error page, not a model. */
    private const val MIN_PLAUSIBLE_BYTES = 1_000_000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Last size resolved for [variant], or null if we've never successfully probed it. */
    fun cachedSize(context: Context, variant: ModelVariant): Long? =
        prefs(context).getLong(KEY_PREFIX + variant.id, 0L).takeIf { it > 0L }

    /**
     * Record the `Content-Length` served for [variant] during a download. It outranks a
     * `HEAD` probe and the catalog constant, being the length of the bytes actually
     * arriving, so caching it lets the displayed size follow a republished model.
     *
     * Ignores implausibly small values, which are error bodies rather than models.
     */
    fun recordObservedSize(context: Context, variant: ModelVariant, totalBytes: Long) {
        if (totalBytes < MIN_PLAUSIBLE_BYTES) return
        if (cachedSize(context, variant) == totalBytes) return
        prefs(context).edit().putLong(KEY_PREFIX + variant.id, totalBytes).apply()
        Log.i(TAG, "Observed size for '${variant.id}': $totalBytes bytes (was ${variant.sizeInBytes} in the catalog)")
    }

    /**
     * The "this download is complete" floor for [variant], preferring the probed
     * size over the catalog's approximation. Single definition so the download
     * worker (which deletes a file landing under the floor) and [ModelManager]
     * (which decides whether a present file is usable) can't disagree — that
     * divergence is a download/wipe loop.
     */
    fun minValidSizeBytes(context: Context, variant: ModelVariant): Long {
        val resolved = cachedSize(context, variant) ?: return ModelCatalog.minValidSizeBytes(variant)
        return (resolved * ModelCatalog.SIZE_FLOOR_FRACTION).toLong()
    }

    /**
     * Real size for [variant], hitting the network only on a cache miss — safe to
     * call on every setup-screen entry, since after the first success it is a
     * preference read.
     *
     * @param hfToken access token for gated repos; ignored when blank.
     */
    suspend fun resolveSize(
        context: Context,
        variant: ModelVariant,
        hfToken: String = "",
    ): Long? {
        cachedSize(context, variant)?.let { return it }

        val resolved = withContext(Dispatchers.IO) { probe(variant, hfToken) }
        if (resolved == null) {
            Log.i(TAG, "Could not resolve size for '${variant.id}' — using catalog value")
            return null
        }
        prefs(context).edit().putLong(KEY_PREFIX + variant.id, resolved).apply()
        Log.i(
            TAG,
            "Resolved size for '${variant.id}': $resolved bytes " +
                "(catalog says ${variant.sizeInBytes})",
        )
        return resolved
    }

    private fun probe(variant: ModelVariant, hfToken: String): Long? =
        runCatching { headSize(variant, hfToken) }.getOrNull()
            ?: runCatching { rangedSize(variant, hfToken) }.getOrNull()

    private fun headSize(variant: ModelVariant, hfToken: String): Long? {
        val request = requestBuilder(variant, hfToken).head().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.d(TAG, "HEAD ${variant.id} → HTTP ${response.code}")
                return null
            }
            val linked = response.header("x-linked-size")?.toLongOrNull()
            val contentLength = response.header("Content-Length")?.toLongOrNull()
            return (linked ?: contentLength)?.takeIf { it >= MIN_PLAUSIBLE_BYTES }
        }
    }

    private fun rangedSize(variant: ModelVariant, hfToken: String): Long? {
        val request = requestBuilder(variant, hfToken)
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.d(TAG, "Ranged GET ${variant.id} → HTTP ${response.code}")
                return null
            }
            // "bytes 0-0/319000000" — the total is what we're after.
            val total = response.header("Content-Range")
                ?.substringAfterLast('/', "")
                ?.toLongOrNull()
            return total?.takeIf { it >= MIN_PLAUSIBLE_BYTES }
        }
    }

    private fun requestBuilder(variant: ModelVariant, hfToken: String): Request.Builder =
        Request.Builder().url(variant.downloadUrl).apply {
            if (hfToken.isNotBlank()) header("Authorization", "Bearer $hfToken")
        }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PrefsNames.MODEL, Context.MODE_PRIVATE)
}
