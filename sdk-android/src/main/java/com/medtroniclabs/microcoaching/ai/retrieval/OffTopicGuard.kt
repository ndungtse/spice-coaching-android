package com.medtroniclabs.microcoaching.ai.retrieval

/**
 * Relevance guards for the offline retrieval paths (`ChatViewModel`'s
 * `handleLocalGemmaMessage` and `handleRetrievalOnlyMessage`).
 *
 * Two checks with different strictness:
 *  - [isClearlyUnanswerable] / [shouldRefuseLowEnd] — a deliberately loose backstop
 *    against BM25 latching onto a stop-word and returning a hit with *zero* clinical
 *    overlap. Any single shared clinical term passes it.
 *  - [sharesTopicalTerm] — the question↔evidence check applied at the serve decision.
 *    Stricter, because demographic words ("গর্ভবতী", "মহিলা") are shared by a large part
 *    of a maternal-health corpus and are not evidence that a card answers anything.
 *
 * `clinicalTerms` is the same gazetteer [ScopeClassifier] uses — static seed terms plus
 * module domain / subdomain / title words harvested at build — so the guard's vocabulary
 * stays in lockstep with what the scope filter considers in-scope.
 */
object OffTopicGuard {

    /**
     * Score on hit[0] above which [shouldRefuseLowEnd] serves without consulting the
     * clinical-overlap check.
     *
     * The threshold is not calibrated to the current corpus: field-weighted scores on
     * real questions run far above it, so in practice this bypass is always taken and
     * the overlap check never runs. [sharesTopicalTerm], applied at the serve decision,
     * is what actually screens an irrelevant card today.
     */
    const val CONFIDENT_RETRIEVAL_MIN_SCORE = 80f

    /**
     * True when hit[0] clears [minScore]. Used on the UC3 low-end path to avoid
     * refusing questions BM25 answered confidently but the overlap heuristic missed.
     */
    fun hasConfidentTopHit(
        hits: List<GroundingChunk>,
        minScore: Float = CONFIDENT_RETRIEVAL_MIN_SCORE,
    ): Boolean = hits.firstOrNull()?.score?.let { it >= minScore } == true

    /** Bodies shorter than this are treated as stubs/headers, not full answers. */
    private const val STUB_BODY_MAX_LEN = 80

    /** Minimum body length for a promoted alternative to a stub top hit. */
    private const val SUBSTANTIVE_BODY_MIN_LEN = 60

    /**
     * Short-but-complete answers (e.g. "What is hypertension?" definitions) may be
     * under [STUB_BODY_MAX_LEN]; still serve them when BM25 rank-1 aligns with the query.
     */
    private const val MIN_ALIGNED_SERVE_BODY_LEN = 40

    /** A later hit must retain at least this fraction of hit[0]'s BM25 score to be promoted. */
    private const val PROMOTE_MIN_SCORE_RATIO = 0.70f

    /** Trust BM25 rank-1 when it leads #2 by at least this margin (same-module siblings). */
    private const val CLEAR_WINNER_SCORE_RATIO = 1.10f

    /** Stub-body promotion allows a wider score gap — stubs often rank artificially high. */
    private const val STUB_PROMOTE_MIN_SCORE_RATIO = 0.55f

    private val REFERRAL_SIGNALS = setOf(
        "refer", "referral", "রেফার", "বিপদ", "danger", "emergency", "জরুরি", "urgent",
    )

    /**
     * Low-end refusal gate. Refuses on empty hits or when overlap guard fires on a
     * weak top score. A confident top hit is served without overlap re-ranking.
     */
    fun shouldRefuseLowEnd(
        query: String,
        hits: List<GroundingChunk>,
        clinicalTerms: Set<String>,
        confidentMinScore: Float = CONFIDENT_RETRIEVAL_MIN_SCORE,
    ): Boolean {
        if (hits.isEmpty()) return true
        if (hasConfidentTopHit(hits, confidentMinScore)) return false
        return isClearlyUnanswerable(query, hits, clinicalTerms)
    }

