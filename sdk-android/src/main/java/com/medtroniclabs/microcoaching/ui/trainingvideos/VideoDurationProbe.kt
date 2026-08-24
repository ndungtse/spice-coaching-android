package com.medtroniclabs.microcoaching.ui.trainingvideos

import android.media.MediaMetadataRetriever
import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.network.MediaUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Works out how long a video is when the backend hasn't said.
 *
 * `duration_ms` arrives null until the backend has probed the media, which leaves
 * the Training list with no length to show on exactly the videos a CHW hasn't
 * opened yet. The information is in the file itself, so we read it.
 *
 * Two sources, cheapest first:
 *  - a copy already downloaded for offline viewing — a local read, no network;
 *  - otherwise the media's own URL. Duration lives in the MP4 `moov` atom, so this
 *    is a ranged read of that atom rather than a download of the video; object
 *    storage honours Range, which is what keeps it bounded. In practice it is
 *    comparable to the thumbnail already fetched for the same row.
 *
 * Results are written once and persisted, so a video is measured at most once ever —
 * and never at all if the backend supplies the length first. A video whose media
 * could not be reached yet stays eligible, since the usual cause is a sync that
 * hasn't stored its URL.
 *
 * [MediaMetadataRetriever] is a native component that can block or throw on an
 * unusual container, so every call is off the main thread, time-boxed, released in
 * a `finally`, and allowed to fail silently: an unknown duration simply isn't shown.
 */
internal object VideoDurationProbe {

    private const val TAG = "VideoDurationProbe"

    /**
     * A list of unmeasured videos would otherwise start every read at once. Two at a
     * time keeps a full sweep from queueing a burst of ranged reads on a field
     * connection.
     */
    private val slots = Semaphore(permits = 2)

    private val inFlightLock = Mutex()
    private val inFlight = mutableSetOf<String>()

    /**
     * Videos whose media we actually read and still couldn't measure — a container
     * the device can't parse, say. Retrying those is pointless within a session.
     *
     * A probe that never got as far as a readable source is deliberately **not**
     * recorded here: the usual reason is that sync hasn't stored a URL yet, which a
     * moment later it has. Blacklisting that case is what makes a probe look like it
     * only ever works after a restart.
     */
    private val unreadable = mutableSetOf<String>()

    /** Measure every assigned video whose length is still unknown. */
    suspend fun probeMissing(chwId: String) {
        if (chwId.isBlank()) return
        val sdk = runCatching { MicroCoachingSDK.getInstance() }.getOrNull() ?: return
        val ids = runCatching { sdk.database.assignedVideoDao().idsMissingDuration(chwId) }
            .getOrDefault(emptyList())
        if (ids.isEmpty()) return
        Log.d(TAG, "Probing ${ids.size} video(s) with unknown duration")
        ids.forEach { probeIfUnknown(it, chwId) }
    }

    /**
     * Discover and store [videoId]'s length if it isn't known yet. Safe to call
     * repeatedly — concurrent and redundant calls for the same video are dropped.
     */
    suspend fun probeIfUnknown(videoId: String, chwId: String) {
        if (videoId.isBlank() || chwId.isBlank()) return

        val shouldRun = inFlightLock.withLock {
            if (videoId in inFlight || videoId in unreadable) false
            else { inFlight += videoId; true }
        }
        if (!shouldRun) return

        var readAttempted = false
        try {
            val sdk = runCatching { MicroCoachingSDK.getInstance() }.getOrNull() ?: return
            val dao = sdk.database.assignedVideoDao()
            // Re-check against the table, not just this process: the backend or a
            // previous session may already have supplied it.
            val known = runCatching { dao.getById(videoId, chwId)?.durationMs ?: 0L }.getOrDefault(0L)
            if (known > 0L) return

            val source = resolveSource(sdk, videoId)
            if (source == null) {
                Log.d(TAG, "No readable source yet for $videoId — will retry")
                return
            }
            readAttempted = true

            val durationMs = slots.withPermit { readDurationMs(source.path, source.isLocal) }
            if (durationMs == null || durationMs <= 0L) {
                Log.d(TAG, "Could not measure $videoId (local=${source.isLocal})")
                return
            }
            Log.i(TAG, "Measured $videoId at ${durationMs}ms (local=${source.isLocal})")
            runCatching { dao.updateDurationIfUnknown(videoId, chwId, durationMs) }
                .onFailure { Log.w(TAG, "Storing duration failed for $videoId: ${it.message}") }
        } finally {
            inFlightLock.withLock {
                inFlight -= videoId
                if (readAttempted) unreadable += videoId
            }
        }
    }

    /** Where the media can be read from, and whether that costs network. */
    private data class MediaSource(val path: String, val isLocal: Boolean)

    /** A downloaded copy if there is one, else the media URL the last sync stored. */
    private suspend fun resolveSource(sdk: MicroCoachingSDK, videoId: String): MediaSource? {
        runCatching { sdk.assetCache.localCachedFile(videoId) }.getOrNull()
            ?.let { return MediaSource(it.absolutePath, isLocal = true) }

        if (!sdk.isNetworkAvailable()) return null
        // Reuses whatever the last sync stored, so this adds a request for the media
        // itself but never one to resolve its address.
        val url = runCatching { MediaUrlResolver.resolveSourceDocument(videoId) }.getOrNull()
        return url?.takeIf { it.isNotBlank() }?.let { MediaSource(it, isLocal = false) }
    }

    private suspend fun readDurationMs(source: String, isLocal: Boolean): Long? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(if (isLocal) LOCAL_TIMEOUT_MS else REMOTE_TIMEOUT_MS) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (isLocal) retriever.setDataSource(source) else retriever.setDataSource(source, emptyMap())
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.takeIf { it > 0L }
                } catch (e: Exception) {
                    // Unreadable container, a lapsed URL, a server that won't range —
                    // all end the same way: the length stays unknown and isn't shown.
                    Log.d(TAG, "Metadata read failed (local=$isLocal): ${e.message}")
                    null
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }

    /** A local read is a file seek; anything slower means something is wrong. */
    private const val LOCAL_TIMEOUT_MS = 3_000L

    /** Bounded so a stalled range request can't hold a slot for the whole session. */
    private const val REMOTE_TIMEOUT_MS = 12_000L

    /** Test seam — clears the per-process dedup memory. */
    internal suspend fun resetForTest() = inFlightLock.withLock {
        inFlight.clear()
        unreadable.clear()
    }
}
