package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The backend's `/sync/modules` cards carry a per-card `id` that the dense
 * retrieval index joins card-embedding vectors on. Cards cached before the field
 * existed have none — the chunk must then carry null rather than fail.
 */
class ModuleCorpusParserCardIdTest {

    @Test
    fun `card id is parsed when present and null when absent`() {
        val cards = """[
            {"id": "c9b2990d-028f-4a44-bdc5-55716abc10bc",
             "title": {"bn": "শালদুধ"},
             "body": {"bn": [{"type": "paragraph", "content": [{"type": "text",
               "text": "শালদুধ শিশুর জন্য অত্যন্ত পুষ্টিকর এবং রোগ প্রতিরোধ ক্ষমতা বাড়ায়। জন্মের পর প্রথম তিন দিন খাওয়াতে হবে।"}]}]}},
            {"title": {"bn": "বুকের দুধ"},
             "body": {"bn": [{"type": "paragraph", "content": [{"type": "text",
               "text": "ছয় মাস পর্যন্ত শুধুমাত্র বুকের দুধ খাওয়াতে হবে দিনে দশ থেকে বারো বার করে নিয়মিত।"}]}]}}
        ]"""
        val chunks = ModuleCorpusParser.extractCardChunks(
            moduleEntityFixture(
                moduleId = "m1",
                moduleFamilyId = "fam-card-id",
                titleBn = "শিশুর পুষ্টি",
                cardsJson = cards,
            ),
        ).map { it.first }

        assertEquals(2, chunks.size)
        assertEquals("c9b2990d-028f-4a44-bdc5-55716abc10bc", chunks[0].cardId)
        assertNull(chunks[1].cardId)
    }
}
