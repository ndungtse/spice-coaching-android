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
 * In-memory retrieval index over the on-device module corpus.
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
 * length-normalised against its OWN peers, so module-level metadata cannot inflate a
 * card's body length and depress its real body term frequency. `synonyms_en` is NOT
 * indexed; it rides query expansion ([ClinicalSynonymMap]) instead.
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

    /** BM25 fields scored independently and combined by [FieldWeights]. */
    enum class Field { TITLE, BODY, QUESTION, KEYWORD }

    /**
     * Per-field multipliers for the combined score. Exposed as a value so the balance
     * between "the card is TITLED this" and "the card MENTIONS this" can be swept
     * against the benchmark instead of argued about.
     */
    data class FieldWeights(
        val title: Float = W_TITLE,
        val body: Float = W_BODY,
        val question: Float = W_QUESTION,
        val keyword: Float = W_KEYWORD,
    ) {
        operator fun get(field: Field): Float = when (field) {
            Field.TITLE -> title
            Field.BODY -> body
            Field.QUESTION -> question
            Field.KEYWORD -> keyword
        }

        companion object { val DEFAULT = FieldWeights() }
    }

    /**
     * Top-K most relevant chunks for [query]. Returns empty when nothing clears the
     * gate — the caller treats that as the "no grounding found" refusal trigger.
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
        charBigramWeight: Float = DEFAULT_CHAR_BIGRAM_WEIGHT,
        fieldWeights: FieldWeights = FieldWeights.DEFAULT,
        definitionBoost: Boolean = true,
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
            .downWeightCharBigrams(charBigramWeight)
        val weightedTerms = termWeights.downWeightCharBigrams(charBigramWeight)

        // A bare "X কি?" is the one shape where term statistics point the wrong way —
        // see DefinitionQueryBoost. Null for every other question, so this is inert
        // on the vast majority of traffic.
        val definitionTopic = if (definitionBoost) DefinitionQueryBoost.topicOf(query) else null

        val combined = chunks.indices.map { i ->
            var s = 0f
            for (field in Field.entries) {
                val weights = if (field == Field.TITLE) originalWeights else weightedTerms
                val fieldScore = scorers.getValue(field).scoreWeighted(weights, i)
                if (fieldScore != 0f) s += fieldWeights[field] * fieldScore
            }
            if (s > 0f && definitionTopic != null && DefinitionQueryBoost.matches(chunks[i], definitionTopic)) {
                s *= DefinitionQueryBoost.BOOST
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

    /**
     * Why a chunk scored what it scored: the per-field contributions already multiplied
     * by [fieldWeight], plus the same score computed from the CHW's words alone so the
     * character-bigram share is visible. Diagnostics only — used by the retrieval lab to
     * explain a ranking; runs the same scorers as [search], so it cannot drift from it.
     */
    internal fun explain(
        query: String,
        chunkId: String,
        language: Lang = Lang.EN,
        charBigramWeight: Float = DEFAULT_CHAR_BIGRAM_WEIGHT,
        fieldWeights: FieldWeights = FieldWeights.DEFAULT,
    ): Explanation? {
        val i = chunks.indexOfFirst { it.chunkId == chunkId }
        if (i < 0) return null
        val scorers = if (language == Lang.BN) scorersBn else scorersEn
        val raw = queryWeights(query, scorers) ?: return null
        val weights = QueryWeights(
            original = raw.original.downWeightCharBigrams(charBigramWeight),
            expanded = raw.expanded.downWeightCharBigrams(charBigramWeight),
        )
        val perField = Field.entries.associateWith { field ->
            val w = if (field == Field.TITLE) weights.original else weights.expanded
            fieldWeights[field] * scorers.getValue(field).scoreWeighted(w, i)
        }
        // Words only: drop the two-character Bangla fragments the tokenizer emits for
        // morphology, which otherwise dominate the total on long Bangla queries.
        val wordsOnly = weights.expanded.filterKeys { !it.isBanglaCharBigram() }
        val wordsOnlyTitle = weights.original.filterKeys { !it.isBanglaCharBigram() }
        val wordScore = Field.entries.sumOf { field ->
            val w = if (field == Field.TITLE) wordsOnlyTitle else wordsOnly
            (fieldWeights[field] * scorers.getValue(field).scoreWeighted(w, i)).toDouble()
        }.toFloat()
        return Explanation(perField = perField, total = perField.values.sum(), wordOnlyTotal = wordScore)
    }

    /** Per-field breakdown for one chunk against one query. */
    internal data class Explanation(
        val perField: Map<Field, Float>,
        val total: Float,
        val wordOnlyTotal: Float,
    ) {
        /** Share of the score that came from character bigrams rather than real words. */
        val charBigramShare: Float
            get() = if (total <= 0f) 0f else (1f - wordOnlyTotal / total).coerceIn(0f, 1f)
    }

    /** Indexed token count of one chunk's field — BM25 penalises longer fields (b=0.75). */
    internal fun fieldLength(chunkId: String, field: Field, language: Lang = Lang.EN): Int? {
        val i = chunks.indexOfFirst { it.chunkId == chunkId }
        if (i < 0) return null
        val scorers = if (language == Lang.BN) scorersBn else scorersEn
        return scorers.getValue(field).documentLength(i)
    }

    private data class QueryWeights(val original: Map<String, Float>, val expanded: Map<String, Float>)

    /** Query-term weights shared by [search] and [explain] so the two cannot disagree. */
    private fun queryWeights(query: String, scorers: Map<Field, Bm25Scorer>): QueryWeights? {
        val tokens = BanglaTokenizer.tokenizeQuery(query)
        if (tokens.isEmpty()) return null
        val bigrams = BanglaTokenizer.wordBigrams(query)
        val df: (String) -> Int = { t -> scorers.values.maxOf { it.documentFrequency(t) } }
        return QueryWeights(
            original = (tokens + bigrams).associateWith { 1.0f },
            expanded = ClinicalSynonymMap.expandQueryWeighted(tokens + bigrams, df, dynamicSynonyms),
        )
    }

    companion object {

        private const val TAG = "ModuleKnowledgeIndex"

        /**
         * Field weights for the combined score. TITLE is the strongest single-field
         * signal; QUESTION is second, so per-card `retrieval_hints_*` pull the intended
         * card without out-voting a literal title hit; KEYWORD is a low-weight recall
         * floor that lifts the right module into range without flattening within-module
         * rank. Raising TITLE further was measured to cost self-retrieval accuracy.
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

        /**
         * Weight applied to the tokenizer's Bangla character bigrams relative to the
         * words a CHW actually typed. They exist to bridge inflection (ম্যালেরিয়ার vs
         * ম্যালেরিয়া) but they outnumber real words roughly 10:1 on a short question, and
         * BM25 sums over terms — so at full weight a card can rank on Bengali
         * orthography alone, sharing no word with the query. Tunable so the trade can
         * be measured rather than argued.
         *
         * At full weight a card can rank on Bangla orthography alone, sharing no actual
         * word with the question. Dropping bigrams entirely costs accuracy the other way,
         * because they genuinely bridge inflection (ম্যালেরিয়ার → ম্যালেরিয়া). A quarter
         * weight keeps the bridge and sheds most of the noise; re-sweep it with the
         * retrieval benchmark if the corpus changes materially.
         */
        internal const val DEFAULT_CHAR_BIGRAM_WEIGHT = 0.25f

        /** A two-character all-Bangla token is a morphology bigram, not a word. */
        private fun Map<String, Float>.downWeightCharBigrams(factor: Float): Map<String, Float> {
            if (factor == 1.0f) return this
            return mapValues { (term, w) -> if (term.isBanglaCharBigram()) w * factor else w }
        }

        /** A two-character all-Bangla token is a morphology bigram, not a word. */
        private fun String.isBanglaCharBigram(): Boolean =
            length == 2 && all { it.code in 0x0980..0x09FF }

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
