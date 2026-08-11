package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the v3 `search_metadata` schema parsing in [ModuleKnowledgeIndex]:
 *  - `synonyms` is now LANGUAGE-BUCKETED (`{bn: {term: tr}, en: {term: tr}}`),
 *    not the legacy flat `synonyms_en: {term: tr}`.
 *  - `topic_tags` / `clinical_conditions` are now per-language maps
 *    (`{bn: [...], en: [...]}`), not bare arrays.
 *
 * Cards use the v3 nested `title`/`body: {bn, en}` shape too. Legacy-shape
 * coverage lives in [BackendSynonymsReadyTest].
 */
class SearchMetadataNestedSchemaTest {

    private fun module(searchMetadataJson: String) = moduleEntityFixture(
        moduleId = "m1",
        moduleFamilyId = "fam1",
        cardsJson = """[{"title":{"en":"TB therapy","bn":"টিবি"},"body":{"en":"Use directly observed treatment daily.","bn":"প্রতিদিন চিকিৎসা।"}}]""",
        searchMetadataJson = searchMetadataJson,
    )

    @Test
    fun `nested synonyms bridge a query term to indexed body vocabulary`() {
        val meta = """
            {
              "schema_version": 1,
              "synonyms": {
                "en": { "DOTS": "directly observed treatment" },
                "bn": { "RDT": "র‍্যাপিড ডায়াগনস্টিক টেস্ট" }
              }
            }
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(module(meta)))
        val hits = index.search("DOTS", k = 2, scoreThreshold = 0f)
        assertTrue("nested synonyms (v3) must bridge DOTS → body vocabulary", hits.isNotEmpty())
    }

    @Test
    fun `nested topic_tags and clinical_conditions reach the keyword index`() {
        val meta = """
            {
              "schema_version": 1,
              "topic_tags": { "en": ["zzdistinctivetag"], "bn": [] },
              "clinical_conditions": { "en": ["zzconditionx"], "bn": [] }
            }
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(module(meta)))
        assertTrue(
            "nested topic_tags (v3) must be indexed as keywords",
            index.search("zzdistinctivetag", k = 2, scoreThreshold = 0f).isNotEmpty(),
        )
        assertTrue(
            "nested clinical_conditions (v3) must be indexed as keywords",
            index.search("zzconditionx", k = 2, scoreThreshold = 0f).isNotEmpty(),
        )
    }
}
