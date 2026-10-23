package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.ServeTuning
import java.util.Locale

/**
 * What a question and each retrieved card share, computed once per turn.
 *
 * The ranker orders candidates by this evidence and the gate judges the one it picks. Both
 * read the same [Evidence], so a card is never judged on facts the ranker did not see.
 *
 * Evidence is:
 *  1. condition terms — gazetteer terms present in both question and card, excluding
 *     demographics ([DEMOGRAPHIC_TERMS]), question-form and filler words
 *     ([TEMPLATE_TERMS]), and numbers/units. Bangla matches by containment so an
 *     inflected compound counts against its base term ("উচ্চরক্তচাপের" ⊃ "রক্তচাপ").
 *  2. shared synonym concepts ([ClinicalSynonymMap.conceptsFor]), which bridge
 *     transliteration variants (এডিমা↔ইডিমা) and BN↔EN vocabulary.
 *
 * A term-rich question needs two matches, a terse one needs a single match: matching
 * a long question on one word is how an unrelated card wins on shared vocabulary.
 *
 * Counter-evidence overrides both: a card whose title/hints scope it to a population
 * (maternal, child, adult) is not servable for a question that names a different
 * population. A question naming no population may still receive a scoped card, but only
 * on two shared topic terms or a dominant cosine, never on one shared word.
 */
object CardEvidence {

    /** Why a hit is (or is not) servable — logged and surfaced in the retrieval lab. */
    data class Evidence(
        val chunkId: String,
        val score: Float,
        /** Shared non-demographic, non-template condition words/gazetteer terms. */
        val conditionTerms: Set<String>,
        /** Shared [ClinicalSynonymMap] concept groups (e.g. "edema"). */
        val sharedConcepts: Set<String>,
        /** How many of the above were found in the card's title/hints/questions. */
        val titleHintOverlap: Int,
        /** Card is population-scoped and the question names a different population. */
        val populationVeto: Boolean,
        /** Card is population-scoped and the question names no population at all. */
        val populationUnstated: Boolean,
        /**
         * How many terms the question offered that name a subject rather than a
         * population — the denominator the match is judged against.
         */
        val queryTopicCount: Int,
        /**
         * Matched evidence left after discounting terms and concepts that only say WHO the
         * card is for or WHEN ([POPULATION_GROUPS], [POPULATION_CONCEPTS]). Zero means the
         * hit and the question share a population and nothing else.
         */
        val topicTermCount: Int,
        /** Cosine of the card's synced embedding against the query embedding; null without a vector. */
        val denseCos: Float? = null,
        /** True when [denseCos] reaches the tuned floor — semantic agreement strong enough to serve on. */
        val denseAgrees: Boolean = false,
        /**
         * True for the one candidate whose cosine both clears
         * [ServeTuning.cosDominantFloor] and leads the runner-up by
         * [ServeTuning.cosDominantMargin] — semantic agreement clear enough to
         * outrank authored title/hint overlap rather than merely break its ties.
         */
        val denseDominant: Boolean = false,
    ) {
        /**
         * A question that names a subject must be answered by a card about that
         * subject: population agreement alone is not enough. A term-rich question
         * needs two subject matches, since matching a long question on one word is
         * how an unrelated card wins on shared vocabulary.
         *
         * A question that names no subject at all ("what advice for the mother in the
         * postpartum period?") has only its population to match on, so there a
         * population match is the strongest evidence available.
         */
        val servable: Boolean
            get() {
                if (populationVeto) return false
                // Dense agreement is meaning-level evidence: the card's embedding and the
                // query's agree even when they share no words. It never overrides the
                // population veto above.
                if (denseDominant) return true
                // A card written for a population, asked about by a question that names
                // none: one shared word is not enough to hand it over. It takes two, or the
                // encoder's clear verdict above.
                if (populationUnstated) return topicTermCount >= 2
                if (denseAgrees) return true
                if (queryTopicCount == 0) return conditionTerms.isNotEmpty() || sharedConcepts.isNotEmpty()
                return topicTermCount >= if (queryTopicCount >= 3) 2 else 1
            }

        fun describe(): String =
            "$chunkId score=${"%.1f".format(Locale.US, score)} " +
                "terms=$conditionTerms concepts=$sharedConcepts titleHint=$titleHintOverlap" +
                (if (topicTermCount == 0) " population-only" else "") +
                (if (populationVeto) " POPULATION-VETO" else "") +
                (if (populationUnstated) " population-unstated" else "") +
                (denseCos?.let { " cos=${"%.2f".format(Locale.US, it)}" } ?: "") +
                (if (denseDominant) " cos-dominant" else "")
    }


