package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeClassifierTest {

    private fun moduleWithTitles(titleBn: String, titleEn: String? = null) = ModuleEntity(
        moduleId = "m1",
        moduleFamilyId = "fam1",
        version = 1,
        titleBn = titleBn,
        titleEn = titleEn,
        domain = "rmnch",
        moduleType = "initial_training",
        estimatedMinutes = 10,
        difficultyLevel = "moderate",
        clinicallyReviewed = true,
        updatedAtIso = "2026-06-01T00:00:00Z",
    )

    // ── Title tokenization fix ────────────────────────────────────────────────

    @Test
    fun `single word from multi-word Bengali title passes scope check`() {
        val classifier = ScopeClassifier.buildFrom(listOf(moduleWithTitles("ম্যালেরিয়া বোঝা")))
        // Query contains only the first word of the title, not the full phrase.
        // Before the fix this returned false because "ম্যালেরিয়া বোঝা" as a single
        // term is not a substring of "ম্যালেরিয়া জীবাণুর ধ্বংস".
        assertTrue(classifier.isInScope("ম্যালেরিয়া জীবাণুর ধ্বংস"))
    }

    @Test
    fun `full title phrase still passes scope check after tokenization`() {
        val classifier = ScopeClassifier.buildFrom(listOf(moduleWithTitles("ম্যালেরিয়া বোঝা")))
        // The full phrase must also pass because "ম্যালেরিয়া" is present as a word.
        assertTrue(classifier.isInScope("ম্যালেরিয়া বোঝা সম্পর্কে"))
    }

    @Test
    fun `single word from English title passes scope check`() {
        val classifier = ScopeClassifier.buildFrom(listOf(moduleWithTitles("টাইটেল", "Malaria Overview")))
        assertTrue(classifier.isInScope("tell me about malaria treatment"))
    }

    @Test
    fun `short title words under 3 chars are not added to scope terms`() {
        // "ANC" is 3 chars (passes); "AN" would be 2 (filtered out)
        val classifier = ScopeClassifier.buildFrom(listOf(moduleWithTitles("AN বোঝা")))
        // "AN" is 2 chars so it must NOT be added — no false scope widening
        assertFalse(classifier.isInScope("an overview"))
        // But "বোঝা" (4 chars) must be present
        assertTrue(classifier.isInScope("বোঝা"))
    }

    // ── STATIC_TERMS additions ────────────────────────────────────────────────

    @Test
    fun `malaria term in STATIC_TERMS passes scope check`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("malaria symptoms"))
        assertTrue(classifier.isInScope("ম্যালেরিয়া জ্বর"))
    }

    @Test
    fun `sepsis term in STATIC_TERMS passes scope check`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("নবজাতকের সেপসিস কী"))
        assertTrue(classifier.isInScope("neonatal sepsis signs"))
    }

    @Test
    fun `cancer terms in STATIC_TERMS pass scope check`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("cervical cancer screening"))
        assertTrue(classifier.isInScope("ক্যান্সার স্ক্রিনিং কীভাবে করতে হয়"))
    }

    @Test
    fun `malnutrition terms in STATIC_TERMS pass scope check`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("child malnutrition prevention"))
        assertTrue(classifier.isInScope("অপুষ্টি প্রতিরোধ"))
    }

    // ── New BN terms from v3 field evaluation (R2) ───────────────────────────

    @Test
    fun `chest indrawing BN query is in scope`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        // Q3 from the evaluation was a critical clinical failure — chest indrawing was L1 blocked
        assertTrue(classifier.isInScope("বুকের ভিতরে টান থাকলে কী করণীয়"))
        assertTrue(classifier.isInScope("বুকের টান"))
    }

    @Test
    fun `diarrhoea BN term is in scope`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("ডায়রিয়ার মারাত্মক লক্ষণ কী"))
        assertTrue(classifier.isInScope("শিশুর ডায়রিয়া হলে কী করব"))
    }

    @Test
    fun `anaemia BN terms are in scope`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("তীব্র অ্যানিমিয়ার লক্ষণ"))
        assertTrue(classifier.isInScope("রক্তশূন্যতার চিকিৎসা কী"))
    }

    @Test
    fun `dehydration BN term is in scope`() {
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("ডিহাইড্রেশন পরীক্ষা কীভাবে করবেন"))
    }

    @Test
    fun `synonym map contributes additional scope terms`() {
        // ClinicalSynonymMap.allTerms is merged into the classifier; পানিশূন্যতা is an alias
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isInScope("পানিশূন্যতা নির্ণয়"))
    }

    // ── Deny list independence ────────────────────────────────────────────────

    @Test
    fun `deny list still blocks clearly out-of-scope queries`() {
        val classifier = ScopeClassifier.buildFrom(listOf(moduleWithTitles("ম্যালেরিয়া বোঝা")))
        assertTrue(classifier.isOutOfScope("ফুটবল খেলার নিয়ম"))
        assertTrue(classifier.isOutOfScope("cricket world cup"))
        assertTrue(classifier.isOutOfScope("python programming tutorial"))
    }

    @Test
    fun `deny list wins even when clinical term is also present`() {
        // "ক্রিকেট" is in DENY_TERMS; even if a clinical word appears,
        // isOutOfScope should return true.
        val classifier = ScopeClassifier.buildFrom(emptyList())
        assertTrue(classifier.isOutOfScope("ম্যালেরিয়া ক্রিকেট"))
    }
}
