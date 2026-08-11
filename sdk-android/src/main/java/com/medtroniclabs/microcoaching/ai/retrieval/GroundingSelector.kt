package com.medtroniclabs.microcoaching.ai.retrieval

/**
 * Shared grounding selection for both offline chat paths.
 *
 * Retrieval anchors on the BN index (module content is Bengali-first) and also
 * consults the EN index — merging the two hit lists — when the native query carries
 * Latin clinical signal or a distinct English query is supplied. Both language modes
 * drive it the same way: Bangla passes the typed BN text as the native query; English
 * passes an EN→BN translation as the native query with the original English as the
 * cross query. Retrieval only; the caller renders the final answer in the app's
 * configured language.
 */
object GroundingSelector {

    data class Selection(
        val hits: List<GroundingChunk>,
        val nativeHits: List<GroundingChunk>,
        val englishHits: List<GroundingChunk>,
        val chosenLabel: String,
    ) {
        val primary: GroundingChunk? get() = hits.firstOrNull()
    }

    fun select(
        nativeQuery: String,
        englishQuery: String?,
        nativeLanguage: ModuleKnowledgeIndex.Lang,
        index: ModuleKnowledgeIndex,
        k: Int,
        scoreThreshold: Float,
    ): Selection {
        // Wider recall only when the EN index is consulted; pure-BN queries keep native BM25 order.
        val runEnglishSearch =
            nativeLanguage == ModuleKnowledgeIndex.Lang.BN &&
                (
                    BanglaTokenizer.hasLatinClinicalSignal(nativeQuery) ||
                        (!englishQuery.isNullOrBlank() && englishQuery != nativeQuery)
                    )
        val recallK = if (runEnglishSearch) maxOf(k, 5) else k
        val nativeHits = index.search(
            nativeQuery,
            k = recallK,
            scoreThreshold = scoreThreshold,
            language = nativeLanguage,
        )
        val englishHits = if (runEnglishSearch) {
            index.search(
                englishQuery?.takeIf { it.isNotBlank() } ?: nativeQuery,
                k = recallK,
                scoreThreshold = scoreThreshold,
                language = ModuleKnowledgeIndex.Lang.EN,
            )
        } else {
            emptyList()
        }

        val merged = applyNativeAnchor(
            mergeAndRerank(nativeHits, englishHits, nativeQuery, runEnglishSearch),
            nativeHits,
            nativeQuery,
        )
        val chosenLabel = when {
            merged.isEmpty() -> "none"
            englishHits.isEmpty() -> "native"
            merged.firstOrNull()?.chunkId == englishHits.firstOrNull()?.chunkId -> "merged (english helped)"
            merged.firstOrNull()?.chunkId == nativeHits.firstOrNull()?.chunkId -> "merged (native stayed top)"
            else -> "merged"
        }
        return Selection(
            hits = merged.take(k),
            nativeHits = nativeHits,
            englishHits = englishHits,
            chosenLabel = chosenLabel,
        )
    }

    private fun mergeAndRerank(
        nativeHits: List<GroundingChunk>,
        englishHits: List<GroundingChunk>,
        nativeQuery: String,
        runEnglishSearch: Boolean,
    ): List<GroundingChunk> {
        if (!runEnglishSearch) {
            return nativeHits
        }
        val merged = LinkedHashMap<String, GroundingChunk>()
        for (hit in nativeHits + englishHits) {
            val prev = merged[hit.chunkId]
            if (prev == null || hit.score > prev.score) {
                merged[hit.chunkId] = hit
            }
        }
        val queryTokens = BanglaTokenizer.tokenizeQuery(nativeQuery).toSet()
        val referralScaffold = hasReferralScaffoldQuery(nativeQuery)
        return merged.values
            .sortedWith(
                compareByDescending<GroundingChunk> {
                    scoreWithSiblingBoost(it, merged.values, queryTokens, nativeQuery, referralScaffold)
                }
                    .thenByDescending { it.score }
            )
    }

    /**
     * When merge/rerank demotes a native hit with stronger title match, restore it.
     * Protects pure-Bengali PNC/FP queries where duplicate modules confuse cross-index merge.
     */
    private fun applyNativeAnchor(
        merged: List<GroundingChunk>,
        nativeHits: List<GroundingChunk>,
        nativeQuery: String,
    ): List<GroundingChunk> {
        val nativeTop = nativeHits.firstOrNull() ?: return merged
        val mergedTop = merged.firstOrNull() ?: return merged
        if (nativeTop.chunkId == mergedTop.chunkId) return merged
        val queryTokens = BanglaTokenizer.tokenizeQuery(nativeQuery).toSet()
        val nativeOverlap = titleOverlap(nativeTop, queryTokens)
        val mergedOverlap = titleOverlap(mergedTop, queryTokens)
        if (
            nativeOverlap >= mergedOverlap + 0.08f &&
            nativeTop.score >= mergedTop.score * 0.45f
        ) {
            return listOf(nativeTop) + merged.filter { it.chunkId != nativeTop.chunkId }
        }
        return merged
    }