    /**
     * Who the question is about rather than what it is about. A quarter of a
     * maternal-health corpus mentions "গর্ভবতী", so a card matching only these has
     * not been shown to answer anything. Both languages are listed because the guard
     * query carries the typed text and its translation.
     */
    internal val DEMOGRAPHIC_TERMS = setOf(
        // Bangla
        "গর্ভবতী", "গর্ভবতীর", "মহিলা", "মহিলার", "মহিলাদের", "নারী", "নারীর",
        "মা", "মায়ের", "মাকে", "রোগী", "রোগীর", "শিশু", "শিশুর", "শিশুকে",
        "ব্যক্তি", "ব্যক্তির", "লোক", "মানুষ", "স্বাস্থ্যকর্মী", "সেবাগ্রহীতা", "সেবাগ্রহীতার",
        // English
        "pregnant", "woman", "women", "mother", "mothers", "patient", "patients",
        "child", "children", "baby", "person", "people", "lady",
    )

    /**
     * Words that describe the FORM of the answer wanted (symptoms of…, prevention
     * of…, advice for…) or are clinical filler common to most of the corpus. They
     * carry no topic, so matching on them alone pairs any question with any card
     * about a different condition — "লক্ষণ" is shared by every symptom card in the
     * corpus regardless of disease.
     */
    internal val TEMPLATE_TERMS = setOf(
        // Bangla question-form
        "লক্ষণ", "লক্ষণগুলো", "লক্ষন", "উপসর্গ", "প্রতিরোধ", "প্রতিরোধে", "করণীয়", "করনীয়",
        "পরামর্শ", "চিকিৎসা", "ওষুধ", "ঔষধ", "সেবা", "যত্ন", "নিয়ম", "কারণ", "কারন",
        "সমস্যা", "তথ্য", "কার্ড", "ফর্ম", "তালিকা", "ব্যবস্থাপনা",
        // Procedure-form: HOW something is done, not WHAT it is about. Without these
        // a "what do I do about high BP" question matches the how-to-measure-BP card.
        "পরিমাপ", "পরীক্ষা", "মূল্যায়ন", "নির্ণয়", "ভিজিটে",
        // Bangla generic clinical filler / near-universal singletons
        "রক্ত", "রক্তে", "উচ্চ", "চাপ", "মাত্রা", "পরিমান", "পরিমাণ", "প্রতিদিন",
        "বয়স", "বয়সী", "বয়সের", "সময়", "সময়ের",
        // Bangla verbal/function words that survive both the stop-word list and the
        // title harvest (module titles contain them verbatim)
        "দিয়ে", "দিয়েছে", "দেওয়া", "নেওয়া", "করা", "করে", "হওয়া", "যাওয়া", "গুরুত্ব", "লেখা",
        // Relational words that place an event in time without naming a subject. They
        // reach the gazetteer through module titles ("প্রসব পরবর্তী …").
        "পরবর্তী", "পূর্ববর্তী", "আগের", "পরের",
        // English question-form
        "symptom", "symptoms", "prevention", "prevent", "prevented", "advice", "advise",
        "advised", "treatment", "treat", "treated", "medicine", "medication", "medications",
        "care", "cause", "causes", "rule", "rules", "recommendation", "recommendations",
        "counsel", "counseling", "counselling", "service", "services", "management",
        "manage", "information", "list", "form",
        "measure", "measurement", "test", "tests", "assess", "assessment", "check", "checkup",
        // English generic clinical filler
        "blood", "pressure", "high", "low", "level", "levels", "age",
    )

