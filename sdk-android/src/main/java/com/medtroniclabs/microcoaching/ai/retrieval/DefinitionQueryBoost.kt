package com.medtroniclabs.microcoaching.ai.retrieval

/**
 * Narrow ranking rule for one question shape BM25 systematically gets wrong: the bare
 * definition — "যক্ষ্মা কি?", "ডায়াবেটিস কাকে বলে?".
 *
 * Term statistics actively work against the right card here. A card *about* a topic
 * repeats its name more often than the card that *defines* it once, and the defining
 * card is usually the longer one, so BM25's term frequency and its length
 * normalisation both favour the wrong card by a small margin.
 *
 * No field weighting fixes that — it is a mismatch between *question shape* and *term
 * counts* — so this rule keys on shape instead. It is gated hard because an ungated
 * version is actively harmful: most questions that merely contain "কী" want a specific
 * fact rather than a definition, and boosting definition cards for all of them
 * measurably loses accuracy.
 *
 * Both conditions must hold before the boost applies:
 *  1. the question is *bare* — a topic plus a definition marker, nothing else. A
 *     question with extra content words ("ম্যালেরিয়ার জীবাণুর নাম কী?") is asking for a
 *     specific fact, not a definition, and is left alone.
 *  2. a candidate is definition-titled and names that topic **as a contiguous phrase**.
 *     Matching the topic's words individually is not enough: "প্রসব পরিকল্পনা কী?" would then
 *     also boost "প্রসব পরবর্তী পরিবার পরিকল্পনা (PPFP) কী?", which contains both words but
 *     is a different subject.
 */
internal object DefinitionQueryBoost {

    /**
     * Multiplier applied to a topic-matched definition card. Comfortably larger than the
     * body-frequency deficit it exists to overcome, yet small enough that it cannot
     * promote a card the query does not otherwise match.
     */
    const val BOOST = 2.5f

    /** At most this many content words may remain once the definition marker is removed. */
    private const val MAX_TOPIC_WORDS = 2

    /** "… কী?", "… কি?", "… কাকে বলে?", "… কী বোঝায়?" — the tail that marks a definition ask. */
    private val DEFINITION_TAIL = Regex(
        // The marker must start its own word — otherwise a word merely ENDING in কি
        // ("থাকি?") would be split, leaving a fragment as the topic.
        """(?:^|\s)(?:কাকে\s*বলা\s*হয়|কাকে\s*বলে|ক[িী]\s*বোঝায়|ক[িী])\s*[?？]*\s*$""",
    )

    /** A title is definition-shaped when it carries the same marker, anywhere in it. */
    // `\b` is ASCII-based in Java regex and never fires next to Bangla, so the marker's
    // right edge is asserted explicitly: কী not followed by another Bangla letter.
    private val DEFINITION_TITLE =
        Regex("""(?:কাকে\s*বলে|ক[িী]\s*বোঝায়|সংজ্ঞা|ক[িী](?![\u0980-\u09FF]))""")

    /**
     * The topic phrase of a bare definition question, or null when the question is not
     * one. Kept as the phrase the CHW typed so [matches] can require contiguity.
     */
    fun topicOf(query: String): String? {
        val trimmed = query.trim()
        if (!DEFINITION_TAIL.containsMatchIn(trimmed)) return null
        val body = DEFINITION_TAIL.replace(trimmed, "").trim().replace(WHITESPACE, " ")
        if (body.isEmpty()) return null
        if (contentWords(body).size !in 1..MAX_TOPIC_WORDS) return null
        return body
    }

    /** True when [chunk] is titled as a definition of exactly [topic]. */
    fun matches(chunk: GroundingChunk, topic: String): Boolean {
        val title = (chunk.titleBn ?: chunk.titleEn ?: return false).replace(WHITESPACE, " ")
        if (!DEFINITION_TITLE.containsMatchIn(title)) return false
        return title.contains(topic)
    }

    private val WHITESPACE = Regex("\\s+")

    /** Words only — the tokenizer's Bangla character bigrams would match almost any title. */
    private fun contentWords(text: String): Set<String> =
        BanglaTokenizer.tokenizeQuery(text)
            .filterNotTo(mutableSetOf()) { it.length == 2 && it.all { c -> c.code in 0x0980..0x09FF } }
}
