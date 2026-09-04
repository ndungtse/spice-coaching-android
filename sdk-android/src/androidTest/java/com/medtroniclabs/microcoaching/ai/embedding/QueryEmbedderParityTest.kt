package com.medtroniclabs.microcoaching.ai.embedding

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The hard acceptance gate for the on-device encoder: every question embedded here
 * must land within **cosine 0.99** of the vector `sentence-transformers` produced from
 * the same text with the same model.
 *
 * This is the check that would have caught the 0.89–0.99 skew earlier. Nothing
 * downstream can detect a query vector that is merely *close* — cosines against the
 * card vectors just get quietly worse, and one such gap already flipped a question on
 * device. So anything below 0.99 means the prompt, the tokenizer, the pad id or the
 * quantization diverges, and the fix is to find which, never to lower this bound.
 *
 * **Measured 2026-09-03 (Android 13 emulator, arm64): min 0.916, mean 0.955, max
 * 0.972 — this gate FAILS, and the cause is the artifact, not the pipeline.** Six
 * variants were compared (no prompt 0.842, no bos/eos 0.916, no eos 0.931, pad=`<eos>`
 * 0.089, document prompt 0.767) and the configuration in this test scored highest, so
 * nothing here is misconfigured. Inter-query geometry also matches the reference
 * (device-vs-other-query 0.512 against reference-vs-other-query 0.534), which rules
 * out a systematic rotation or offset and leaves per-query mixed-precision
 * quantization noise as the whole of the residual. `litert-community` publishes only
 * mixed-precision builds of this model, so 0.99 is not reachable with any of them; see
 * [DeviceDenseVectorDumpTest] for what that noise costs in retrieval terms.
 *
 * The 171 MB model is not committed. Stage it through internal storage — pushing
 * into the app's external directory leaves files the app cannot open (see
 * [encoderDir]) — with `ignored/embeddings-eval/stage_encoder_on_device.sh`, which
 * needs `adb root`. Without the model the test skips rather than failing, since it
 * cannot run in CI.
 */
@RunWith(AndroidJUnit4::class)
class QueryEmbedderParityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Where the encoder is, preferring the production location.
     *
     * The internal fallback exists because a model staged with `adb push` into the
     * app's *external* directory is listed but unreadable — Android's FUSE layer
     * attributes files to whichever process created them, so bytes written by
     * `adb`/root are denied to the app whatever the ownership says. Internal storage
     * is a real filesystem, where a root `cp` plus `chown`/`restorecon` produces files
     * the app can actually open. Production always uses the external directory; only
     * this test ever reads the fallback.
     */
    private fun encoderDir(): File = sequenceOf(
        File(context.getExternalFilesDir(null), EncoderModel.DIR_NAME),
        File(context.filesDir, EncoderModel.DIR_NAME),
    ).firstOrNull { EncoderModel.filesPresent(it) }
        ?: File(context.getExternalFilesDir(null), EncoderModel.DIR_NAME)

    private fun referenceVectors(): List<Pair<String, FloatArray>> {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("embedding/query_reference_vectors.json")
            .bufferedReader().use { it.readText() }
        return Json { ignoreUnknownKeys = true }.parseToJsonElement(text)
            .jsonObject.getValue("queries").jsonArray
            .map { it.jsonObject }
            .map { q ->
                q.getValue("text").jsonPrimitive.content to
                    q.getValue("vec").jsonArray.map { v -> v.jsonPrimitive.float }.toFloatArray()
            }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        for (i in a.indices) dot += (a[i] * b[i]).toDouble()
        return dot
    }

    @Test
    fun onDeviceQueryVectorsMatchTheServerVectors() {
        val dir = encoderDir()
        // Worth printing on a skip: a staged-but-unreadable model reports len=0 and
        // canRead=false here, which looks identical to "absent" everywhere else.
        dir.listFiles()?.forEach { println("PARITY   sees ${it.name} len=${it.length()} canRead=${it.canRead()}") }
        assumeTrue(
            "encoder files absent at $dir — push the model (see KDoc); skipping parity gate",
            EncoderModel.filesPresent(dir),
        )

        val embedder = LiteRtQueryEmbedder(dir)
        val reference = referenceVectors()
        assertTrue("no reference vectors", reference.isNotEmpty())

        val cosines = ArrayList<Pair<String, Double>>(reference.size)
        var initMs = 0L
        var totalMs = 0L
        runBlocking {
            reference.forEachIndexed { index, (text, expected) ->
                val started = System.nanoTime()
                val actual = embedder.embed(text)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                // The first call maps 171 MB of weights, so it is reported separately
                // rather than dragging the per-query figure up.
                if (index == 0) initMs = elapsed else totalMs += elapsed
                assertNotNull("embed returned null for: $text", actual)
                cosines += text to cosine(actual!!, expected)
            }
        }
        embedder.close()

        val worst = cosines.minByOrNull { it.second }!!
        val mean = cosines.sumOf { it.second } / cosines.size
        val best = cosines.maxOf { it.second }
        println(
            "PARITY n=${cosines.size} min=%.5f mean=%.5f max=%.5f | load+first=%d ms, per-query=%d ms"
                .format(worst.second, mean, best, initMs, totalMs / (cosines.size - 1).coerceAtLeast(1)),
        )
        cosines.sortedBy { it.second }.take(5).forEach {
            println("PARITY worst %.5f  %s".format(it.second, it.first.take(60)))
        }

        assertTrue(
            "parity below the 0.99 floor: min=%.5f on %s".format(worst.second, worst.first),
            worst.second >= MIN_COSINE,
        )
    }

    private companion object {
        /**
         * Below this, the query is in a measurably different place in the space than
         * the cards. Measured skew of 0.89–0.99 was enough to flip a question, so this
         * is the bound that makes such a divergence a failure rather than a slow drift.
         */
        const val MIN_COSINE = 0.99
    }
}
