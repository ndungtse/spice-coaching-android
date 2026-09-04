package com.medtroniclabs.microcoaching.ai.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The parts of the encoder that decide whether a query lands in the same space the
 * cards were embedded into. All of it is a pure function of the text, and all of it
 * fails silently on device — a wrong prompt or a missing normalization produces a
 * plausible 768 numbers that simply do not match anything.
 */
class QueryEncodingTest {

    // ── prompt ────────────────────────────────────────────────────────────────

    @Test
    fun `the query prompt is the one the model declares`() {
        // google/embeddinggemma-300m's config_sentence_transformers.json, verbatim,
        // trailing space included. The cards used the *document* prompt; mixing the
        // two collapses similarity, so this string is a contract with the backend.
        assertEquals("task: search result | query: ", QueryEncoding.QUERY_PROMPT)
    }

    @Test
    fun `the prompt is prefixed to the query`() {
        assertEquals(
            "task: search result | query: নাভিতে সংক্রমণ",
            QueryEncoding.promptFor("নাভিতে সংক্রমণ"),
        )
    }

    @Test
    fun `surrounding whitespace is trimmed before prompting`() {
        // The fixture vectors were produced from `native_query.strip()`, so anything
        // else would embed a different string than the ids were pinned against.
        assertEquals(
            "task: search result | query: নাভিতে সংক্রমণ",
            QueryEncoding.promptFor("  নাভিতে সংক্রমণ\n"),
        )
    }

    // ── token window ──────────────────────────────────────────────────────────
    // The graph takes a single [1, 256] INT32 tensor of ids and no attention mask, so
    // padding is the only thing telling it where the question ends.

    @Test
    fun `a short query is padded to the graph width`() {
        assertEquals(
            listOf(2, 10, 11, 1, 0, 0, 0, 0),
            QueryEncoding.window(listOf(2, 10, 11, 1), size = 8, padId = 0).toList(),
        )
    }

    @Test
    fun `an overlong query is truncated but keeps its eos`() {
        // Dropping the tail is right — a question states its subject first — but the
        // sequence must still end the way every sequence in training ended.
        val ids = listOf(2) + (100..200).toList() + listOf(1)
        val window = QueryEncoding.window(ids, size = 6, padId = 0)
        assertEquals(6, window.size)
        assertEquals(2, window.first())
        assertEquals(1, window.last())
    }

    @Test
    fun `an exactly-full query is untouched`() {
        assertEquals(
            listOf(2, 5, 6, 1),
            QueryEncoding.window(listOf(2, 5, 6, 1), size = 4, padId = 0).toList(),
        )
    }

    // ── normalization ─────────────────────────────────────────────────────────
    // The graph already pools and projects to 768; whether it also normalizes is
    // not visible from its signature, so the SDK normalizes idempotently.

    @Test
    fun `normalization gives a unit vector`() {
        val v = QueryEncoding.l2Normalize(floatArrayOf(3f, 4f))!!
        assertEquals(0.6f, v[0], 1e-6f)
        assertEquals(0.8f, v[1], 1e-6f)
        assertEquals(1f, sqrt(v.sumOf { (it * it).toDouble() }).toFloat(), 1e-6f)
    }

    @Test
    fun `an already-normalized vector survives normalization unchanged`() {
        // The exported graph may already end in its Normalize module; running ours over
        // the result must be a no-op rather than a second scaling.
        val once = QueryEncoding.l2Normalize(floatArrayOf(0.1f, 0.9f, -0.42f))!!
        val twice = QueryEncoding.l2Normalize(once)!!
        assertTrue(once.indices.all { abs(once[it] - twice[it]) < 1e-6f })
    }

    @Test
    fun `a zero vector cannot be normalized`() {
        assertNull(QueryEncoding.l2Normalize(floatArrayOf(0f, 0f, 0f)))
    }
}