    /**
     * Pick which top-k hit to serve on the UC3 low-end path.
     *
     * Defaults to BM25 rank-1, promoting a later hit only when hit[0] is clearly
     * misaligned: a referral-timing question against a facility-services card, a stub
     * body, or no title overlap. Generic clinical-token overlap alone is not enough to
     * demote the BM25 winner.
     */
    fun selectLowEndServeHit(
        query: String,
        hits: List<GroundingChunk>,
        clinicalTerms: Set<String>,
    ): GroundingChunk? {
        if (hits.isEmpty()) return null
        val top = hits.first()
        if (hits.size == 1) return top

        if (hasReferralTimingIntent(query)) {
            val referralBest = hits.maxBy { referralTimingScore(it) }
            if (
                referralBest != top &&
                referralTimingScore(referralBest) >= referralTimingScore(top) + 2 &&
                referralBest.score >= top.score * PROMOTE_MIN_SCORE_RATIO
            ) {
                return referralBest
            }
        }

        // Trust rank-1 when it aligns on title or clinical terms; short definition
        // cards are legitimate answers even though their bodies are brief.
        if (
            hasQueryAlignment(query, top, clinicalTerms) &&
            substantiveBodyLength(top) >= MIN_ALIGNED_SERVE_BODY_LEN
        ) {
            return top
        }

        if (isStubLikeBody(top)) {
            hits.drop(1)
                .filter { !isStubLikeBody(it) && substantiveBodyLength(it) >= SUBSTANTIVE_BODY_MIN_LEN }
                .filter { it.score >= top.score * STUB_PROMOTE_MIN_SCORE_RATIO }
                .maxByOrNull { titleTokenOverlap(query, it) }
                ?.let { return it }
        }

        if (!hasQueryAlignment(query, top, clinicalTerms)) {
            val topClinical = clinicalTermsIn(query, clinicalTerms)
            if (topClinical.isNotEmpty() && clinicalOverlap(topClinical, top, clinicalTerms) == 0) {
                hits.drop(1)
                    .filter {
                        clinicalOverlap(topClinical, it, clinicalTerms) > 0 &&
                            it.score >= top.score * PROMOTE_MIN_SCORE_RATIO
                    }
                    .maxByOrNull { clinicalOverlap(topClinical, it, clinicalTerms) }
                    ?.let { return it }
            }
        }

        val second = hits[1]
        if (
            !isStubLikeBody(top) &&
            top.score >= second.score * CLEAR_WINNER_SCORE_RATIO &&
            substantiveBodyLength(top) >= MIN_ALIGNED_SERVE_BODY_LEN
        ) {
            return top
        }

        return top
    }

    /**
     * True when the retrieved set is so off-topic relative to [query] that we should
     * refuse rather than answer from it.
     *
     * We intentionally inspect the whole top-k list, not just rank 1. A wrong first hit
     * should not force a refusal when hit 2 or 3 is clinically relevant.
     */
    fun isClearlyUnanswerable(
        query: String,
        hits: List<GroundingChunk>,
        clinicalTerms: Set<String>,
    ): Boolean {
        if (hits.isEmpty()) return true
        val queryClinical = clinicalTermsIn(query, clinicalTerms)
        if (queryClinical.isEmpty()) return false
        return bestMatchingHit(queryClinical, hits, clinicalTerms) == null
    }

    fun bestMatchingHit(
        query: String,
        hits: List<GroundingChunk>,
        clinicalTerms: Set<String>,
    ): GroundingChunk? {
        if (hits.isEmpty()) return null
        val queryClinical = clinicalTermsIn(query, clinicalTerms)
        if (queryClinical.isEmpty()) return hits.firstOrNull()
        return bestMatchingHit(queryClinical, hits, clinicalTerms)
    }

