package com.medtroniclabs.microcoaching.ai.embedding

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the Kotlin tokenizer to the ids real `sentencepiece` produces from
 * EmbeddingGemma's own model file. Anything short of exact equality means the query
 * reaches the encoder as a different sequence of tokens than the backend used for the
 * cards, and every cosine after that is meaningless.
 *
 * The full 4.5 MB `sentencepiece.model` is a gated Gemma artifact and is not
 * committed, so CI runs against a vocabulary trimmed to the pieces these texts can
 * reach (`ignored/embeddings-eval/gen_tokenizer_vocab.py`). The trim proves itself:
 * the expected ids come from the *full* model, so reproducing them from the trimmed
 * one can only happen if both the trim and the algorithm are right.
 */
class SentencePieceBpeTokenizerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.bufferedReader().readText()

    private val vocab: SentencePieceVocab by lazy {
        SentencePieceVocab.fromTrimmedJson(resource("embedding/tokenizer_vocab_trimmed.json"))
    }

    private val tokenizer by lazy { SentencePieceBpeTokenizer(vocab) }

    private val cases: List<Triple<String, String, List<Int>>> by lazy {
        json.parseToJsonElement(resource("embedding/tokenizer_ids_fixture.json"))
            .jsonObject["cases"]!!.jsonArray
            .map { it.jsonObject }
            .map { c ->
                Triple(
                    c["name"]!!.jsonPrimitive.content,
                    c["text"]!!.jsonPrimitive.content,
                    c["ids"]!!.jsonArray.map { it.jsonPrimitive.int },
                )
            }
    }

    @Test
    fun `every fixture case tokenizes to the exact python ids`() {
        val failures = cases.mapNotNull { (name, text, expected) ->
            val actual = tokenizer.encode(text)
            if (actual == expected) {
                null
            } else {
                val at = actual.zip(expected).indexOfFirst { it.first != it.second }
                "$name: expected ${expected.size} ids, got ${actual.size}; " +
                    "first divergence at ${if (at >= 0) at else minOf(actual.size, expected.size)} " +
                    "(expected ${expected.take(24)} / actual ${actual.take(24)})"
            }
        }
        assertTrue(
            "${failures.size} of ${cases.size} cases diverged:\n" + failures.take(6).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `the fixture covers the cases a hand-written BPE gets wrong`() {
        val names = cases.map { it.first }
        assertTrue(names.any { it.startsWith("edge_") })
        assertTrue(names.contains("edge_byte_fallback_only"))
        assertTrue(names.contains("edge_byte_fallback_in_context"))
        assertTrue(names.contains("edge_bengali_conjunct"))
        assertTrue(names.contains("edge_empty"))
        assertTrue("labelled queries missing", names.count { it.startsWith("query_") } >= 52)
    }

    @Test
    fun `encoding brackets the text with bos and eos`() {
        val ids = tokenizer.encode("নাভি")
        assertEquals(vocab.bosId, ids.first())
        assertEquals(vocab.eosId, ids.last())
    }

    @Test
    fun `an unknown codepoint falls back to its utf-8 bytes`() {
        // Most emoji have their own piece (U+1F9EC is id 253633), so this needs one
        // that genuinely is not in the 262 k vocabulary: U+1FAE8 must arrive as its
        // four UTF-8 byte pieces rather than as a single unknown token.
        val ids = tokenizer.encode("\uD83E\uDEE8").drop(1).dropLast(1)
        assertEquals(4, ids.size)
        assertTrue("byte fallback produced non-byte pieces", ids.all { vocab.isByteId(it) })
        assertTrue("unk leaked into byte fallback", ids.none { it == vocab.unkId })
    }
}
