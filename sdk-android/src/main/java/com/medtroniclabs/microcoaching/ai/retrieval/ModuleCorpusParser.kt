package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.content.richtext.bodyToPlainText
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.localized.readLocalized
import com.medtroniclabs.microcoaching.data.localized.readLocalizedArray
import com.medtroniclabs.microcoaching.data.localized.readLocalizedBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray

/**
 * Parses the on-device module corpus JSON (`search_metadata`, `cards_json`) into the
 * typed, index-ready streams that [ModuleKnowledgeIndex.build] consumes.
 *
 * Extracted verbatim from ModuleKnowledgeIndex's companion (behaviour-preserving) so the
 * index file keeps only build orchestration + BM25 search; all corpus-JSON shape handling
 * (nested-vs-flat locale maps, TipTap bodies, synonym buckets, source-page anchors) lives
 * here. Tolerant by design: any missing key / wrong shape / unparseable JSON degrades to
 * empty rather than throwing.
 */
internal object ModuleCorpusParser {

    private val json = com.medtroniclabs.microcoaching.util.LenientJson

    /** Cards with shorter bodies are header stubs — exclude from BM25 recall (BRAC Q81). */
    private const val MIN_INDEXABLE_BODY_LEN = 20

    fun hasIndexableBody(bodyBn: String?, bodyEn: String?): Boolean {
        val bnLen = bodyBn?.trim()?.length ?: 0
        val enLen = bodyEn?.trim()?.length ?: 0
        return maxOf(bnLen, enLen) >= MIN_INDEXABLE_BODY_LEN
    }