    /**
     * Best hit we are willing to serve as a fallback answer. This is stricter than
     * [bestMatchingHit]: the hit must both overlap clinically and clear a minimum
     * retrieval score, otherwise the caller should refuse instead of surfacing an
     * irrelevant weak match.
     */
    fun bestFallbackHit(
        query: String,
        hits: List<GroundingChunk>,
        clinicalTerms: Set<String>,
        minScore: Float,
    ): GroundingChunk? =
        bestMatchingHit(query, hits, clinicalTerms)?.takeIf { it.score >= minScore }

    /**
     * Who the question is about rather than what it is about. These words are shared by a
     * large share of a maternal-health corpus — "গর্ভবতী" alone appears in roughly a
     * quarter of card chunks — so a card matching only these has not been shown to be
     * about the question at all. Measured: every wrong card served in the August audit
     * overlapped the question ONLY on words from this set, while every correct one also
     * shared a topical term (ম্যালেরিয়া, যক্ষ্মা, 140/90, পানি).
     */
    private val DEMOGRAPHIC_TERMS = setOf(
        // Bangla
        "গর্ভবতী", "গর্ভবতীর", "মহিলা", "মহিলার", "মহিলাদের", "নারী", "নারীর",
        "মা", "মায়ের", "মাকে", "রোগী", "রোগীর", "শিশু", "শিশুর", "শিশুকে",
        "ব্যক্তি", "ব্যক্তির", "লোক", "মানুষ",
        // English (English-mode queries carry both sides into the guard query)
        "pregnant", "woman", "women", "mother", "mothers", "patient", "patients",
        "child", "children", "baby", "person", "people", "lady",
    )

    /**
     * True when [query] and [hit] share at least one TOPICAL word — a content word that
     * is not merely demographic. This is the question↔evidence check the rest of the
     * validation stack lacks: groundedness and the L4 validator both compare the ANSWER
     * to the references and therefore pass a faithful summary of the wrong card.
     *
     * Deliberately permissive: one shared topical word is enough. It is meant to catch
     * the "diet question answered with an anemia card" class, where the only thing the
     * question and the card have in common is that both mention pregnant women.
     */
    fun sharesTopicalTerm(query: String, hit: GroundingChunk): Boolean {
        val queryTopical = topicalTokens(query)
        if (queryTopical.isEmpty()) return true // nothing discriminating was asked — don't block
        val hitText = listOfNotNull(hit.titleBn, hit.bodyBn, hit.titleEn, hit.bodyEn)
            .joinToString(" ")
        if (hitText.isBlank()) return false
        val hitTokens = BanglaTokenizer.tokenize(hitText).toSet()
        return queryTopical.any { it in hitTokens }
    }

    /**
     * Query content words minus demographics. Stop-words are already dropped by
     * [BanglaTokenizer.tokenizeQuery]; the character bigrams it also emits are dropped
     * here, because a two-character Bangla fragment matches nearly every card and would
     * make the check vacuous.
     */
    private fun topicalTokens(query: String): Set<String> =
        BanglaTokenizer.tokenizeQuery(query)
            .filterNotTo(mutableSetOf()) { token ->
                token in DEMOGRAPHIC_TERMS || token.isBanglaCharBigram()
            }

    /** A two-character all-Bangla token is the tokenizer's morphology bigram, not a word. */
    private fun String.isBanglaCharBigram(): Boolean =
        length == 2 && all { it.code in 0x0980..0x09FF }

    /**
     * Subset of [clinicalTerms] that is genuinely present in [text]. Multi-word terms
     * are matched as phrases (substring on the full lowercased text); single-word
     * terms are matched against the tokenizer's output to avoid the "to" ⊂ "stool"
     * class of false positive that ClinicalSynonymMap's prior implementation had.
     */
    private fun clinicalTermsIn(text: String, clinicalTerms: Set<String>): Set<String> {
        if (text.isBlank() || clinicalTerms.isEmpty()) return emptySet()
        val lowered = text.lowercase()
        val tokens = BanglaTokenizer.tokenize(text).toSet()
        return clinicalTerms.filterTo(mutableSetOf()) { term ->
            when {
                term.isBlank() -> false
                term.contains(' ') -> lowered.contains(term)
                term.all { it.code < 128 } -> term in tokens
                else -> tokens.any { tok -> tok.startsWith(term) }
            }
        }
    }

