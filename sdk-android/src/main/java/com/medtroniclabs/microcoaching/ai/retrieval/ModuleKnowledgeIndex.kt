package com.medtroniclabs.microcoaching.ai.retrieval

import android.util.Log
import com.medtroniclabs.microcoaching.content.richtext.bodyToPlainText
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * In-memory retrieval index over the on-device module corpus (B1 of chat_plan.md).
 *
 * Each module contributes:
 *   - one chunk per entry in `cards_json` (title + body, BN/EN where available)
 *   - one chunk per entry in `quiz_json` (case setup + question + explanation)
 *
 * Each chunk is tokenised into **two independent BM25 indices** — one over its
 * English fields (`titleEn`/`bodyEn`) and one over its Bangla fields
 * (`titleBn`/`bodyBn`). Callers query the index matching the query's language
 * (see [search] / [Lang]) so a Bangla query is never scored against English tokens
 * (or vice-versa); that keeps cross-language term bleed from skewing BM25 ranking.
 * Quiz hits are re-ranked +20% over card hits to favour clinician-authored Q&A pairs.
 *
 * Build cost is ≪ 100 ms for ~200 chunks. Rebuild on app start and after every
 * successful inbound module sync — never per query.
 */
class ModuleKnowledgeIndex private constructor(
    private val chunks: List<GroundingChunk>,
    private val scorerEn: Bm25Scorer,
    private val scorerBn: Bm25Scorer,
) {

    /** Which per-language index a [search] call scores against. */
    enum class Lang { EN, BN }

    /**
     * Top-K most relevant chunks for [query]. Returns empty when no chunk scores
     * above [scoreThreshold] — caller treats that as the "no grounding found"
     * refusal trigger (L2 in chat_plan.md §B4).
     *
     * @param applyQuizBoost when true (LLM grounding path), quiz chunks are
     *   re-ranked +20% to favour clinician Q&A pairs as context. Set false on the
     *   low-end retrieval-only path where the quiz explanation *is* the user-visible
     *   answer — boosting quiz chunks there pushes wrong-module explanations above
     *   on-topic card content.
     * @param language which per-language index to score against — [Lang.EN] for an
     *   English query, [Lang.BN] for a Bangla query. Scoring like-for-like avoids
     *   comparing uncalibrated cross-language BM25 scores.
     */
    fun search(
        query: String,
        k: Int = 2,
        scoreThreshold: Float = 3.0f,
        applyQuizBoost: Boolean = true,
        language: Lang = Lang.EN,
    ): List<GroundingChunk> {
        if (query.isBlank() || chunks.isEmpty()) return emptyList()
        val tokens = BanglaTokenizer.tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val expandedTokens = ClinicalSynonymMap.expandQuery(tokens)
        val scorer = if (language == Lang.BN) scorerBn else scorerEn

        return scorer.topK(expandedTokens, k = k * 2) // overfetch to give the quiz-boost room to reorder
            .map { scored ->
                val base = chunks[scored.docId]
                val boost = if (applyQuizBoost && base.source == GroundingChunk.Source.QUIZ) 1.2f else 1.0f
                base.copy(score = scored.score * boost)
            }
            .filter { it.score >= scoreThreshold }
            .sortedByDescending { it.score }
            .take(k)
    }

    /** Total number of chunks currently indexed. Exposed for telemetry / diagnostics. */
    val size: Int = chunks.size

    companion object {

        private const val TAG = "ModuleKnowledgeIndex"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Build an empty index — useful as a no-op fallback before sync runs. */
        fun empty(): ModuleKnowledgeIndex =
            ModuleKnowledgeIndex(emptyList(), Bm25Scorer(emptyList()), Bm25Scorer(emptyList()))

        /**
         * Build the index from a fresh module list. Caller is responsible for
         * invoking on a background dispatcher — the JSON parse + tokenisation
         * is bounded but not free.
         */
        fun build(modules: List<ModuleEntity>): ModuleKnowledgeIndex {
            val chunks = mutableListOf<GroundingChunk>()
            for (m in modules) {
                chunks += extractCardChunks(m)
                chunks += extractQuizChunks(m)
            }
            // Tokenise each language side into its own document list (same chunk
            // indices in both), so the EN and BN scorers never share a vocabulary.
            // Title tokens are repeated 3× so a card whose title directly matches
            // the query out-ranks the module overview card, which accumulates the
            // highest general TF for all module keywords via its broad body text.
            fun docTokens(title: String?, body: String?): List<String> {
                val text = listOfNotNull(title, title, title, body).joinToString(" ").trim()
                return BanglaTokenizer.tokenize(text)
            }
            val enDocs = chunks.map { docTokens(it.titleEn, it.bodyEn) }
            val bnDocs = chunks.map { docTokens(it.titleBn, it.bodyBn) }
            Log.i(TAG, "Built index — ${chunks.size} chunks across ${modules.size} modules")
            return ModuleKnowledgeIndex(chunks, Bm25Scorer(enDocs), Bm25Scorer(bnDocs))
        }

        private fun extractCardChunks(module: ModuleEntity): List<GroundingChunk> {
            val arr = runCatching {
                json.parseToJsonElement(module.cardsJson).jsonArray
            }.getOrNull() ?: return emptyList()
            return arr.mapIndexedNotNull { idx, el ->
                val obj = el as? JsonObject ?: return@mapIndexedNotNull null
                val titleBn = obj.string("title_bn") ?: obj.string("title")
                val bodyBn = obj.bodyText("body_bn") ?: obj.bodyText("body")
                val titleEn = obj.string("title_en")
                val bodyEn = obj.bodyText("body_en")
                if (titleBn.isNullOrBlank() && bodyBn.isNullOrBlank() &&
                    titleEn.isNullOrBlank() && bodyEn.isNullOrBlank()
                ) return@mapIndexedNotNull null
                GroundingChunk(
                    source = GroundingChunk.Source.CARD,
                    moduleFamilyId = module.moduleFamilyId,
                    positionalId = idx,
                    titleEn = titleEn,
                    bodyEn = bodyEn,
                    titleBn = titleBn,
                    bodyBn = bodyBn,
                    score = 0f,
                )
            }
        }

        private fun extractQuizChunks(module: ModuleEntity): List<GroundingChunk> {
            val arr = runCatching {
                json.parseToJsonElement(module.quizJson).jsonArray
            }.getOrNull() ?: return emptyList()
            return arr.mapIndexedNotNull { idx, el ->
                val obj = el as? JsonObject ?: return@mapIndexedNotNull null
                val questionBn = obj.string("question_bn") ?: obj.string("question")
                val caseBn = obj.string("case_setup_bn") ?: obj.string("case_setup")
                val explanationBn = obj.string("explanation_bn") ?: obj.string("explanation")
                val questionEn = obj.string("question_en")
                val explanationEn = obj.string("explanation_en")
                if (questionBn.isNullOrBlank() && questionEn.isNullOrBlank()) return@mapIndexedNotNull null
                val titleBn = listOfNotNull(caseBn, questionBn).joinToString(" — ").ifBlank { null }

                // Extract correct answer option text so BM25 indexes clinical values
                // (e.g. "BP ≥ 140/90") that appear only in the options array, not the
                // explanation. Wrong options are intentionally excluded to avoid indexing
                // incorrect clinical values as retrievable signal.
                val correctIndicesArr = obj["correct_indices"] as? JsonArray
                val correctBn = (obj["options_bn"] as? JsonArray)?.let { opts ->
                    correctIndicesArr?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
                        ?.mapNotNull { i -> opts.getOrNull(i)?.quizOptionLabel() }
                        ?.joinToString(" ")?.ifBlank { null }
                }
                val correctEn = (obj["options_en"] as? JsonArray)?.let { opts ->
                    correctIndicesArr?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
                        ?.mapNotNull { i -> opts.getOrNull(i)?.quizOptionLabel() }
                        ?.joinToString(" ")?.ifBlank { null }
                }

                val bodyBn = joinSentences(correctBn, explanationBn)
                val bodyEn = joinSentences(correctEn, explanationEn)

                GroundingChunk(
                    source = GroundingChunk.Source.QUIZ,
                    moduleFamilyId = module.moduleFamilyId,
                    positionalId = idx,
                    titleEn = questionEn,
                    bodyEn = bodyEn,
                    titleBn = titleBn,
                    bodyBn = bodyBn,
                    score = 0f,
                )
            }
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull

        /**
         * Normalise a quiz options entry to its display label.
         * Backend ships either a plain string or {"label":"…","value":0}.
         * Mirrors the `toOptionLabel()` function in QuizJsonParser.kt.
         */
        private fun JsonElement.quizOptionLabel(): String? = when (this) {
            is JsonPrimitive -> contentOrNull
            is JsonObject -> (this["label"] as? JsonPrimitive)?.contentOrNull
                ?: (this["text"] as? JsonPrimitive)?.contentOrNull
                ?: (this["answer"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }

        /**
         * Join two nullable text parts with a proper sentence boundary.
         * If [first] already ends with sentence-closing punctuation (`.`, `।`, `!`, `?`,
         * `:`) a plain space is inserted; otherwise a full stop + space is added so the
         * two parts read as distinct sentences rather than a run-on string.
         * Returns null when both parts are blank.
         */
        private fun joinSentences(first: String?, second: String?): String? {
            val a = first?.trim()?.takeIf { it.isNotBlank() }
            val b = second?.trim()?.takeIf { it.isNotBlank() }
            return when {
                a == null && b == null -> null
                a == null -> b
                b == null -> a
                a.last() in listOf('.', '।', '!', '?', ':') -> "$a $b"
                else -> "$a. $b"
            }
        }

        /**
         * Extract a body field as plain, index-ready prose. The field may be a
         * markdown string or a TipTap/ProseMirror block array — [bodyToPlainText]
         * dispatches and strips markup/JSON so the BM25 index never sees node types,
         * attrs, or media URLs. Returns null for absent / blank bodies.
         *
         * A bare [JsonObject] (single block, not wrapped in an array) is wrapped in
         * `[…]` so the TipTap parser's `startsWith("[")` gate accepts it. Without
         * this guard the object is serialised as `{…}`, treated as a markdown string,
         * and produces garbled text like raw JSON attribute names in the index.
         */
        private fun JsonObject.bodyText(key: String): String? {
            val raw = when (val el = this[key]) {
                null -> return null
                is JsonPrimitive -> el.contentOrNull
                is JsonObject -> "[${el}]" // wrap single block node in array for TipTap parser
                else -> el.toString()      // JsonArray already starts with "["; pass through
            } ?: return null
            return bodyToPlainText(raw).ifBlank { null }
        }
    }
}
