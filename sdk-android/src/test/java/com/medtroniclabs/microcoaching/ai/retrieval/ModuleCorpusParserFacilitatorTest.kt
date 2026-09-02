package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Facilitator-guide card bodies address a trainer running a session ("write them on
 * the board"), not a CHW with a question, so they must never enter the retrieval
 * index and become an answer.
 */
class ModuleCorpusParserFacilitatorTest {

    private fun module(cardsJson: String) = moduleEntityFixture(
        moduleId = "m1",
        moduleFamilyId = "fam-facilitator",
        titleBn = "ইপিআই কার্যক্রম",
        cardsJson = cardsJson,
    )

    @Test
    fun `facilitator-guide card is excluded from the index`() {
        val cards = """[
            {"title": {"bn": "শিশুর টিকা দিয়ে প্রতিরোধযোগ্য রোগসমূহ"},
             "body": {"bn": [{"type": "paragraph", "content": [{"type": "text",
               "text": "রোগগুলোর নাম কয়েকজনকে বলতে বলবেন এবং তিনি তা বোর্ডে লিখবেন। বলা শেষে সংক্ষিপ্ত ধারণা দিবেন।"}]}]}},
            {"title": {"bn": "টিকার তালিকা"},
             "body": {"bn": [{"type": "paragraph", "content": [{"type": "text",
               "text": "শিশুকে যক্ষ্মা পোলিও হাম রুবেলা সহ এগারোটি রোগের টিকা দিতে হবে নির্ধারিত সময়ে।"}]}]}}
        ]"""
        val chunks = ModuleCorpusParser.extractCardChunks(module(cards))
        assertEquals(1, chunks.size)
        assertEquals("টিকার তালিকা", chunks.single().first.titleBn)
    }

    @Test
    fun `plain clinical card is not mistaken for facilitator text`() {
        val cards = """[
            {"title": {"bn": "রক্তচাপ মাপার নিয়ম"},
             "body": {"bn": [{"type": "paragraph", "content": [{"type": "text",
               "text": "রক্তচাপ মাপার আগে রোগীকে চুপচাপ বসতে দিন এবং বেশি হলে দ্রুত রেফার করতে হবে হাসপাতালে।"}]}]}}
        ]"""
        val chunks = ModuleCorpusParser.extractCardChunks(module(cards))
        assertEquals(1, chunks.size)
    }
}
