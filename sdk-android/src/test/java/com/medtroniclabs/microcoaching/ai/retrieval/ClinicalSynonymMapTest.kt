package com.medtroniclabs.microcoaching.ai.retrieval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClinicalSynonymMapTest {

    // ── expandQuery: BN → EN expansion ───────────────────────────────────────

    @Test
    fun `expandQuery on BN diarrhoea token includes English equivalent`() {
        val result = ClinicalSynonymMap.expandQuery(listOf("ডায়রিয়া"))
        assertTrue("must include diarrhoea", result.contains("diarrhoea"))
        assertTrue("must include diarrhea", result.contains("diarrhea"))
    }

    @Test
    fun `expandQuery on BN chest token includes chest indrawing aliases`() {
        val result = ClinicalSynonymMap.expandQuery(listOf("বুক"))
        assertTrue("must include chest indrawing", result.contains("chest indrawing"))
        assertTrue("must include indrawing", result.contains("indrawing"))
    }

    @Test
    fun `expandQuery on BN anaemia token includes both spellings and haemoglobin`() {
        val result = ClinicalSynonymMap.expandQuery(listOf("অ্যানিমিয়া"))
        assertTrue("must include anaemia", result.contains("anaemia"))
        assertTrue("must include রক্তশূন্যতা", result.contains("রক্তশূন্যতা"))
        assertTrue("must include হিমোগ্লোবিন", result.contains("হিমোগ্লোবিন"))
    }

    // ── expandQuery: EN → BN expansion ───────────────────────────────────────

    @Test
    fun `expandQuery on EN anaemia includes BN equivalents`() {
        val result = ClinicalSynonymMap.expandQuery(listOf("anaemia"))
        assertTrue("must include অ্যানিমিয়া", result.contains("অ্যানিমিয়া"))
        assertTrue("must include রক্তশূন্যতা", result.contains("রক্তশূন্যতা"))
    }

    @Test
    fun `expandQuery on EN dehydration includes BN equivalent`() {
        val result = ClinicalSynonymMap.expandQuery(listOf("dehydration"))
        assertTrue("must include ডিহাইড্রেশন", result.contains("ডিহাইড্রেশন"))
    }

    // ── expandQuery: morphological / substring matching ───────────────────────

    @Test
    fun `expandQuery on বুকের (inflected) matches chest_indrawing group`() {
        // "বুকের" contains "বুক" so it should trigger the chest_indrawing group
        val result = ClinicalSynonymMap.expandQuery(listOf("বুকের"))
        assertTrue("inflected form must pull in chest indrawing aliases", result.contains("chest indrawing"))
    }

    // ── expandQuery: unknown token passes through unchanged ──────────────────

    @Test
    fun `unknown token passes through without expansion`() {
        val result = ClinicalSynonymMap.expandQuery(listOf("xyzunknown"))
        assertTrue("unknown token must be preserved", result.contains("xyzunknown"))
        // Must not add clinical noise
        assertFalse(result.contains("diarrhoea"))
        assertFalse(result.contains("anaemia"))
    }

    @Test
    fun `expandQuery preserves all original tokens`() {
        val input = listOf("রেফার", "করতে", "হবে")
        val result = ClinicalSynonymMap.expandQuery(input)
        input.forEach { assertTrue("original token '$it' must be preserved", result.contains(it)) }
    }

    // ── allTerms: flat set covers key aliases ────────────────────────────────

    @Test
    fun `allTerms contains BN and EN for critical concepts`() {
        val terms = ClinicalSynonymMap.allTerms
        assertTrue(terms.contains("ডায়রিয়া"))
        assertTrue(terms.contains("diarrhoea"))
        assertTrue(terms.contains("বুক"))
        assertTrue(terms.contains("chest indrawing"))
        assertTrue(terms.contains("অ্যানিমিয়া"))
        assertTrue(terms.contains("anaemia"))
        assertTrue(terms.contains("ডিহাইড্রেশন"))
        assertTrue(terms.contains("dehydration"))
    }
}
