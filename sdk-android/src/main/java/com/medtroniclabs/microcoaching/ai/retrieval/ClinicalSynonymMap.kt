package com.medtroniclabs.microcoaching.ai.retrieval

/**
 * Clinical concept synonym groups used by both the scope classifier and the BM25
 * query expander. Each entry maps a canonical concept name to the full set of
 * aliases in English, Bangla, and common morphological variants.
 *
 * To add a new concept as scope grows: add one entry to [GROUPS]. Both
 * [ScopeClassifier.buildFrom] (via [allTerms]) and [ModuleKnowledgeIndex.search]
 * (via [expandQuery]) benefit automatically — no other code changes needed.
 */
object ClinicalSynonymMap {

    private val GROUPS: Map<String, Set<String>> = mapOf(
        "anaemia" to setOf(
            "anaemia", "anemia", "haemoglobin", "hemoglobin", "severe anaemia",
            "অ্যানিমিয়া", "রক্তশূন্যতা", "হিমোগ্লোবিন", "রক্তস্বল্পতা",
        ),
        "diarrhoea" to setOf(
            "diarrhoea", "diarrhea", "loose stool", "watery stool",
            "ডায়রিয়া", "পাতলা পায়খানা",
        ),
        "chest_indrawing" to setOf(
            "chest indrawing", "chest retraction", "indrawing", "respiratory distress",
            "বুক", "টান", "বুকের টান", "বুকের ভিতরে টান",
        ),
        "dehydration" to setOf(
            "dehydration", "dehydrated",
            "ডিহাইড্রেশন", "পানিশূন্যতা",
        ),
        "jaundice" to setOf(
            "jaundice", "icterus",
            "জন্ডিস", "পীলিয়া",
        ),
        "sepsis" to setOf(
            "sepsis", "blood infection", "neonatal sepsis",
            "সেপসিস", "রক্তের বিষক্রিয়া",
        ),
        "malnutrition" to setOf(
            "malnutrition", "stunting", "wasting", "undernutrition",
            "অপুষ্টি", "পুষ্টিহীনতা",
        ),
        "referral" to setOf(
            "referral", "refer", "hospital referral",
            "রেফার", "রেফারেল", "হাসপাতাল পাঠানো",
        ),
        "anc_visit" to setOf(
            "anc", "antenatal", "antenatal visit", "anc visit", "rounds",
            "এএনসি", "দফা", "গর্ভকালীন সেবা",
        ),
        // Add new groups here as scope expands — no logic changes needed.
    )

    /** Flat set of all aliases across every group. Consumed by [ScopeClassifier.buildFrom]. */
    val allTerms: Set<String> by lazy { GROUPS.values.flatten().toSet() }

    /**
     * Expand a tokenized query by adding all synonyms for any token that matches
     * an alias in [GROUPS]. Matching is bidirectional substring: a token matches if
     * it equals an alias or either contains the other. This lets "বুকের" match the
     * "বুক" entry and pull in the full chest-indrawing synonym set.
     *
     * Consumed by [ModuleKnowledgeIndex.search] to widen BM25 token coverage across
     * BN↔EN vocabulary boundaries without requiring stemming or embeddings.
     */
    fun expandQuery(tokens: List<String>): List<String> {
        val lower = tokens.map { it.lowercase() }
        val expanded = lower.toMutableSet()
        for (token in lower) {
            GROUPS.values.firstOrNull { aliases ->
                aliases.any { alias -> alias == token || token in alias || alias in token }
            }?.let { expanded += it }
        }
        return expanded.toList()
    }
}
