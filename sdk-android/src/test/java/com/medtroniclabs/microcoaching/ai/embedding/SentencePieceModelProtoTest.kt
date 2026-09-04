package com.medtroniclabs.microcoaching.ai.embedding

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the production vocabulary loader — the protobuf parser that reads the real
 * `sentencepiece.model` — against the same 69 ids
 * [SentencePieceBpeTokenizerTest] pins. That test proves the merge algorithm using a
 * committed trimmed vocabulary; this one proves the parser that builds the full one at
 * runtime, so between them nothing on the device path is unverified.
 *
 * The model file is a gated 4.5 MB Gemma artifact and is not committed, so this skips
 * unless a copy is present at `ignored/models/sentencepiece.model` (the path the
 * fixture generators read). Run it after fetching that file; CI skips it and relies on
 * the trimmed-vocabulary test instead.
 */
class SentencePieceModelProtoTest {

    private fun locateModel(): File? = sequenceOf(
        "ignored/models/sentencepiece.model",
        "../ignored/models/sentencepiece.model",
    ).map { File(it) }.firstOrNull { it.isFile }

    private fun resource(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.bufferedReader().readText()

    @Test
    fun `the real model file parses and tokenizes to the exact python ids`() {
        val model = locateModel()
        assumeTrue(
            "ignored/models/sentencepiece.model absent — gated artifact, not committed",
            model != null,
        )
        val vocab = SentencePieceVocab.fromModelProto(model!!.readBytes())
        assertEquals("vocabulary size", 262_144, vocab.vocabSize)
        assertEquals(2, vocab.bosId)
        assertEquals(1, vocab.eosId)

        val tokenizer = SentencePieceBpeTokenizer(vocab)
        val cases = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(resource("embedding/tokenizer_ids_fixture.json"))
            .jsonObject["cases"]!!.jsonArray.map { it.jsonObject }

        val failures = cases.mapNotNull { c ->
            val name = c["name"]!!.jsonPrimitive.content
            val expected = c["ids"]!!.jsonArray.map { it.jsonPrimitive.int }
            val actual = tokenizer.encode(c["text"]!!.jsonPrimitive.content)
            if (actual == expected) null else "$name: expected $expected, got $actual"
        }
        assertTrue(
            "${failures.size} of ${cases.size} cases diverged:\n" + failures.take(4).joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
