package com.medtroniclabs.microcoaching.ai.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DenseVectorIndexTest {

    private fun chunk(family: String, positionalId: Int, cardId: String?) = GroundingChunk(
        source = GroundingChunk.Source.CARD,
        moduleFamilyId = family,
        positionalId = positionalId,
        titleEn = null,
        bodyEn = null,
        titleBn = "শিরোনাম",
        bodyBn = "শরীর",
        score = 0f,
        cardId = cardId,
    )

    @Test
    fun `search returns chunks by cosine descending`() {
        val a = chunk("fam-a", 0, "card-a")
        val b = chunk("fam-b", 1, "card-b")
        val index = DenseVectorIndex.build(
            chunks = listOf(a, b),
            vectorsByCardId = mapOf(
                "card-a" to floatArrayOf(1f, 0f),
                "card-b" to floatArrayOf(0f, 1f),
            ),
        )
        val hits = index.search(floatArrayOf(0.9f, 0.1f), k = 2)
        assertEquals(2, hits.size)
        assertEquals(a.chunkId, hits[0].first.chunkId)
        assertTrue(hits[0].second > hits[1].second)
    }

    @Test
    fun `k caps the result count`() {
        val chunks = (0 until 5).map { chunk("fam", it, "card-$it") }
        val vectors = chunks.associate { it.cardId!! to floatArrayOf(1f, it.positionalId.toFloat()) }
        val index = DenseVectorIndex.build(chunks, vectors)
        assertEquals(3, index.search(floatArrayOf(1f, 1f), k = 3).size)
    }

    @Test
    fun `vectors without a matching chunk are dropped`() {
        val index = DenseVectorIndex.build(
            chunks = listOf(chunk("fam-a", 0, "card-a")),
            vectorsByCardId = mapOf("ghost" to floatArrayOf(1f, 0f)),
        )
        assertEquals(0, index.size)
        assertTrue(index.search(floatArrayOf(1f, 0f), k = 3).isEmpty())
    }

    @Test
    fun `chunks without a cardId are skipped`() {
        val index = DenseVectorIndex.build(
            chunks = listOf(chunk("fam-a", 0, null), chunk("fam-b", 1, "card-b")),
            vectorsByCardId = mapOf("card-b" to floatArrayOf(0f, 1f)),
        )
        assertEquals(1, index.size)
        assertEquals("fam-b", index.search(floatArrayOf(0f, 1f), k = 1)[0].first.moduleFamilyId)
    }

    @Test
    fun `mismatched vector dimensions are dropped`() {
        val index = DenseVectorIndex.build(
            chunks = listOf(chunk("fam-a", 0, "card-a"), chunk("fam-b", 1, "card-b")),
            vectorsByCardId = mapOf(
                "card-a" to floatArrayOf(1f, 0f),
                "card-b" to floatArrayOf(1f, 0f, 0f),
            ),
        )
        assertEquals(1, index.size)
    }
}
