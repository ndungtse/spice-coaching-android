package com.medtroniclabs.microcoaching.ai.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingSelectorFusionTest {

    private fun chunk(name: String, score: Float = 100f) = GroundingChunk(
        source = GroundingChunk.Source.CARD,
        moduleFamilyId = "fam-$name",
        positionalId = 0,
        titleEn = null,
        bodyEn = null,
        titleBn = name,
        bodyBn = "শরীর $name",
        score = score,
        cardId = "card-$name",
    )

    @Test
    fun `empty dense list leaves the bm25 order untouched`() {
        val bm25 = listOf(chunk("a"), chunk("b"))
        val fused = GroundingSelector.fuseWithDense(bm25, dense = emptyList(), rrfK = 60)
        assertEquals(bm25.map { it.chunkId }, fused.map { it.chunkId })
        assertSame(bm25[0], fused[0])
    }

    @Test
    fun `dense agreement lifts a low bm25 rank above a bm25-only hit`() {
        val a = chunk("a", 90f); val b = chunk("b", 80f)
        val c = chunk("c", 70f); val d = chunk("d", 60f)
        val fused = GroundingSelector.fuseWithDense(
            bm25 = listOf(a, b, c, d),
            dense = listOf(d to 0.8f, a to 0.7f),
            rrfK = 60,
        )
        // RRF: a = 1/61+1/62, d = 1/64+1/61, b = 1/62, c = 1/63 → a, d, b, c.
        assertEquals(listOf("a", "d", "b", "c"), fused.map { it.titleBn })
    }

    @Test
    fun `fused hits carry denseCos and bm25-only hits carry null`() {
        val a = chunk("a"); val b = chunk("b")
        val fused = GroundingSelector.fuseWithDense(
            bm25 = listOf(a, b),
            dense = listOf(a to 0.71f),
            rrfK = 60,
        )
        assertEquals(0.71f, fused.first { it.titleBn == "a" }.denseCos)
        assertNull(fused.first { it.titleBn == "b" }.denseCos)
    }

    @Test
    fun `dense-only entrant joins with zero bm25 score and its cosine`() {
        val a = chunk("a")
        val e = chunk("e", score = 55f)
        val fused = GroundingSelector.fuseWithDense(
            bm25 = listOf(a),
            dense = listOf(e to 0.66f),
            rrfK = 60,
        )
        val entrant = fused.first { it.titleBn == "e" }
        assertEquals(0f, entrant.score)
        assertEquals(0.66f, entrant.denseCos)
    }

    @Test
    fun `fused entries carry each channel's rank and a missing channel is null`() {
        val a = chunk("a", 90f); val b = chunk("b", 80f); val d = chunk("d", 60f)
        val bm25 = listOf(a, b, d)
        val dense = listOf(d to 0.8f, a to 0.7f)
        val fused = GroundingSelector.fuseWithDense(bm25, dense, rrfK = 60)
        val entries = GroundingSelector.fusedEntries(bm25, dense, fused)
        assertEquals(listOf(a.shortKey, d.shortKey, b.shortKey), entries.map { it.key })
        assertEquals(1, entries[0].bm25Rank); assertEquals(2, entries[0].denseRank)
        assertEquals(3, entries[1].bm25Rank); assertEquals(1, entries[1].denseRank)
        assertEquals(2, entries[2].bm25Rank); assertNull(entries[2].denseRank)
    }

    @Test
    fun `the fused line prints a dash for a channel that did not run`() {
        val a = chunk("a", 90f)
        val entries = GroundingSelector.fusedEntries(listOf(a), emptyList(), listOf(a))
        assertEquals("FUSED n=1 order=[${a.shortKey} bm25=1/90.0 dense=-]", GroundingSelector.describeFused(entries))
    }

    @Test
    fun `a dense-only entrant prints no bm25 rank`() {
        val a = chunk("a", 90f); val e = chunk("e", 55f)
        val dense = listOf(e to 0.62f)
        val fused = GroundingSelector.fuseWithDense(listOf(a), dense, rrfK = 60)
        val line = GroundingSelector.describeFused(GroundingSelector.fusedEntries(listOf(a), dense, fused))
        assertTrue(line, line.contains("${e.shortKey} bm25=- dense=1/0.62"))
    }
}
