package com.medtroniclabs.microcoaching.ai.retrieval

import android.util.Log
import com.medtroniclabs.microcoaching.content.richtext.bodyToPlainText
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.localized.readLocalized
import com.medtroniclabs.microcoaching.data.localized.readLocalizedArray
import com.medtroniclabs.microcoaching.data.localized.readLocalizedBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull

/**
 * In-memory retrieval index over the on-device module corpus (B1 of chat_plan.md).
 *
 * Each module contributes one chunk per entry in `cards_json` (title + body, BN/EN
 * where available). Quiz JSON is **not** indexed — cards + `search_metadata` only.
 *
 * Scoring is **field-weighted** rather than a single flat token bag: each chunk is
 * tokenised into four independent BM25 fields per language —
 *   - TITLE    (strong, specific)
 *   - BODY     (the card prose)
 *   - QUESTION (per-card `retrieval_hints_*` / `questions_*`)
 *   - KEYWORD  (module `keywords`/`search_phrases_*` + per-card `keywords_*` +
 *              legacy per-card `retrieval_metadata`; low-weight recall hint)
 * and the per-field scores are combined as a weighted sum. Each field is
 * length-normalised against its OWN peers, so module-level metadata can no longer
 * inflate a card's body length and depress its real body TF — the bug that flattened
 * within-module ranking and de-calibrated the old absolute threshold. `synonyms_en`
 * is NOT indexed; it rides query expansion ([ClinicalSynonymMap]) instead.
 *
 * Two languages stay fully separate (EN query never scores against BN tokens).
 * Build cost is ≪ 100 ms for ~200 chunks. Rebuild on app start and after every
 * successful inbound module sync — never per query.
 */