    /** Structured view over a module's `search_metadata`, split by index field + language. */
    data class ParsedMetadata(
        val keywordsEn: List<String>,   // keywords_en + topic_tags + clinical_conditions
        val keywordsBn: List<String>,   // keywords_bn
        val phrasesEn: List<String>,    // search_phrases_en (QUESTION field)
        val phrasesBn: List<String>,    // search_phrases_bn (QUESTION field)
        val synonyms: Map<String, List<String>>, // synonyms_en → query expansion (NOT indexed)
    ) {
        companion object {
            val EMPTY = ParsedMetadata(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
        }
    }

    /**
     * Parse `search_metadata` into KEYWORD streams (recall hints), QUESTION streams
     * (natural-language phrases), and the `synonyms_en` map (routed to query
     * expansion, never indexed). `audience`/`rationale`/`schema_version` ignored.
     * Any missing key, wrong shape, or unparseable JSON degrades to [ParsedMetadata.EMPTY].
     */
    fun parseSearchMetadata(jsonText: String): ParsedMetadata {
        val obj = runCatching { json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
            ?: return ParsedMetadata.EMPTY
        // v3 nests these per-language (`topic_tags: {bn:[...], en:[...]}`); older
        // payloads shipped a bare `topic_tags: [...]` array. localizedStrArr reads
        // the nested form and falls back to the flat `topic_tags_<lang>` key, so
        // both shapes index correctly.
        return ParsedMetadata(
            keywordsEn = obj.localizedStrArr("keywords", "en") +
                obj.localizedStrArr("topic_tags", "en") +
                obj.localizedStrArr("clinical_conditions", "en"),
            keywordsBn = obj.localizedStrArr("keywords", "bn") +
                obj.localizedStrArr("topic_tags", "bn") +
                obj.localizedStrArr("clinical_conditions", "bn"),
            phrasesEn = obj.localizedStrArr("search_phrases", "en"),
            phrasesBn = obj.localizedStrArr("search_phrases", "bn"),
            synonyms = obj.synonymMap(),
        )
    }

    /**
     * Optional per-card clinician-approved index hints carried as
     * `"retrieval_metadata": {"keywords": [...], "aliases": [...], "concepts": [...]}`.
     * Absent in current production content (folded into the KEYWORD field for any
     * legacy module that still ships it). Empty when the card has no metadata.
     */
    data class RetrievalMetadata(
        val keywords: List<String>,
        val aliases: List<String>,
        val concepts: List<String>,
    ) {
        val allTerms: List<String> = keywords + aliases + concepts

        companion object {
            val EMPTY = RetrievalMetadata(emptyList(), emptyList(), emptyList())
        }
    }

    /**
     * Per-card `search_metadata` from the backend generator. Top-level keys are
     * supported for fixture overlays; production payloads nest under `search_metadata`.
     *
     * Field routing:
     *  - `retrieval_hints_*` + `questions_*` → QUESTION (×2.5)
     *  - `keywords_*` → KEYWORD on this card only (×0.5)
     *  - `synonyms_en` → query expansion (not indexed)
     */
    data class CardSearchMetadata(
        val hintsEn: List<String>,
        val hintsBn: List<String>,
        val questionsEn: List<String>,
        val questionsBn: List<String>,
        val keywordsEn: List<String>,
        val keywordsBn: List<String>,
        val synonyms: Map<String, List<String>>,
    ) {
        val hasSearchableContent: Boolean
            get() = hintsEn.isNotEmpty() || hintsBn.isNotEmpty() ||
                questionsEn.isNotEmpty() || questionsBn.isNotEmpty() ||
                keywordsEn.isNotEmpty() || keywordsBn.isNotEmpty() ||
                synonyms.isNotEmpty()

        companion object {
            val EMPTY = CardSearchMetadata(
                emptyList(), emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyMap(),
            )
        }
    }

    fun extractCardChunks(
        module: ModuleEntity,
    ): List<Triple<GroundingChunk, RetrievalMetadata, CardSearchMetadata>> {
        val arr = runCatching {
            json.parseToJsonElement(module.cardsJson).jsonArray
        }.getOrNull() ?: return emptyList()
        return arr.mapIndexedNotNull { idx, el ->
            val obj = el as? JsonObject ?: return@mapIndexedNotNull null
            val title = obj.readLocalized("title")
            val titleBn = title.bn
            val titleEn = title.en
            val bodyBn = obj.localizedBodyPlain("body", "bn")
            val bodyEn = obj.localizedBodyPlain("body", "en")
            if (titleBn.isNullOrBlank() && bodyBn.isNullOrBlank() &&
                titleEn.isNullOrBlank() && bodyEn.isNullOrBlank()
            ) return@mapIndexedNotNull null
            if (!hasIndexableBody(bodyBn, bodyEn)) {
                return@mapIndexedNotNull null
            }
            val chunk = GroundingChunk(
                source = GroundingChunk.Source.CARD,
                moduleFamilyId = module.moduleFamilyId,
                positionalId = idx,
                titleEn = titleEn,
                bodyEn = bodyEn,
                titleBn = titleBn,
                bodyBn = bodyBn,
                score = 0f,
                sourcePages = obj.sourcePageRefs(),
            )
            Triple(chunk, obj.retrievalMetadata(), obj.cardSearchMetadata())
        }
    }

    private fun JsonObject.cardSearchMetadata(): CardSearchMetadata {
        val nested = this["search_metadata"] as? JsonObject
        return CardSearchMetadata(
            hintsEn = mergedLocalized("retrieval_hints", "en"),
            hintsBn = mergedLocalized("retrieval_hints", "bn"),
            questionsEn = mergedLocalized("questions", "en"),
            questionsBn = mergedLocalized("questions", "bn"),
            keywordsEn = mergedLocalized("keywords", "en"),
            keywordsBn = mergedLocalized("keywords", "bn"),
            synonyms = nested.synonymMap(),
        )
    }

    private fun JsonObject.mergedLocalized(baseKey: String, lang: String): List<String> =
        localizedStrArr(baseKey, lang).ifEmpty {
            (this["search_metadata"] as? JsonObject)?.localizedStrArr(baseKey, lang).orEmpty()
        }

    private fun JsonObject.localizedStrArr(baseKey: String, lang: String): List<String> =
        readLocalizedArray(baseKey, lang).ifEmpty { strArr("${baseKey}_$lang") }

    private fun JsonObject.strArr(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            ?: emptyList()

    /**
     * Term → expansion(s) for query expansion. Reads the v3 nested
     * `synonyms: {bn: {term: tr}, en: {term: tr}}` — merging every language
     * bucket — and falls back to the legacy flat `synonyms_en: {term: tr}`.
     * `ingest` recurses one level so a language-bucketed map and a flat map
     * both work.
     */
    private fun JsonObject?.synonymMap(): Map<String, List<String>> {
        val obj = this ?: return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        fun ingest(node: JsonObject?) {
            node?.forEach { (k, v) ->
                when (v) {
                    is JsonObject -> ingest(v) // language bucket → recurse one level
                    is JsonPrimitive -> {
                        val key = k.takeIf { it.isNotBlank() } ?: return@forEach
                        val value = v.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
                        out.getOrPut(key) { mutableListOf() }.add(value)
                    }
                    else -> {}
                }
            }
        }
        ingest(obj["synonyms"] as? JsonObject)
        ingest(obj["synonyms_en"] as? JsonObject)
        return out
    }

    private fun JsonObject.retrievalMetadata(): RetrievalMetadata {
        val obj = this["retrieval_metadata"] as? JsonObject ?: return RetrievalMetadata.EMPTY
        return RetrievalMetadata(
            keywords = obj.strArr("keywords"),
            aliases = obj.strArr("aliases"),
            concepts = obj.strArr("concepts"),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /**
     * Parse a card's `source_pages` into (document, page) anchors, keeping the
     * `source_document_id` so chat attribution can cite the exact document the card
     * was authored from. The current backend ships objects —
     * `[{"source_document_id":…,"page_number":159}, …]`; older payloads used a bare
     * int array `[159, 160]` (document id null). Returns null when absent, not an
     * array, or yielding no positive pages; non-positive entries are dropped.
     */
    private fun JsonObject.sourcePageRefs(): List<GroundingChunk.SourcePageRef>? =
        (this["source_pages"] as? JsonArray)
            ?.mapNotNull { el ->
                when (el) {
                    is JsonObject -> {
                        val page = (el["page_number"] as? JsonPrimitive)?.intOrNull
                            ?: return@mapNotNull null
                        if (page <= 0) return@mapNotNull null
                        val docId = (el["source_document_id"] as? JsonPrimitive)?.contentOrNull
                        GroundingChunk.SourcePageRef(sourceDocumentId = docId, pageNumber = page)
                    }
                    is JsonPrimitive -> el.intOrNull?.takeIf { it > 0 }
                        ?.let { GroundingChunk.SourcePageRef(sourceDocumentId = null, pageNumber = it) }
                    else -> null
                }
            }
            ?.takeIf { it.isNotEmpty() }

    /**
     * Extract a body field as plain, index-ready prose. Supports nested locale
     * maps (`body: {bn: [...], en: [...]}`), legacy flat keys, markdown strings,
     * and TipTap block arrays.
     */
    private fun JsonObject.localizedBodyPlain(baseKey: String, lang: String): String? {
        readLocalizedBody(baseKey, lang)?.let { raw ->
            return bodyToPlainText(raw).ifBlank { null }
        }
        return bodyText("${baseKey}_$lang")
    }

    /**
     * Extract a legacy flat body field as plain, index-ready prose. The field may be a markdown
     * string or a TipTap/ProseMirror block array — [bodyToPlainText] strips markup so
     * the BM25 index never sees node types, attrs, or media URLs. A bare [JsonObject]
     * (single block) is wrapped in `[…]` so the TipTap parser accepts it.
     */
    private fun JsonObject.bodyText(key: String): String? {
        val raw = when (val el = this[key]) {
            null -> return null
            is JsonPrimitive -> el.contentOrNull
            is JsonObject -> "[${el}]"
            else -> el.toString()
        } ?: return null
        return bodyToPlainText(raw).ifBlank { null }
    }
}
