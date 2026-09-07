package com.medtroniclabs.microcoaching.ai.model

import com.medtroniclabs.microcoaching.ai.embedding.EncoderModel
import java.io.File

/**
 * Which part of the on-device setup is transferring. The user sees one progress bar; the
 * phase is what changes the label under it once the language model has landed.
 *
 * Public because it rides on [ModelState], which hosts observe.
 */
enum class DownloadPhase {
    /** The chat LLM — "simple words". */
    LANGUAGE_MODEL,

    /** The query encoder and its tokenizer — "smarter search". */
    EMBEDDINGS,
}

/**
 * One file to fetch.
 *
 * @property relativeDir subdirectory of `getExternalFilesDir(null)`, or null for its root.
 *           The LLM sits at the root because [ModelManager.findLocalModel] resolves it by
 *           exact filename there.
 * @property useProviderChain whether to try the Backend → Hugging Face → Kaggle fallback.
 *           Only the LLM does; the encoder exists on Hugging Face alone, so walking the
 *           chain for it would just burn two guaranteed failures per file.
 */
internal data class DownloadArtifact(
    val phase: DownloadPhase,
    val fileName: String,
    val expectedBytes: Long,
    val relativeDir: String?,
    val downloadUrl: String,
    val requiresAccessToken: Boolean,
    val useProviderChain: Boolean,
)

/**
 * The set of files behind a single "simple words" download, and the arithmetic over them.
 *
 * Everything the user is told about the download is computed here: the total they agree to
 * before opting in, what is still outstanding after a partial transfer, and the space to
 * reserve. Keeping it pure is what makes those numbers testable without a device — the
 * worker that consumes them cannot be unit-tested at all.
 */
internal object DownloadPlan {

    /**
     * Files to fetch, in transfer order.
     *
     * The language model comes first because it is the capability the user actually asked
     * for, and the label they see should name it. Within the encoder the 4.5 MB tokenizer
     * precedes the 171 MB graph: both come from the same gated repo, so a missing or
     * revoked token fails in a second instead of after the whole transfer.
     */
    fun artifacts(variant: ModelVariant, includeEncoder: Boolean): List<DownloadArtifact> {
        val languageModel = DownloadArtifact(
            phase = DownloadPhase.LANGUAGE_MODEL,
            fileName = variant.fileName,
            expectedBytes = variant.sizeInBytes,
            relativeDir = null,
            downloadUrl = variant.downloadUrl,
            requiresAccessToken = variant.requiresAccessToken,
            useProviderChain = true,
        )
        if (!includeEncoder) return listOf(languageModel)

        fun encoder(fileName: String, bytes: Long, url: String) = DownloadArtifact(
            phase = DownloadPhase.EMBEDDINGS,
            fileName = fileName,
            expectedBytes = bytes,
            relativeDir = EncoderModel.DIR_NAME,
            downloadUrl = url,
            requiresAccessToken = true,
            useProviderChain = false,
        )
        return listOf(
            languageModel,
            encoder(EncoderModel.TOKENIZER_FILE_NAME, EncoderModel.TOKENIZER_BYTES, EncoderModel.TOKENIZER_URL),
            encoder(EncoderModel.TFLITE_FILE_NAME, EncoderModel.TFLITE_BYTES, EncoderModel.TFLITE_URL),
        )
    }

    /** Where [artifact] belongs under [root] (`getExternalFilesDir(null)`). */
    fun fileFor(root: File, artifact: DownloadArtifact): File =
        artifact.relativeDir?.let { File(File(root, it), artifact.fileName) }
            ?: File(root, artifact.fileName)

    /** Every artifact's length — the one-time download figure shown before opting in. */
    fun totalBytes(artifacts: List<DownloadArtifact>): Long = artifacts.sumOf { it.expectedBytes }

    /**
     * Artifacts not yet on disk at their exact expected length, in transfer order.
     *
     * Exact length, not a floor: a resumed transfer that stopped mid-stream leaves a short
     * file, and a longer one means the artifact upstream is not what these constants
     * describe. Either way it still has to arrive. This is also the upgrade path — someone
     * who already has the language model fetches only the encoder.
     */
    fun remaining(root: File, artifacts: List<DownloadArtifact>): List<DownloadArtifact> =
        artifacts.filterNot { fileFor(root, it).length() == it.expectedBytes }

    /**
     * Bytes still to transfer, for the free-space pre-flight.
     *
     * A partial file counts its whole length rather than the difference: the download
     * resumes onto it by HTTP `Range`, but the check has to hold for the case where it is
     * discarded and refetched.
     */
    fun remainingBytes(root: File, artifacts: List<DownloadArtifact>): Long =
        remaining(root, artifacts).sumOf { it.expectedBytes }

    /**
     * Bytes of the plan already complete on disk — the counterpart of [remainingBytes], and
     * what the card compares against the total.
     *
     * A partial artifact contributes nothing, matching [remaining]: it still has to arrive,
     * so counting it would claim progress the download does not have.
     */
    fun presentBytes(root: File, artifacts: List<DownloadArtifact>): Long =
        artifacts.filter { fileFor(root, it).length() == it.expectedBytes }.sumOf { it.expectedBytes }

    /** Every artifact's file under [root], present or not. */
    fun files(root: File, artifacts: List<DownloadArtifact>): List<File> =
        artifacts.map { fileFor(root, it) }
}
