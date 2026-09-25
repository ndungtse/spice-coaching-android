package com.medtroniclabs.microcoaching.ai.retrieval

/**
 * One piece of curriculum content surfaced by [ModuleKnowledgeIndex] as evidence
 * for the LLM's answer.
 *
 * Chunks are built from module cards only. The carrier is intentionally untyped
 * beyond [source] so the validator and refusal layers can treat them
 * uniformly. Both language halves are retained: [referenceText] feeds the LLM
 * prompt (preferring the English side, falling back to Bengali), and the
 * refusal/fallback path serves the card body in the SDK language, translating the
 * other side when only it is present.
 */
data class GroundingChunk(
    val source: Source,
    val moduleFamilyId: String,
    val positionalId: Int,
    val titleEn: String?,
    val bodyEn: String?,
    val titleBn: String?,
    val bodyBn: String?,
    val score: Float,
    /**
     * (document, page) anchors parsed from the card's `source_pages` field on
     * inbound module sync; null when the card has no anchor (or for legacy
     * modules that predate the field). Drives the per-message PDF deep-link in
     * chat — `ChatViewModel.resolveSourceAttribution` reads the first entry to
     * pick BOTH the exact source document and the page, then persists them on
     * the assistant `ChatMessage`.
     */
    val sourcePages: List<SourcePageRef>? = null,
    /**
     * Reserved for a future answer-shaped grounding snippet on the chunk. Not
     * populated by [ModuleKnowledgeIndex] (cards-only index). Kept so tests and
     * the groundedness gate can still exercise explanation-aware scoring when set.
     */
    val explanationEn: String? = null,
    val explanationBn: String? = null,
    /**
     * The card's authored `search_metadata` retrieval hints + questions, carried on
     * the chunk so the serve decision can weigh title/hint evidence without an
     * index handle. High-precision fields: an author wrote these as the questions
     * this card answers, so a topical match here outranks body-keyword density.
     * Empty for legacy cards without metadata. Lists are shared by reference
     * through the per-search `copy(score = …)`, so carrying them is free.
     */
    val hintsBn: List<String> = emptyList(),
    val hintsEn: List<String> = emptyList(),
    val questionsBn: List<String> = emptyList(),
    val questionsEn: List<String> = emptyList(),
    /**
     * The backend's per-card UUID from `/sync/modules` — the join key for the
     * card-embedding vectors synced separately. Null for cards cached before the
     * backend shipped the field; such cards simply never gain a dense vector.
     */
    val cardId: String? = null,
    /**
     * Cosine similarity of this card's synced embedding against the query vector,
     * set during dense/hybrid retrieval. Null when the card was not among the
     * dense candidates (or dense retrieval did not run).
     */
    val denseCos: Float? = null,
) {
    enum class Source { CARD, QUIZ }

    /**
     * One (document, page) anchor from a card's `source_pages[]` entry.
     * [sourceDocumentId] is null only for the legacy bare-int payload shape.
     */
    data class SourcePageRef(
        val sourceDocumentId: String?,
        val pageNumber: Int,
    )

    /** Identifier suitable for telemetry / debugging. Stable per (module, source, position). */
    val chunkId: String = "$moduleFamilyId:${source.name.lowercase()}:$positionalId"

    /** The card's short trace key, `family8:index`, as every stage's trace line prints it. */
    val shortKey: String get() = "${moduleFamilyId.take(8)}:$positionalId"

    /** First positive page number (document id ignored) — for callers that only need a page anchor. */
    val firstPageNumber: Int? get() = sourcePages?.firstOrNull { it.pageNumber > 0 }?.pageNumber

    /** True when a linked quiz explanation is present on either language side. */
    fun hasExplanation(): Boolean = !explanationEn.isNullOrBlank() || !explanationBn.isNullOrBlank()

    /** English text injected into the LLM reference block. Falls back to BN if no EN side. */
    fun referenceText(): String = when {
        !bodyEn.isNullOrBlank() -> {
            val header = titleEn ?: titleBn ?: source.name
            "$header — $bodyEn"
        }
        !bodyBn.isNullOrBlank() -> {
            val header = titleEn ?: titleBn ?: source.name
            "$header — $bodyBn"
        }
        else -> titleEn ?: titleBn ?: ""
    }

    /**
     * The text the prompt builder should ground on. When [explanationEn] or
     * [explanationBn] is set (tests / future use), prefers that concise snippet;
     * otherwise falls back to [referenceText].
     */
    fun groundingSnippet(): String {
        val explanation = explanationEn?.takeIf { it.isNotBlank() }
            ?: explanationBn?.takeIf { it.isNotBlank() }
        if (explanation != null) {
            val header = titleEn ?: titleBn ?: source.name
            return "$header — $explanation"
        }
        return referenceText()
    }
}