    /**
     * Numbers and measurement units. A shared "mmol/L" says both texts mention a lab
     * value, not that they are about the same condition.
     */
    private val UNIT_TERMS = setOf(
        "mmhg", "mmol", "mg", "ml", "kg", "gm", "dl", "iu", "l",
        "মিলিমোল", "লিটার", "মিলি", "গ্রাম", "কেজি",
    )
    private val NUMERIC = Regex("^[0-9./%+-]+$")

    /**
     * Population scopes a card can be written for. Advice scoped to one population is
     * not advice for another: an antenatal blood-pressure card does not answer a
     * question about a general adult client, and a vaccine schedule for women of
     * reproductive age does not answer one about a child's dose.
     */
    private val POPULATION_GROUPS: Map<String, Set<String>> = mapOf(
        "maternal" to setOf(
            "গর্ভ", "গর্ভবতী", "গর্ভকালীন", "প্রসূতি", "প্রসব", "প্রসবোত্তর", "মা", "মায়ের", "মহিলা", "নারী",
            "এএনসি", "পিএনসি",
            "pregnan", "antenatal", "anc", "postpartum", "postnatal", "pnc", "mother", "maternal",
            "woman", "women",
            // The peripartum period named in English, as MLKit renders "প্রসব".
            "delivery", "childbirth", "labour", "labor", "puerperium",
        ),
        "child" to setOf(
            "শিশু", "বাচ্চা", "নবজাতক", "child", "children", "baby", "newborn", "neonat", "infant",
        ),
        // The general adult client, named so that a question about an adult conflicts with
        // a card scoped to mothers or children, and the reverse.
        "adult" to setOf(
            "সেবাগ্রহীতা", "ক্লায়েন্ট", "প্রাপ্তবয়স্ক", "পুরুষ", "বয়স্ক",
            "client", "adult", "man", "elderly",
        ),
    )

    /**
     * Synonym concepts reachable from a population marker: they name a period or a kind of
     * person, so they qualify a card as being about the right people without making it
     * about the right subject. Derived from [POPULATION_GROUPS], so the two never disagree.
     */
    private val POPULATION_CONCEPTS: Set<String> =
        ClinicalSynonymMap.conceptsFor(POPULATION_GROUPS.values.flatten())

    /**
     * Reads the question once and records, for every candidate, the evidence the ranker
     * orders by and the gate judges. Candidates keep their input order.
     */
    fun compute(
        query: String,
        hits: List<GroundingChunk>,
        clinicalTerms: Set<String>,
        tuning: ServeTuning,
    ): List<Evidence> {
        if (hits.isEmpty()) return emptyList()
        val queryTokens = topicalQueryTokens(query)
        // The gazetteer harvests module-title words verbatim, so it holds inflected
        // forms ("কার্ডের", "সময়ের") that an exact-match filter would let through.
        val queryGazetteer = gazetteerTermsIn(query, clinicalTerms)
            .filterNotTo(mutableSetOf()) { term -> isTemplateOrDemographic(term) }
        val queryConcepts = ClinicalSynonymMap.conceptsFor(queryTokens)
        val queryPopulations = populationsIn(query)
        return hits.map { hit ->
            evidenceFor(
                hit, queryTokens, queryGazetteer, queryConcepts, queryPopulations,
                cosFloor = tuning.cosFloor,
            )
        }.let { stampDominance(it, tuning) }
    }

    // ── evidence computation ──────────────────────────────────────────────────

