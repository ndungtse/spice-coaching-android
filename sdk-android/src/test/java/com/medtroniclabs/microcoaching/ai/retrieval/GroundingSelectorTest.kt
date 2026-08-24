package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import org.junit.Assert.assertEquals
import org.junit.Test

class GroundingSelectorTest {

    /**
     * Bengali-only corpus shape: the module a definition question belongs to has one
     * exactly-titled card, while a bigger module from the same clinical topic lands
     * several cards in the candidate list. `search_metadata` is absent on the small
     * module (real production shape for un-enriched cards).
     */
    private val modules = listOf(
        moduleEntityFixture(
            moduleId = "v-tb-def",
            moduleFamilyId = "fam-tb-def",
            titleBn = "যক্ষ্মা (টিবি) কী?",
            cardsJson = """
                [
                  {"title": {"bn": "যক্ষ্মা (টিবি) কী?"},
                   "body": {"bn": "যক্ষ্মা (টিবি) হলো একটি সংক্রামক রোগ যা ব্যাকটেরিয়া দ্বারা সৃষ্ট হয়। এটি সাধারণত ফুসফুসকে আক্রান্ত করে। যক্ষ্মা প্রতিরোধযোগ্য এবং নিরাময়যোগ্য।"}}
                ]
            """.trimIndent(),
        ),
        moduleEntityFixture(
            moduleId = "v-tb-big",
            moduleFamilyId = "fam-tb-big",
            titleBn = "যক্ষ্মা (টিবি) কী এবং কেন স্ক্রিনিং গুরুত্বপূর্ণ",
            cardsJson = """
                [
                  {"title": {"bn": "যক্ষ্মা (টিবি) চিকিৎসার সহায়তা"},
                   "body": {"bn": "যক্ষ্মা চিকিৎসার সাধারণত কয়েক মাস সময় লাগে। রোগীকে চিকিৎসার পুরো সময় ওষুধ চালিয়ে যেতে উৎসাহ দিন। যক্ষ্মা রোগীর পাশে থাকুন।"}},
                  {"title": {"bn": "যক্ষ্মা (টিবি) সংক্রমণ প্রতিরোধ"},
                   "body": {"bn": "যক্ষ্মা সংক্রমণ প্রতিরোধে কাশির শিষ্টাচার মেনে চলুন। ঘর বায়ু চলাচলযোগ্য রাখুন। যক্ষ্মা রোগীর সংস্পর্শ এড়িয়ে চলুন।"}}
                ]
            """.trimIndent(),
        ),
    )

    /**
     * When the EN index contributes nothing (Bengali-only corpus), the selector must
     * preserve raw native BM25 order — the sibling-boost rerank requires two candidates
     * from the same family, so with no cross-index evidence it can only demote a lone
     * exactly-titled card below a multi-card family.
     */
    @Test
    fun `empty english index preserves native bm25 order`() {
        val index = ModuleKnowledgeIndex.build(modules)
        // englishQuery differs from nativeQuery (a BN→EN translation always does),
        // so the merge path is armed; the EN search itself returns nothing.
        val selection = GroundingSelector.select(
            nativeQuery = "যক্ষ্মা কি?",
            englishQuery = "What is tuberculosis?",
            nativeLanguage = ModuleKnowledgeIndex.Lang.BN,
            index = index,
            k = 3,
            scoreThreshold = 0f,
        )
        assertEquals("native", selection.chosenLabel)
        assertEquals(selection.nativeHits.map { it.chunkId }, selection.hits.map { it.chunkId })
        assertEquals("fam-tb-def", selection.hits.first().moduleFamilyId)
    }
}
