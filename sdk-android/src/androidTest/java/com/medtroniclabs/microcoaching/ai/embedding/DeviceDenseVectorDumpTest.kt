package com.medtroniclabs.microcoaching.ai.embedding

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * Measurement tool, not an assertion: embeds the whole audit corpus and every labelled
 * query **on the device**, with the quantized graph, and dumps the vectors for the host
 * to turn into a cosine matrix.
 *
 * It exists because [QueryEmbedderParityTest] measures 0.955 against the fp32
 * reference, and no pipeline variant does better — so the residual is this artifact's
 * mixed-precision quantization. The question that actually matters is whether that
 * noise changes any serve/refuse verdict, and the only way to answer it is to replay
 * the eval on device-computed vectors.
 *
 * Cards use the **document** prompt and the same `"{title}. {body} {hints}"` recipe as
 * `ignored/embeddings-eval/gen_fixture.py`, so the dump is comparable to the committed
 * fp32 fixture key for key.
 *
 * Stage the model with `ignored/embeddings-eval/stage_encoder_on_device.sh` first.
 */
@RunWith(AndroidJUnit4::class)
class DeviceDenseVectorDumpTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun asset(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("embedding/$name").bufferedReader().use { it.readText() }

    /** Mirrors gen_fixture.py's `flatten`: concatenate every string a localized field holds. */
    private fun flatten(element: kotlinx.serialization.json.JsonElement?): String = when (element) {
        null -> ""
        is JsonPrimitive -> element.contentOrNull ?: ""
        is JsonArray -> element.joinToString(" ") { flatten(it) }
        is JsonObject -> element.values.joinToString(" ") { flatten(it) }
        else -> ""
    }

    private fun norm(s: String): String = s.replace(WHITESPACE, " ").trim()

    @Test
    fun dumpDeviceVectors() {
        val dir = sequenceOf(
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, EncoderModel.DIR_NAME),
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                EncoderModel.DIR_NAME,
            ),
        ).firstOrNull { EncoderModel.filesPresent(it) }
        assumeTrue("encoder not staged — see stage_encoder_on_device.sh", dir != null)

        val vocab = SentencePieceVocab.fromModelProto(File(dir, EncoderModel.TOKENIZER_FILE_NAME).readBytes())
        val tokenizer = SentencePieceBpeTokenizer(vocab)
        val interpreter = Interpreter(
            File(dir, EncoderModel.TFLITE_FILE_NAME),
            Interpreter.Options().apply { numThreads = 2 },
        )

        fun embed(text: String): FloatArray {
            val ids = tokenizer.encode(text)
            val window = QueryEncoding.window(ids, EncoderModel.MAX_TOKENS, vocab.padId)
            val out = arrayOf(FloatArray(EncoderModel.EMBEDDING_DIM))
            interpreter.run(arrayOf(window), out)
            return QueryEncoding.l2Normalize(out[0])!!
        }

        // ── cards, in the fixture's key order ────────────────────────────────────
        val keys = ArrayList<String>()
        val cardVectors = ArrayList<FloatArray>()
        json.parseToJsonElement(asset("audit_corpus_2026-08.json")).jsonArray.forEach { moduleElement ->
            val module = moduleElement.jsonObject
            val family = module.getValue("module_family_id").jsonPrimitive.content.take(8)
            val cards = json.parseToJsonElement(module.getValue("cards_json").jsonPrimitive.content).jsonArray
            cards.forEachIndexed { index, cardElement ->
                val card = cardElement.jsonObject
                val metadata = card["search_metadata"]?.jsonObject
                val hints = listOf("retrieval_hints", "questions", "keywords")
                    .mapNotNull { metadata?.get(it) }
                    .joinToString(" ") { flatten(it) }
                val title = norm(flatten(card["title"]))
                val body = norm(flatten(card["body"]))
                keys += "$family:$index"
                cardVectors += embed(DOCUMENT_PROMPT + "$title. $body ${norm(hints)}".trim())
            }
        }

        // ── queries, from every labelled set on disk ─────────────────────────────
        // Card vectors are the expensive half and are shared, so embedding both sets in
        // one run costs only the queries and keeps them directly comparable.
        val queryIds = ArrayList<String>()
        val queryVectors = ArrayList<FloatArray>()
        listOf("audit_labelled.json", "chw_questions_2026-09.json").forEach { file ->
            json.parseToJsonElement(asset(file)).jsonArray.forEach { row ->
                val record = row.jsonObject
                queryIds += record.getValue("id").jsonPrimitive.content
                queryVectors += embed(
                    QueryEncoding.promptFor(record.getValue("native_query").jsonPrimitive.content),
                )
            }
        }
        interpreter.close()

        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "device_vectors.json")
        out.bufferedWriter().use { writer ->
            writer.write("{\"model\":\"litert-community/embeddinggemma-300m seq256 mixed-precision\",")
            writer.write("\"doc_variant\":\"title+body+hints\",\"dim\":${EncoderModel.EMBEDDING_DIM},")
            writer.write("\"keys\":[${keys.joinToString(",") { "\"$it\"" }}],")
            writer.write("\"card_vectors\":[")
            cardVectors.forEachIndexed { i, v ->
                if (i > 0) writer.write(",")
                writer.write(v.joinToString(",", "[", "]") { "%.6f".format(it) })
            }
            writer.write("],\"query_ids\":[${queryIds.joinToString(",") { "\"$it\"" }}],")
            writer.write("\"query_vectors\":[")
            queryVectors.forEachIndexed { i, v ->
                if (i > 0) writer.write(",")
                writer.write(v.joinToString(",", "[", "]") { "%.6f".format(it) })
            }
            writer.write("]}")
        }
        println("DUMP wrote ${out.absolutePath} cards=${keys.size} queries=${queryIds.size} bytes=${out.length()}")
    }

    private companion object {
        /** EmbeddingGemma's document prompt — the side the backend used for the cards. */
        const val DOCUMENT_PROMPT = "title: none | text: "
        val WHITESPACE = Regex("\\s+")
    }
}
