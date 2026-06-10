package com.medtroniclabs.microcoaching.ai.retrieval

import java.text.Normalizer

/**
 * Tokenizer used by [ModuleKnowledgeIndex] for both index-build and query-time.
 *
 * Design:
 * - Normalises to Unicode NFC and strips zero-width joiner / non-joiner
 *   (ZWJ U+200D, ZWNJ U+200C) up front, so visually identical Bangla that differs
 *   only in code-point composition or invisible joiners tokenises identically.
 * - Splits on whitespace, ASCII punctuation, and the Bangla full stop `।`.
 * - Lowercases ASCII characters; Bangla characters are case-less, so no-op there.
 * - Drops single-character tokens and pure punctuation.
 * - Emits **character bigrams of Bangla-script runs** alongside the whole-word
 *   tokens, so morphologically inflected forms — common in Bangla compounding
 *   (e.g. টিকার vs টিকা) — still match.
 *
 * Crucially, bigrams are emitted on **both** the index and query sides (this method
 * is the single tokeniser for both passes): a query bigram only contributes to BM25
 * if the same bigram exists in the index vocabulary. Restricting bigrams to Bangla
 * script keeps the index bounded — ASCII/English stays whole-word — which is more
 * than affordable for the ≤ 200 chunk pilot corpus. Same trick as Lucene's CJK
 * analyser: cheap, no stemmer needed.
 */
object BanglaTokenizer {

    private val splitPattern = Regex("""[\s।,?!;:।\-/()\[\]"'"']+""")
    private val zeroWidthJoiners = setOf(Char(0x200C), Char(0x200D)) // ZWNJ, ZWJ
    private val banglaRange = 0x0980..0x09FF

    /** Tokenise a free-text string for indexing or querying — symmetric on both sides. */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
            .filterNot { it in zeroWidthJoiners }

        val words = splitPattern
            .split(normalized)
            .map { it.lowercase() }
            .filter { it.length >= 2 || it.isBanglaCharacter() }
            .filter { it.isNotBlank() }

        val bigrams = banglaBigrams(normalized)
        return if (bigrams.isEmpty()) words else (words + bigrams).distinct()
    }

    /**
     * Character bigrams drawn from maximal Bangla-script runs in [text]. Runs are
     * isolated first so a bigram never straddles a word boundary or mixes scripts.
     */
    private fun banglaBigrams(text: String): List<String> {
        val out = mutableListOf<String>()
        val run = StringBuilder()
        fun flush() {
            for (i in 0 until run.length - 1) out.add(run.substring(i, i + 2))
            run.setLength(0)
        }
        for (ch in text) {
            if (ch.code in banglaRange) run.append(ch) else flush()
        }
        flush()
        return out
    }

    private fun String.isBanglaCharacter(): Boolean =
        length == 1 && this[0].code in banglaRange
}
