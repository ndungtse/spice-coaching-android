package com.medtroniclabs.microcoaching.ai.embedding

import kotlin.math.sqrt

/**
 * The text-side and vector-side arithmetic of query encoding, separated from the
 * interpreter so it can be tested without a model.
 *
 * Everything here fails silently on a device: a wrong prompt, the wrong pad id, or a
 * missing normalization all yield a plausible 768 numbers that simply do not match
 * the card vectors. Nothing downstream can detect that — cosines just get
 * quietly worse — which is why each step is pinned by
 * [QueryEncodingTest] and the whole chain by the on-device parity test.
 */
internal object QueryEncoding {

    /**
     * EmbeddingGemma's retrieval query prompt, verbatim from the model's
     * `config_sentence_transformers.json` (trailing space included).
     *
     * The backend embedded cards with the *document* prompt (`title: none | text: `).
     * The two prompts place text in different regions of the same space, so using the
     * wrong one collapses similarity without producing any error. This constant is
     * effectively a contract with whatever produced the synced card vectors.
     */
    const val QUERY_PROMPT = "task: search result | query: "

    fun promptFor(text: String): String = QUERY_PROMPT + text.trim()

    /**
     * Fit [tokens] to the graph's fixed [size], padding with [padId].
     *
     * The exported graph takes token ids alone — a single `[1, 256]` INT32 input, no
     * attention mask — so padding is the only length signal it gets, and the pad id
     * must be the one the model treats as padding rather than any spare value.
     *
     * Truncation keeps the head and re-attaches the final token, because a question
     * states its subject first and because every sequence the model was trained on
     * ended with `<eos>`.
     */
    fun window(tokens: List<Int>, size: Int, padId: Int): IntArray {
        val ids = IntArray(size) { padId }
        if (tokens.size <= size) {
            tokens.forEachIndexed { i, id -> ids[i] = id }
        } else {
            for (i in 0 until size) ids[i] = tokens[i]
            ids[size - 1] = tokens.last()
        }
        return ids
    }

    /**
     * Scales to unit length so the dense index's dot product is a cosine, matching the
     * normalization applied to card vectors at write time
     * ([com.medtroniclabs.microcoaching.data.db.entity.l2Normalized]). Idempotent, so
     * it is safe over a graph that already ends in its own Normalize module. Null for a
     * zero vector, which has no direction to preserve.
     */
    fun l2Normalize(vector: FloatArray): FloatArray? {
        var sumOfSquares = 0.0
        for (v in vector) sumOfSquares += (v * v).toDouble()
        if (sumOfSquares <= 0.0) return null
        val norm = sqrt(sumOfSquares).toFloat()
        return FloatArray(vector.size) { vector[it] / norm }
    }
}
