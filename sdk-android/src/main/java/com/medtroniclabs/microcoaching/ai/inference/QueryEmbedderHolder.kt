package com.medtroniclabs.microcoaching.ai.inference

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the query embedder, and resolves it again whenever the one held is not usable.
 *
 * The encoder arrives and leaves while the app runs: a smarter-search download can finish
 * mid-session, and an opt-out can delete the files. Re-resolving on demand is what lets the next
 * chat turn use what is on disk now. Releasing the old one frees its mapped weights.
 *
 * @param resolve finds the embedder for the files currently on disk, or null.
 */
internal class QueryEmbedderHolder(private val resolve: () -> QueryEmbedder?) {

    private val mutex = Mutex()
    private var held: QueryEmbedder? = null

    /** The usable embedder, or null when none can run right now. */
    suspend fun get(): QueryEmbedder? = mutex.withLock {
        held?.let { current ->
            if (current.isAvailable) return@withLock current
            current.release()
            held = null
        }
        resolve()?.takeIf { it.isAvailable }?.also { held = it }
    }

    /** Frees the held embedder. The next [get] resolves afresh. */
    suspend fun release() = mutex.withLock {
        held?.release()
        held = null
    }
}
