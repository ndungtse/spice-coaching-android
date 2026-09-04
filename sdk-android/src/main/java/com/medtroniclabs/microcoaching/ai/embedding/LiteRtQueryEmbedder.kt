package com.medtroniclabs.microcoaching.ai.embedding

import android.util.Log
import com.medtroniclabs.microcoaching.ai.inference.QueryEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * EmbeddingGemma-300m as a [QueryEmbedder], run on the CPU through LiteRT.
 *
 * The graph (`embeddinggemma-300M_seq256_mixed-precision.tflite`) is a single
 * `[1, 256]` INT32 input of token ids and a single `[1, 768]` FLOAT32 output — pooling
 * and both projection layers are inside it, and there is no attention-mask input, so
 * padding is what tells the model where the question ends.
 *
 * Everything expensive is deferred and shared: the 171 MB of weights are mapped on
 * first use, one query is encoded at a time behind [lock] (the interpreter is not
 * thread-safe and a second concurrent copy would double the resident cost), and all of
 * it runs off the main thread.
 *
 * Every failure path returns null rather than throwing. A null query vector makes
 * [com.medtroniclabs.microcoaching.ui.chat.denseCandidates] skip the dense branch, so
 * chat falls back to the BM25 behaviour it has with the flag off — the encoder is an
 * optional improvement and must never be able to break the answer path.
 */
internal class LiteRtQueryEmbedder(private val modelDir: File) : QueryEmbedder {

    private val lock = Mutex()

    @Volatile
    private var loadFailed = false

    private var interpreter: Interpreter? = null
    private var tokenizer: SentencePieceBpeTokenizer? = null
    private var padId: Int = 0

    override val isAvailable: Boolean
        get() = !loadFailed && EncoderModel.filesPresent(modelDir)

    override suspend fun embed(text: String): FloatArray? {
        if (text.isBlank() || !isAvailable) return null
        return withContext(Dispatchers.Default) {
            lock.withLock {
                val ready = runCatching { load() }.getOrElse { cause ->
                    // A corrupt or truncated graph throws here; one attempt is enough,
                    // since retrying maps the same bytes again.
                    Log.e(TAG, "encoder load failed, disabling dense branch: ${cause.message}")
                    loadFailed = true
                    close()
                    false
                }
                if (!ready) return@withLock null
                runCatching { encode(text) }.getOrElse { cause ->
                    Log.w(TAG, "embed failed: ${cause.message}")
                    null
                }
            }
        }
    }

    /** Maps the weights and builds the tokenizer. Idempotent; called under [lock]. */
    private fun load(): Boolean {
        if (interpreter != null && tokenizer != null) return true
        val missing = EncoderModel.missingFiles(modelDir)
        if (missing.isNotEmpty()) {
            Log.i(TAG, "encoder files missing: $missing")
            return false
        }
        val options = Interpreter.Options().apply {
            // CPU only. The published GPU figures for this model are ~762 MB resident
            // against 110 MB on CPU, which no target tier can hold.
            numThreads = INFERENCE_THREADS
        }
        val loaded = Interpreter(File(modelDir, EncoderModel.TFLITE_FILE_NAME), options)
        val vocab = SentencePieceVocab.fromModelProto(
            File(modelDir, EncoderModel.TOKENIZER_FILE_NAME).readBytes(),
        )
        interpreter = loaded
        tokenizer = SentencePieceBpeTokenizer(vocab)
        padId = vocab.padId
        Log.i(
            TAG,
            "encoder loaded: input=${loaded.getInputTensor(0).shape().toList()} " +
                "output=${loaded.getOutputTensor(0).shape().toList()}",
        )
        return true
    }

    private fun encode(text: String): FloatArray? {
        val interpreter = this.interpreter ?: return null
        val tokenizer = this.tokenizer ?: return null

        val ids = tokenizer.encode(QueryEncoding.promptFor(text))
        val window = QueryEncoding.window(ids, size = EncoderModel.MAX_TOKENS, padId = padId)

        val input = arrayOf(window)
        val output = arrayOf(FloatArray(EncoderModel.EMBEDDING_DIM))
        interpreter.run(input, output)

        // The graph may or may not end in its own Normalize module; normalizing is
        // idempotent, and the dense index reads cosine as a dot product, so unit
        // length is required either way.
        return QueryEncoding.l2Normalize(output[0])
    }

    /** Releases the mapped weights. Safe to call more than once. */
    fun close() {
        runCatching { interpreter?.close() }
        interpreter = null
        tokenizer = null
    }

    private companion object {
        const val TAG = "LiteRtQueryEmbedder"

        /**
         * Two threads: the published single-query CPU latency at this sequence length
         * is 66 ms, and a chat turn encodes one question, so more threads would
         * contend with the UI for no gain the CHW can perceive.
         */
        const val INFERENCE_THREADS = 2
    }
}