    private val REFERRAL_SCAFFOLD_SIGNALS = setOf(
        "refer", "referral", "রেফার", "জরুরি", "urgent", "emergency", "pnc", "পিএনসি",
    )

    private fun hasReferralScaffoldQuery(query: String): Boolean {
        val lowered = query.lowercase()
        return REFERRAL_SCAFFOLD_SIGNALS.any { term ->
            if (term.all { it.code < 128 }) lowered.contains(term) else query.contains(term)
        }
    }

    private fun scoreWithSiblingBoost(
        hit: GroundingChunk,
        allHits: Collection<GroundingChunk>,
        queryTokens: Set<String>,
        nativeQuery: String,
        referralScaffold: Boolean,
    ): Float {
        var boosted = hit.score
        val siblings = allHits.filter { it.moduleFamilyId == hit.moduleFamilyId }
        if (siblings.size > 1) {
            boosted += titleOverlap(hit, queryTokens) * 12f
            boosted += lexicalAlignment(hit, queryTokens) * 8f
        }
        if (referralScaffold) {
            boosted += referralScaffoldBoost(hit, nativeQuery, queryTokens)
        }
        return boosted
    }

    /**
     * For referral / PNC scaffold queries, favour cards whose titles match urgent-referral
     * timing over generic gap/checklist siblings from duplicate PNC modules.
     */
    private fun referralScaffoldBoost(
        hit: GroundingChunk,
        query: String,
        queryTokens: Set<String>,
    ): Float {
        var boost = 0f
        val title = listOfNotNull(hit.titleBn, hit.titleEn).joinToString(" ").lowercase()
        val queryLower = query.lowercase()
        val wantsPnc = queryLower.contains("pnc") || query.contains("পিএনসি") ||
            query.contains("পোস্টনেটাল") || query.contains("প্রসবোত্তর")
        val wantsUrgent = queryWantsUrgentReferral(query, queryLower)

        if (wantsPnc) {
            if (
                title.contains("pnc") || title.contains("পিএনসি") ||
                    title.contains("পোস্টনেটাল") || title.contains("প্রসবোত্তর")
            ) {
                boost += 45f
            }
            if (title.contains("anc") || title.contains("এন্টিনেটাল") || title.contains("antenatal")) {
                boost -= 60f
            }
        }
        if (wantsUrgent) {
            if (titleIndicatesUrgentReferral(title)) {
                boost += 40f
            } else if (titleIndicatesNonUrgentReferral(title)) {
                boost -= 70f
            }
        }
        boost += titleOverlap(hit, queryTokens) * 25f
        return boost
    }

    private fun queryWantsUrgentReferral(query: String, queryLower: String): Boolean {
        if (query.contains("নন-জরুরি") || queryLower.contains("non-urgent") || queryLower.contains("non urgent")) {
            return false
        }
        return query.contains("জরুরি") || queryLower.contains("urgent") || queryLower.contains("emergency")
    }

    private fun titleIndicatesNonUrgentReferral(titleLower: String): Boolean =
        titleLower.contains("নন-জরুরি") ||
            titleLower.contains("non-urgent") ||
            titleLower.contains("non urgent") ||
            titleLower.contains("gaps") ||
            titleLower.contains("ঘাটতি")

    private fun titleIndicatesUrgentReferral(titleLower: String): Boolean {
        if (titleIndicatesNonUrgentReferral(titleLower)) return false
        return titleLower.contains("জরুরি") ||
            titleLower.contains("urgent") ||
            titleLower.contains("emergency")
    }

    private fun titleOverlap(hit: GroundingChunk, queryTokens: Set<String>): Float {
        if (queryTokens.isEmpty()) return 0f
        val title = listOfNotNull(hit.titleBn, hit.titleEn).joinToString(" ")
        val titleTokens = BanglaTokenizer.tokenizeQuery(title).toSet()
        if (titleTokens.isEmpty()) return 0f
        return queryTokens.intersect(titleTokens).size.toFloat() / queryTokens.size.toFloat()
    }

    private fun lexicalAlignment(hit: GroundingChunk, queryTokens: Set<String>): Float {
        if (queryTokens.isEmpty()) return 0f
        val text = listOfNotNull(hit.titleBn, hit.titleEn, hit.bodyBn, hit.bodyEn).joinToString(" ")
        val hitTokens = BanglaTokenizer.tokenizeQuery(text).toSet()
        if (hitTokens.isEmpty()) return 0f
        return queryTokens.intersect(hitTokens).size.toFloat() / queryTokens.size.toFloat()
    }
}