    /**
     * Marks the leading cosine as dominant when it clears
     * [ServeTuning.cosDominantFloor] and leads the runner-up by
     * [ServeTuning.cosDominantMargin].
     *
     * Dominance is a property of the candidate set, not of one hit, so it is resolved
     * here rather than in [evidenceFor]. A single cosine among the candidates has no
     * runner-up to beat and is judged on the floor alone. Candidates without a vector
     * carry no cosine, so on every BM25-only path this returns the list untouched.
     */
    private fun stampDominance(evidences: List<Evidence>, tuning: ServeTuning): List<Evidence> {
        val ranked = evidences
            .filter { it.denseCos != null }
            .sortedByDescending { it.denseCos }
        val leader = ranked.firstOrNull() ?: return evidences
        val leaderCos = leader.denseCos ?: return evidences
        if (leaderCos < tuning.cosDominantFloor) return evidences
        val runnerUpCos = ranked.getOrNull(1)?.denseCos ?: 0f
        if (leaderCos - runnerUpCos < tuning.cosDominantMargin) return evidences
        return evidences.map {
            if (it.chunkId == leader.chunkId) it.copy(denseDominant = true) else it
        }
    }

    private fun evidenceFor(
        hit: GroundingChunk,
        queryTokens: Set<String>,
        queryGazetteer: Set<String>,
        queryConcepts: Set<String>,
        queryPopulations: Set<String>,
        cosFloor: Float,
    ): Evidence {
        val hitText = listOfNotNull(
            hit.titleBn, hit.titleEn, hit.bodyBn, hit.bodyEn,
            hit.hintsBn.joinToString(" ").ifBlank { null },
            hit.hintsEn.joinToString(" ").ifBlank { null },
            hit.questionsBn.joinToString(" ").ifBlank { null },
            hit.questionsEn.joinToString(" ").ifBlank { null },
        ).joinToString(" ")
        val hitTokens = BanglaTokenizer.tokenize(hitText).toSet()

        val titleHintText = listOfNotNull(
            hit.titleBn, hit.titleEn,
            hit.hintsBn.joinToString(" ").ifBlank { null },
            hit.hintsEn.joinToString(" ").ifBlank { null },
            hit.questionsBn.joinToString(" ").ifBlank { null },
            hit.questionsEn.joinToString(" ").ifBlank { null },
        ).joinToString(" ")
        val titleHintTokens = BanglaTokenizer.tokenize(titleHintText).toSet()

        // Evidence is restricted to gazetteer terms and synonym concepts. Any shared
        // word would be too weak a signal: Bangla filler outside the stop-word list
        // (সময়, দেওয়া, হয়েছে) appears on almost every card.
        val conditionTerms = queryGazetteer.filterTo(mutableSetOf()) { termInText(it, hitText, hitTokens) }
        val sharedConcepts = queryConcepts.intersect(ClinicalSynonymMap.conceptsFor(hitTokens))

        // Ranking counts condition terms only. Concept groups are broad by design —
        // "postpartum" spans half the PNC titles — so they qualify a hit as servable
        // without also making it outrank a card that names the actual topic.
        val titleHint = conditionTerms.count { termInText(it, titleHintText, titleHintTokens) }

        // Scope is read from the authored fields, never the body: bodies mention
        // mothers and children in passing on cards that answer anyone.
        // A card written for one population does not answer a question about another. A
        // question that names no population is not "another": it is judged on topic evidence.
        val hitPopulations = populationsIn(titleHintText)
        val veto = hitPopulations.isNotEmpty() &&
            queryPopulations.isNotEmpty() &&
            hitPopulations.intersect(queryPopulations).isEmpty()
        val unstated = hitPopulations.isNotEmpty() && queryPopulations.isEmpty()

        return Evidence(
            chunkId = hit.chunkId,
            score = hit.score,
            conditionTerms = conditionTerms,
            sharedConcepts = sharedConcepts,
            titleHintOverlap = titleHint,
            populationVeto = veto,
            populationUnstated = unstated,
            queryTopicCount = queryGazetteer.count { populationsIn(it).isEmpty() } +
                queryConcepts.count { it !in POPULATION_CONCEPTS },
            topicTermCount = conditionTerms.count { populationsIn(it).isEmpty() } +
                sharedConcepts.count { it !in POPULATION_CONCEPTS },
            denseCos = hit.denseCos,
            denseAgrees = (hit.denseCos ?: 0f) >= cosFloor,
        )
    }