class ModuleKnowledgeIndex private constructor(
    private val chunks: List<GroundingChunk>,
    private val scorersEn: Map<Field, Bm25Scorer>,
    private val scorersBn: Map<Field, Bm25Scorer>,
    /** Per-corpus `synonyms_en` (abbreviation → expansion) fed to query expansion. */
    private val dynamicSynonyms: Map<String, List<String>>,
) {

    /** Which per-language index a [search] call scores against. */
    enum class Lang { EN, BN }

    /** BM25 fields scored independently and combined by [fieldWeight]. */
    enum class Field { TITLE, BODY, QUESTION, KEYWORD }

    /**
     * Top-K most relevant chunks for [query]. Returns empty when nothing clears the
     * gate — caller treats that as the "no grounding found" refusal trigger (L2 in
     * chat_plan.md §B4).
     *
     * @param scoreThreshold absolute floor on the combined field-weighted score.
     *   `0f` (used by unit tests) bypasses the gate to isolate "was it indexed?" from
     *   production tuning. The default [DEFAULT_SCORE_THRESHOLD] is deliberately low —
     *   the semantic backstop is `OffTopicGuard` (clinical-token overlap) plus the
     *   downstream groundedness gate, not a hand-tuned BM25 magnitude.
     * @param language which per-language index to score against.
     */
    fun search(
        query: String,
        k: Int = 2,
        scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
        language: Lang = Lang.EN,
    ): List<GroundingChunk> {
        if (query.isBlank() || chunks.isEmpty()) {
            Log.i(TAG, "search lang=$language → 0 hits (blankQuery=${query.isBlank()} emptyIndex=${chunks.isEmpty()})")
            return emptyList()
        }
        val rawTokens = BanglaTokenizer.tokenize(query)
        val tokens = BanglaTokenizer.tokenizeQuery(query)
        if (tokens.isEmpty()) {
            Log.i(
                TAG,
                "search lang=$language → 0 hits (no content tokens; " +
                    "raw=${rawTokens.size} all-stopword=${rawTokens.isNotEmpty()})",
            )
            return emptyList()
        }
        val bigrams = BanglaTokenizer.wordBigrams(query)
        val scorers = if (language == Lang.BN) scorersBn else scorersEn
        // Document frequency for the bridge gate = max across fields: a term "exists in
        // the corpus" if it appears in ANY field, so a bridge only carries full weight
        // when its source word is genuinely absent everywhere (e.g. "engorgement").
        val df: (String) -> Int = { t -> scorers.values.maxOf { it.documentFrequency(t) } }
        val termWeights =
            ClinicalSynonymMap.expandQueryWeighted(tokens + bigrams, df, dynamicSynonyms)
        // The TITLE field is scored with the CHW's ACTUAL words only — never query
        // expansions. A title carries the heaviest weight, so letting a generic
        // expansion term land there (e.g. the "pw"→"pregnancy" bridge hitting a
        // "…During Pregnancy" title) drowns out the real discriminator the CHW typed
        // (e.g. "90/60"). Expansions still widen recall in BODY/QUESTION/KEYWORD.
        val originalWeights = (tokens + bigrams).associateWith { 1.0f }

        val combined = chunks.indices.map { i ->
            var s = 0f
            for (field in Field.entries) {
                val weights = if (field == Field.TITLE) originalWeights else termWeights
                val fieldScore = scorers.getValue(field).scoreWeighted(weights, i)
                if (fieldScore != 0f) s += fieldWeight(field) * fieldScore
            }
            i to s
        }
        val result = combined
            .filter { (_, s) -> if (scoreThreshold <= 0f) s > 0f else s >= scoreThreshold }
            .sortedByDescending { it.second }
            .take(k)
            .map { (i, s) -> chunks[i].copy(score = s) }

        val expansionTerms = termWeights.filterKeys { it !in tokens && it !in bigrams }
        val expansionNote = if (expansionTerms.isEmpty()) "" else {
            " expanded(+${expansionTerms.size})=" +
                expansionTerms.entries.joinToString(prefix = "[", postfix = "]") { (t, w) -> "$t(w=$w)" }
        }
        val droppedStopwords = rawTokens.count { it in BanglaTokenizer.STOPWORDS }
        Log.i(
            TAG,
            "search lang=$language thr=$scoreThreshold tokens=$tokens bigrams=$bigrams " +
                "stopwordsDropped=$droppedStopwords$expansionNote → " +
                "${result.size} hits: " +
                result.joinToString { "[%s %s %.2f]".format(it.source, it.moduleFamilyId, it.score) },
        )
        return result
    }

    /** Total number of chunks currently indexed. Exposed for telemetry / diagnostics. */
    val size: Int = chunks.size

    companion object {

        private const val TAG = "ModuleKnowledgeIndex"

        /**
         * Field weights for the combined score. TITLE is the strongest single-field
         * signal (replaces the old title×3 token-duplication hack); QUESTION is second
         * so per-card `retrieval_hints_*` strongly pull the intended card without
         * out-voting a literal title hit; KEYWORD is a low-weight recall floor
         * that lifts the right module into range without flattening within-module rank.
         */
        private const val W_TITLE = 3.0f
        private const val W_BODY = 1.0f
        private const val W_QUESTION = 2.5f
        private const val W_KEYWORD = 0.5f

        /**
         * Production retrieval floor on the combined score. Low by design (was an
         * absolute 3.0 that the metadata-in-body bug de-calibrated): with metadata moved
         * to its own field the body score is clean again, and the real semantic guards
         * are `OffTopicGuard` + the groundedness gate. A low floor here cuts false
         * refusals; it cannot admit hallucination on its own (the model never sees
         * un-retrieved content).
         */
        private const val DEFAULT_SCORE_THRESHOLD = 1.5f

        private fun fieldWeight(field: Field): Float = when (field) {
            Field.TITLE -> W_TITLE
            Field.BODY -> W_BODY
            Field.QUESTION -> W_QUESTION
            Field.KEYWORD -> W_KEYWORD
        }

        /** Build an empty index — useful as a no-op fallback before sync runs. */
        fun empty(): ModuleKnowledgeIndex {
            val emptyScorers = Field.entries.associateWith { Bm25Scorer(emptyList()) }
            return ModuleKnowledgeIndex(emptyList(), emptyScorers, emptyScorers, emptyMap())
        }

        private fun fieldTokens(text: String?): List<String> {
            if (text.isNullOrBlank()) return emptyList()
            return BanglaTokenizer.tokenize(text) + BanglaTokenizer.wordBigrams(text)
        }

        /**
         * Build the index from a fresh module list. Caller is responsible for invoking
         * on a background dispatcher — the JSON parse + tokenisation is bounded but not
         * free. Only card content and `search_metadata` reach the index.
         */
        fun build(
            modules: List<ModuleEntity>,
            retiredFamilyIds: Set<String> = emptySet(),
        ): ModuleKnowledgeIndex {
            val activeModules = if (retiredFamilyIds.isEmpty()) {
                modules
            } else {
                modules.filter { it.moduleFamilyId !in retiredFamilyIds }
            }
            val chunks = mutableListOf<GroundingChunk>()

            // Per-field, per-language parallel token-document lists (same chunk indices).
            val titleEn = mutableListOf<List<String>>(); val bodyEn = mutableListOf<List<String>>()
            val questionEn = mutableListOf<List<String>>(); val keywordEn = mutableListOf<List<String>>()
            val titleBn = mutableListOf<List<String>>(); val bodyBn = mutableListOf<List<String>>()
            val questionBn = mutableListOf<List<String>>(); val keywordBn = mutableListOf<List<String>>()

            val dynamicSynonyms = LinkedHashMap<String, MutableList<String>>()
            var cardsWithSearchMetadata = 0
            var hintPhraseCount = 0

            for (m in activeModules) {
                val meta = ModuleCorpusParser.parseSearchMetadata(m.searchMetadataJson)
                meta.synonyms.forEach { (k, vs) ->
                    dynamicSynonyms.getOrPut(k.lowercase()) { mutableListOf() }.addAll(vs)
                }

                val cardChunks = ModuleCorpusParser.extractCardChunks(m)
                cardChunks.forEach { (_, _, cardMeta) ->
                    cardMeta.synonyms.forEach { (k, vs) ->
                        dynamicSynonyms.getOrPut(k.lowercase()) { mutableListOf() }.addAll(vs)
                    }
                }
                // Tokenise the module-level search vocabulary ONCE and share the
                // token strings across this module's card chunks. Tokenising
                // inside the card loop allocated a fresh copy of the module's
                // whole keyword/phrase vocabulary per card (≈10× duplication for
                // a 10-card module), in both languages. Indexed content per
                // chunk is unchanged — same tokens, shared string instances.
                val moduleKeywordsEn = (meta.keywordsEn + meta.phrasesEn).flatMap { fieldTokens(it) }
                val moduleKeywordsBn = (meta.keywordsBn + meta.phrasesBn).flatMap { fieldTokens(it) }

                cardChunks.forEach { (chunk, rmeta, cardMeta) ->
                    if (cardMeta.hasSearchableContent) {
                        cardsWithSearchMetadata++
                        hintPhraseCount += cardMeta.hintsEn.size + cardMeta.hintsBn.size
                    }
                    chunks += chunk

                    titleEn += fieldTokens(chunk.titleEn)
                    bodyEn += fieldTokens(chunk.bodyEn)
                    questionEn += (
                        cardMeta.hintsEn + cardMeta.questionsEn
                        ).flatMap { fieldTokens(it) }
                    keywordEn += moduleKeywordsEn +
                        (rmeta.allTerms + cardMeta.keywordsEn).flatMap { fieldTokens(it) }

                    titleBn += fieldTokens(chunk.titleBn)
                    bodyBn += fieldTokens(chunk.bodyBn)
                    questionBn += (
                        cardMeta.hintsBn + cardMeta.questionsBn
                        ).flatMap { fieldTokens(it) }
                    keywordBn += moduleKeywordsBn +
                        (rmeta.allTerms + cardMeta.keywordsBn).flatMap { fieldTokens(it) }
                }
            }

            val scorersEn = mapOf(
                Field.TITLE to Bm25Scorer(titleEn),
                Field.BODY to Bm25Scorer(bodyEn),
                Field.QUESTION to Bm25Scorer(questionEn),
                Field.KEYWORD to Bm25Scorer(keywordEn),
            )
            val scorersBn = mapOf(
                Field.TITLE to Bm25Scorer(titleBn),
                Field.BODY to Bm25Scorer(bodyBn),
                Field.QUESTION to Bm25Scorer(questionBn),
                Field.KEYWORD to Bm25Scorer(keywordBn),
            )
            Log.i(
                TAG,
                "Built index — ${chunks.size} card chunks across ${activeModules.size} modules " +
                    "(retiredFamilies=${retiredFamilyIds.size} " +
                    "cardsWithSearchMetadata=$cardsWithSearchMetadata hintPhrases=$hintPhraseCount)",
            )
            return ModuleKnowledgeIndex(chunks, scorersEn, scorersBn, dynamicSynonyms)
        }
    }
}
