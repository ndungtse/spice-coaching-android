package com.medtroniclabs.microcoaching.ai.embedding

import java.util.PriorityQueue

/**
 * SentencePiece BPE tokenizer for EmbeddingGemma, producing the exact id sequence the
 * model was trained on: `<bos>` + merged pieces + `<eos>`.
 *
 * A query must reach the encoder as the same tokens the backend used for the cards.
 * Any divergence — one merge chosen differently, a space handled differently — moves
 * the query vector inside the embedding space while the card vectors stay put, and
 * every cosine after that is wrong in a way nothing downstream can detect. That is
 * why [SentencePieceBpeTokenizerTest] pins all 67 cases to ids produced by the real
 * `sentencepiece` library rather than asserting anything about this implementation.
 *
 * Three stages, matching `bpe_model.cc`:
 *  1. escape — every space becomes `▁`, and nothing else changes (this model's
 *     normalizer is `identity`, adds no dummy prefix and collapses no whitespace);
 *  2. split — the longest user-defined piece at each position is taken whole, so a
 *     newline or a run of spaces stays the single token it was trained as; anything
 *     else contributes one code point. User-defined pieces are merge barriers.
 *  3. merge — repeatedly join the adjacent pair whose combined piece scores highest,
 *     earliest position winning ties, until no adjacent pair is in the vocabulary.
 *
 * Symbols still absent from the vocabulary at the end decompose into their UTF-8 byte
 * pieces (`byte_fallback: true`), so no input produces `<unk>`.
 */
internal class SentencePieceBpeTokenizer(private val vocab: SentencePieceVocab) {

    /** `<bos>` + pieces + `<eos>`, as `GemmaTokenizer` (add_bos_token, add_eos_token) does. */
    fun encode(text: String): List<Int> {
        val ids = ArrayList<Int>(text.length + 2)
        ids += vocab.bosId
        encodePieces(escape(text), ids)
        ids += vocab.eosId
        return ids
    }

    private fun escape(text: String): String = text.replace(' ', WHITESPACE_MARKER)

    private fun encodePieces(escaped: String, out: MutableList<Int>) {
        if (escaped.isEmpty()) return
        val symbols = split(escaped)
        merge(symbols)
        for (symbol in symbols) {
            if (symbol.text == null) continue
            emit(symbol.text!!, out)
        }
    }

    /**
     * A mutable doubly-linked run of symbols. Merging rewrites [text] in place and
     * unlinks the right-hand neighbour by nulling its [text], which is what lets a
     * queued pair be recognised as stale without rebuilding the queue.
     */
    private class Symbol(var text: String?, var prev: Int, var next: Int, val fixed: Boolean)

    private fun split(escaped: String): MutableList<Symbol> {
        val symbols = ArrayList<Symbol>(escaped.length)
        var i = 0
        while (i < escaped.length) {
            val userDefined = vocab.matchUserDefined(escaped, i)
            val piece: String
            val fixed: Boolean
            if (userDefined != null) {
                piece = userDefined
                fixed = true
            } else {
                val end = escaped.offsetByCodePoints(i, 1)
                piece = escaped.substring(i, end)
                fixed = false
            }
            symbols += Symbol(piece, symbols.size - 1, symbols.size + 1, fixed)
            i += piece.length
        }
        symbols.lastOrNull()?.next = -1
        return symbols
    }

    /** A queued merge: the two symbol slots, the merged piece's score, and its text. */
    private class Candidate(val left: Int, val right: Int, val score: Float, val merged: String)

    private fun merge(symbols: MutableList<Symbol>) {
        // Highest score wins; equal scores resolve to the leftmost pair, matching
        // sentencepiece's comparator so tie-heavy inputs merge in the same order.
        val queue = PriorityQueue<Candidate>(symbols.size.coerceAtLeast(1)) { a, b ->
            if (a.score != b.score) b.score.compareTo(a.score) else a.left.compareTo(b.left)
        }

        fun offer(left: Int, right: Int) {
            if (left < 0 || right < 0 || right >= symbols.size) return
            val l = symbols[left]
            val r = symbols[right]
            if (l.text == null || r.text == null) return
            // A user-defined piece is a token in its own right, never merge material.
            if (l.fixed || r.fixed) return
            val merged = l.text + r.text
            val score = vocab.scoreOf(merged) ?: return
            queue += Candidate(left, right, score, merged)
        }

        for (i in 0 until symbols.size - 1) offer(i, i + 1)

        while (queue.isNotEmpty()) {
            val best = queue.poll()
            val l = symbols[best.left]
            val r = symbols[best.right]
            // Stale: one side was consumed by an earlier merge, or has since changed.
            if (l.text == null || r.text == null) continue
            if (l.text + r.text != best.merged) continue

            l.text = best.merged
            r.text = null
            l.next = r.next
            if (r.next >= 0) symbols[r.next].prev = best.left

            offer(l.prev, best.left)
            offer(best.left, l.next)
        }
    }

    /** Emits a symbol's id, decomposing to UTF-8 byte pieces when it has none. */
    private fun emit(piece: String, out: MutableList<Int>) {
        val id = vocab.idOf(piece)
        if (id != null) {
            out += id
            return
        }
        for (byte in piece.toByteArray(Charsets.UTF_8)) {
            val byteId = vocab.byteId(byte.toInt())
            out += if (byteId >= 0) byteId else vocab.unkId
        }
    }

    private companion object {
        /** U+2581 LOWER ONE EIGHTH BLOCK — SentencePiece's escaped space. */
        const val WHITESPACE_MARKER = '▁'
    }
}