    /**
     * Template words that must match as whole terms only. As substrings they live
     * inside real condition words ("চাপ" and "রক্ত" ⊂ "রক্তচাপ"), so letting them
     * strip by containment would discard the condition the question is about.
     */
    private val WHOLE_TERM_ONLY_TEMPLATE = setOf(
        "রক্ত", "রক্তে", "উচ্চ", "চাপ", "মাত্রা", "পরিমান", "পরিমাণ",
    )

    /**
     * True when [term] is a template or demographic word, or a Bangla inflection of
     * one ("কার্ডের" ⊃ "কার্ড"). ASCII terms match exactly.
     */
    private fun isTemplateOrDemographic(term: String): Boolean {
        if (term in TEMPLATE_TERMS || term in DEMOGRAPHIC_TERMS) return true
        if (term.none { it.code in 0x0980..0x09FF }) return false
        return (TEMPLATE_TERMS.asSequence() + DEMOGRAPHIC_TERMS.asSequence())
            .any {
                it.length >= 4 && it !in WHOLE_TERM_ONLY_TEMPLATE &&
                    it.any { ch -> ch.code in 0x0980..0x09FF } && it in term
            }
    }

    /** Query content words minus demographics, template words, numbers/units, bigrams. */
    internal fun topicalQueryTokens(query: String): Set<String> =
        BanglaTokenizer.tokenizeQuery(query).filterNotTo(mutableSetOf()) { token ->
            token in DEMOGRAPHIC_TERMS || token in TEMPLATE_TERMS || token in UNIT_TERMS ||
                token.isBanglaCharBigram() || NUMERIC.matches(token)
        }

    /**
     * Gazetteer terms genuinely present in [text].
     *
     * Multi-word terms match as substrings; English terms must match a whole token,
     * because substring matching there pulls in whole synonym groups through
     * accidents like "to" ⊂ "loose stool". Bangla terms of four characters or more
     * match by containment in either direction so inflections and compounds count.
     */
    internal fun gazetteerTermsIn(text: String, clinicalTerms: Set<String>): Set<String> {
        if (text.isBlank() || clinicalTerms.isEmpty()) return emptySet()
        val lower = text.lowercase()
        val tokens = BanglaTokenizer.tokenize(lower).toSet()
        return clinicalTerms.filterTo(mutableSetOf()) { term ->
            when {
                term.isBlank() -> false
                term.contains(' ') -> term in lower
                term.none { it.code in 0x0980..0x09FF } -> term in tokens
                term.length >= 4 -> tokens.any { it.contains(term) || (it.length >= 4 && term.contains(it)) }
                else -> tokens.any { it.startsWith(term) }
            }
        }
    }

    private fun termInText(term: String, text: String, tokens: Set<String>): Boolean = when {
        term.contains(' ') -> term in text.lowercase()
        term.none { it.code in 0x0980..0x09FF } -> term in tokens
        else -> tokens.any { it.contains(term) || (term.length > it.length && it.length >= 4 && term.contains(it)) }
    }

    private fun populationsIn(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val lower = text.lowercase()
        // Short markers must match a whole word: "মা" (mother) also sits inside
        // "মাপার" (to measure), and appears as a character bigram of it, so bigrams
        // are dropped before matching.
        val tokens = BanglaTokenizer.tokenize(lower).filterNotTo(mutableSetOf()) { it.isBanglaCharBigram() }
        return POPULATION_GROUPS.filterValues { markers ->
            markers.any { m -> if (m.length >= 4) m in lower else m in tokens }
        }.keys
    }

    private fun String.isBanglaCharBigram(): Boolean =
        length == 2 && all { it.code in 0x0980..0x09FF }
}
