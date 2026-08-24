package com.medtroniclabs.microcoaching.ai.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefinitionQueryBoostTest {

    private fun card(titleBn: String) = GroundingChunk(
        source = GroundingChunk.Source.CARD,
        moduleFamilyId = "fam",
        positionalId = 0,
        titleEn = null,
        bodyEn = null,
        titleBn = titleBn,
        bodyBn = "কিছু বিবরণ এখানে রয়েছে যা কার্ডের বিষয়বস্তু ব্যাখ্যা করে।",
        score = 10f,
    )

    @Test
    fun `bare definition questions expose their topic`() {
        assertEquals("যক্ষ্মা", DefinitionQueryBoost.topicOf("যক্ষ্মা কি?"))
        assertEquals("ডায়াবেটিস", DefinitionQueryBoost.topicOf("ডায়াবেটিস কাকে বলে?"))
        assertEquals("প্রসব পরিকল্পনা", DefinitionQueryBoost.topicOf("প্রসব পরিকল্পনা কী?"))
    }

    /**
     * The gate keys on the definition MARKER, not on punctuation — a CHW who omits the
     * question mark, doubles it, or spaces oddly still gets the rule.
     */
    @Test
    fun `punctuation and spacing do not decide the gate`() {
        assertEquals("যক্ষ্মা", DefinitionQueryBoost.topicOf("যক্ষ্মা কি"))
        assertEquals("যক্ষ্মা", DefinitionQueryBoost.topicOf("যক্ষ্মা কী??"))
        assertEquals("যক্ষ্মা", DefinitionQueryBoost.topicOf("  যক্ষ্মা   কি ?  "))
    }

    /** The marker only counts as a definition ask when it TRAILS the question. */
    @Test
    fun `marker must be at the end`() {
        assertNull(DefinitionQueryBoost.topicOf("যক্ষ্মা কী এবং কেন হয়?"))
        assertNull(DefinitionQueryBoost.topicOf("কি যক্ষ্মা ছোঁয়াচে?"))
        assertNull(DefinitionQueryBoost.topicOf("যক্ষ্মা রোগীর কী করব?"))
        // a word merely ending in কি must not be split into a fragment topic
        assertNull(DefinitionQueryBoost.topicOf("থাকি?"))
    }

    /** A question naming a specific fact is not a definition ask, even though it ends in কী. */
    @Test
    fun `question with extra content words does not trigger the gate`() {
        assertNull(DefinitionQueryBoost.topicOf("ম্যালেরিয়ার জীবাণুর নাম কী?"))
        assertNull(DefinitionQueryBoost.topicOf("খালি পেটে সুগার কত হলে ডায়াবেটিস ধরা হয়?"))
        assertNull(DefinitionQueryBoost.topicOf("গর্ভবতী মায়ের প্রতিদিন কতটুকু পানি পান করা উচিত?"))
    }

    @Test
    fun `definition-titled card on the same topic matches`() {
        val topic = DefinitionQueryBoost.topicOf("যক্ষ্মা কি?")!!
        assertTrue(DefinitionQueryBoost.matches(card("যক্ষ্মা (টিবি) কী?"), topic))
        assertFalse(DefinitionQueryBoost.matches(card("যক্ষ্মা (টিবি) চিকিৎসার সহায়তা"), topic))
    }

    /**
     * The regression that forced contiguous-phrase matching: both titles contain প্রসব and
     * পরিকল্পনা and both are definition-shaped, but only one is about birth planning.
     */
    @Test
    fun `topic must appear as a contiguous phrase`() {
        val topic = DefinitionQueryBoost.topicOf("প্রসব পরিকল্পনা কী?")!!
        assertTrue(DefinitionQueryBoost.matches(card("প্রসব পরিকল্পনা কী এবং কেন প্রয়োজন?"), topic))
        assertFalse(
            "a different subject sharing both words must not be boosted",
            DefinitionQueryBoost.matches(card("প্রসব পরবর্তী পরিবার পরিকল্পনা (PPFP) কী?"), topic),
        )
    }

    @Test
    fun `boost promotes the definition card over a higher-scoring sibling`() {
        val modules = listOf(
            com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture(
                moduleId = "v-tb", moduleFamilyId = "fam-tb", titleBn = "যক্ষ্মা",
                cardsJson = """
                    [
                      {"title": {"bn": "যক্ষ্মা (টিবি) চিকিৎসার সহায়তা"},
                       "body": {"bn": "যক্ষ্মা চিকিৎসা কয়েক মাস চলে। যক্ষ্মা রোগীকে ওষুধ চালিয়ে যেতে উৎসাহ দিন। যক্ষ্মা নিরাময়যোগ্য।"}},
                      {"title": {"bn": "যক্ষ্মা (টিবি) কী?"},
                       "body": {"bn": "যক্ষ্মা হলো একটি সংক্রামক রোগ যা ব্যাকটেরিয়া দ্বারা সৃষ্ট হয় এবং সাধারণত ফুসফুসকে আক্রান্ত করে থাকে।"}}
                    ]
                """.trimIndent(),
            ),
        )
        val index = ModuleKnowledgeIndex.build(modules)
        val boosted = index.search(
            "যক্ষ্মা কি?", k = 2, scoreThreshold = 0f,
            language = ModuleKnowledgeIndex.Lang.BN, definitionBoost = true,
        )
        val plain = index.search(
            "যক্ষ্মা কি?", k = 2, scoreThreshold = 0f,
            language = ModuleKnowledgeIndex.Lang.BN, definitionBoost = false,
        )
        // The definition card is served, and only it was multiplied.
        assertEquals("fam-tb:card:1", boosted.first().chunkId)
        val definitionPlain = plain.first { it.chunkId == "fam-tb:card:1" }.score
        val definitionBoosted = boosted.first { it.chunkId == "fam-tb:card:1" }.score
        assertEquals(definitionPlain * DefinitionQueryBoost.BOOST, definitionBoosted, 0.01f)
        val siblingPlain = plain.first { it.chunkId == "fam-tb:card:0" }.score
        val siblingBoosted = boosted.first { it.chunkId == "fam-tb:card:0" }.score
        assertEquals("the non-definition sibling must be untouched", siblingPlain, siblingBoosted, 0.01f)
    }
}