    private fun bestMatchingHit(
        queryClinical: Set<String>,
        hits: List<GroundingChunk>,
        clinicalTerms: Set<String>,
    ): GroundingChunk? =
        hits.map { hit ->
            hit to clinicalOverlap(queryClinical, hit, clinicalTerms)
        }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<GroundingChunk, Int>> { it.second }.thenByDescending { it.first.score })
            .firstOrNull()
            ?.first

    private fun clinicalOverlap(
        queryClinical: Set<String>,
        hit: GroundingChunk,
        clinicalTerms: Set<String>,
    ): Int {
        val chunkText = listOfNotNull(hit.titleBn, hit.bodyBn, hit.titleEn, hit.bodyEn).joinToString(" ")
        return queryClinical.intersect(clinicalTermsIn(chunkText, clinicalTerms)).size
    }

    private fun hasReferralTimingIntent(query: String): Boolean {
        val lowered = query.lowercase()
        val hasReferral = REFERRAL_SIGNALS.any { term ->
            if (term.all { it.code < 128 }) lowered.contains(term) else query.contains(term)
        }
        // "when" / "কখন" are query stop-words — match on raw text, not tokenizeQuery.
        val hasWhen = Regex("""\bwhen\b""").containsMatchIn(lowered) || query.contains("কখন")
        return hasReferral && hasWhen
    }

    private fun referralTimingScore(hit: GroundingChunk): Int {
        val text = listOfNotNull(hit.titleEn, hit.titleBn, hit.bodyEn, hit.bodyBn)
            .joinToString(" ")
            .lowercase()
        var score = 0
        if ("when to refer" in text) score += 4
        if ("when" in text && "refer" in text) score += 3
        if ("danger" in text || "বিপদ" in text) score += 2
        if ("services at" in text && "when to refer" !in text) score -= 2
        return score
    }

    private fun isStubLikeBody(hit: GroundingChunk): Boolean {
        val body = listOfNotNull(hit.bodyBn, hit.bodyEn).maxByOrNull { it.length }?.trim().orEmpty()
        if (body.length < MIN_ALIGNED_SERVE_BODY_LEN) return true
        if (body.length < STUB_BODY_MAX_LEN && body.endsWith(":")) return true
        if (body.startsWith("<b>") && body.count { it == '.' } <= 1) return true
        return false
    }

    private fun substantiveBodyLength(hit: GroundingChunk): Int =
        maxOf(hit.bodyBn?.length ?: 0, hit.bodyEn?.length ?: 0)

    private fun titleTokenOverlap(query: String, hit: GroundingChunk): Int {
        val queryTokens = BanglaTokenizer.tokenizeQuery(query).toSet()
        if (queryTokens.isEmpty()) return 0
        val title = listOfNotNull(hit.titleBn, hit.titleEn).joinToString(" ")
        val titleTokens = BanglaTokenizer.tokenizeQuery(title).toSet()
        return queryTokens.intersect(titleTokens).size
    }

    /** BM25 rank-1 is trusted when the top hit shares title or clinical tokens with the query. */
    private fun hasQueryAlignment(
        query: String,
        hit: GroundingChunk,
        clinicalTerms: Set<String>,
    ): Boolean {
        if (titleTokenOverlap(query, hit) > 0) return true
        val queryClinical = clinicalTermsIn(query, clinicalTerms)
        return queryClinical.isNotEmpty() && clinicalOverlap(queryClinical, hit, clinicalTerms) > 0
    }
}
