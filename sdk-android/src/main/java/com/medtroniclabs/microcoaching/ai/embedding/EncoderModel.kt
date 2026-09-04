package com.medtroniclabs.microcoaching.ai.embedding

import java.io.File

/**
 * The query encoder's artifacts and on-disk layout.
 *
 * Deliberately not a [com.medtroniclabs.microcoaching.ai.model.ModelVariant]: that type
 * holds exactly one file and its whole pipeline — the offer prompt, `LocalModelChoice`,
 * `InferenceRouter` — is about the chat LLM. This is a two-file, non-generative model
 * that nothing offers to the user, so it follows the
 * [com.medtroniclabs.microcoaching.ai.voice.stt.SttModelManager] pattern instead: its
 * own directory, its own worker, its own ready flag.
 *
 * **`seq256`, not the longer sequence builds.** The weights are identical, so vectors
 * land in the same space the backend embedded the cards into, but the published CPU
 * figures are 66 ms / 110 MB resident at 256 tokens against 549 ms / 169 MB at 1024.
 * A CHW's question is a few dozen tokens; paying 8x the latency for headroom it never
 * uses would be the wrong trade.
 */
internal object EncoderModel {

    /** HF repo id, also the `model_id` the card vectors must have been built with. */
    const val REPO = "litert-community/embeddinggemma-300m"

    const val TFLITE_FILE_NAME = "embeddinggemma-300M_seq256_mixed-precision.tflite"
    const val TOKENIZER_FILE_NAME = "sentencepiece.model"

    /** Published lengths, read from the HF API on 2026-09-03. */
    const val TFLITE_BYTES = 179_131_736L
    const val TOKENIZER_BYTES = 4_683_319L

    const val TFLITE_URL = "https://huggingface.co/$REPO/resolve/main/$TFLITE_FILE_NAME"
    const val TOKENIZER_URL = "https://huggingface.co/$REPO/resolve/main/$TOKENIZER_FILE_NAME"

    /** Longest query the `seq256` graph accepts, including prompt, `<bos>` and `<eos>`. */
    const val MAX_TOKENS = 256

    /** EmbeddingGemma's output width. */
    const val EMBEDDING_DIM = 768

    /** Both files, with the length each must have to count as complete. */
    private val REQUIRED_FILES: List<Pair<String, Long>> = listOf(
        TFLITE_FILE_NAME to TFLITE_BYTES,
        TOKENIZER_FILE_NAME to TOKENIZER_BYTES,
    )

    /** Subdirectory of `getExternalFilesDir(null)` holding the encoder. */
    const val DIR_NAME = "embed"

    /** Total bytes to fetch, for the pre-flight free-space check. */
    val totalBytes: Long get() = REQUIRED_FILES.sumOf { it.second }

    /**
     * Files absent or of the wrong length, in declaration order.
     *
     * Exact length, not a floor: a resumed download that stopped mid-stream leaves a
     * short file, and a `.tflite` has no magic number this SDK can check, so length is
     * the only local completeness signal before the interpreter tries to load it. A
     * longer-than-expected file is equally wrong — it means the artifact upstream is
     * not the one these constants describe.
     */
    fun missingFiles(dir: File): List<String> =
        REQUIRED_FILES.filterNot { (name, bytes) -> File(dir, name).length() == bytes }
            .map { it.first }

    fun filesPresent(dir: File): Boolean = missingFiles(dir).isEmpty()
}
