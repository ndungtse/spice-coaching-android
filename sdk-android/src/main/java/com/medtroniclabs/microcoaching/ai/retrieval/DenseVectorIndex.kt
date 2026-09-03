package com.medtroniclabs.microcoaching.ai.retrieval

import android.util.Log

/**
 * In-memory dense retrieval index over the card-embedding vectors synced from the
 * backend (`GET /sync/card-embeddings`), joined to [GroundingChunk]s on
 * [GroundingChunk.cardId].
 *
 * Vectors are L2-normalized at sync-write time and query vectors are normalized by
 * the encoder, so cosine similarity reduces to a dot product. Search is a brute-force
 * scan: at the corpus scale this index serves (hundreds of cards, one query per chat
 * message) that is microseconds, and it keeps the class free of native dependencies
 * so the JVM eval harness can exercise it directly.
 *
 * Rows that cannot be joined are dropped at build time — a vector whose `card_id`
 * matches no chunk (retired card, or a module not synced to this device), a chunk
 * with no [GroundingChunk.cardId] (cached before the backend shipped per-card ids),
 * or a vector whose dimension disagrees with the rest. The drop counts are logged
 * once so a device audit can tell "dense index empty" from "dense index missing".
 */
class DenseVectorIndex private constructor(
    private val chunks: List<GroundingChunk>,
    private val vectors: Array<FloatArray>,
) {

    val size: Int get() = chunks.size

    /**
     * Top-[k] chunks by cosine similarity to [query], descending. [query] must be
     * L2-normalized and match the index dimension; a mismatched query returns empty
     * rather than throwing, because the encoder and the synced vectors can disagree
     * transiently across a model upgrade.
     */
    fun search(query: FloatArray, k: Int): List<Pair<GroundingChunk, Float>> {
        if (chunks.isEmpty() || k <= 0) return emptyList()
        if (query.size != vectors[0].size) {
            Log.w(TAG, "query dim ${query.size} != index dim ${vectors[0].size} — returning no dense hits")
            return emptyList()
        }
        val scored = ArrayList<Pair<GroundingChunk, Float>>(chunks.size)
        for (i in chunks.indices) {
            val v = vectors[i]
            var dot = 0f
            for (j in v.indices) dot += v[j] * query[j]
            scored.add(chunks[i] to dot)
        }
        scored.sortByDescending { it.second }
        return scored.take(k)
    }

    companion object {
        private const val TAG = "DenseVectorIndex"

        fun build(
            chunks: List<GroundingChunk>,
            vectorsByCardId: Map<String, FloatArray>,
        ): DenseVectorIndex {
            val joinedChunks = ArrayList<GroundingChunk>()
            val joinedVectors = ArrayList<FloatArray>()
            var dim = -1
            var droppedDim = 0
            var chunksWithoutId = 0
            for (chunk in chunks) {
                val cardId = chunk.cardId
                if (cardId == null) {
                    chunksWithoutId++
                    continue
                }
                val vec = vectorsByCardId[cardId] ?: continue
                if (dim == -1) dim = vec.size
                if (vec.size != dim) {
                    droppedDim++
                    continue
                }
                joinedChunks.add(chunk)
                joinedVectors.add(vec)
            }
            val unmatchedVectors = vectorsByCardId.size - joinedChunks.size - droppedDim
            Log.i(
                TAG,
                "built: ${joinedChunks.size} vectors joined of ${vectorsByCardId.size} synced " +
                    "(chunks without cardId=$chunksWithoutId, unmatched vectors=$unmatchedVectors, " +
                    "wrong-dimension=$droppedDim, dim=$dim)",
            )
            return DenseVectorIndex(joinedChunks, joinedVectors.toTypedArray())
        }
    }
}
