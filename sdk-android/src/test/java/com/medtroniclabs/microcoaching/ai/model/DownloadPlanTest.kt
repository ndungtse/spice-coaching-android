package com.medtroniclabs.microcoaching.ai.model

import com.medtroniclabs.microcoaching.ai.embedding.EncoderModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * The arithmetic behind one progress bar over several files.
 *
 * This is the half of the combined download that can be tested without a device, and it
 * carries the two things a user would notice if they were wrong: the total they agreed to
 * before opting in, and a percentage that must not restart when the second file begins.
 */
class DownloadPlanTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val variant = ModelVariant(
        id = "test-llm",
        displayName = "Test LLM",
        fileName = "test-llm.litertlm",
        downloadUrl = "https://example.org/test-llm.litertlm",
        sizeInBytes = 500_000_000L,
        runtime = ModelRuntime.LITERT_LM,
        minDeviceMemoryGb = 3,
        requiresAccessToken = false,
        params = "0.6B",
    )

    private fun write(dir: File, path: String, bytes: Long) {
        val file = File(dir, path)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
    }

    // ── plan shape ────────────────────────────────────────────────────────────

    @Test
    fun `without the encoder the plan is the language model alone`() {
        val artifacts = DownloadPlan.artifacts(variant, includeEncoder = false)
        assertEquals(1, artifacts.size)
        assertEquals(DownloadPhase.LANGUAGE_MODEL, artifacts.single().phase)
        assertEquals("test-llm.litertlm", artifacts.single().fileName)
        assertNull("the language model lives at the files root", artifacts.single().relativeDir)
    }

    @Test
    fun `the language model downloads before the embeddings`() {
        // The label switches when this order is honoured; reversing it would show
        // "smarter search" first and "simple words" second.
        val artifacts = DownloadPlan.artifacts(variant, includeEncoder = true)
        assertEquals(
            listOf(
                DownloadPhase.LANGUAGE_MODEL,
                DownloadPhase.EMBEDDINGS,
                DownloadPhase.EMBEDDINGS,
            ),
            artifacts.map { it.phase },
        )
        assertEquals("test-llm.litertlm", artifacts[0].fileName)
        // Tokenizer before the 171 MB graph: both come from the same gated repo, so a
        // bad token fails after 4.5 MB rather than after the whole transfer.
        assertEquals(EncoderModel.TOKENIZER_FILE_NAME, artifacts[1].fileName)
        assertEquals(EncoderModel.TFLITE_FILE_NAME, artifacts[2].fileName)
    }

    @Test
    fun `only the language model uses the provider fallback chain`() {
        // Backend and Kaggle have nothing to offer for a Hugging Face encoder; trying
        // them would burn two failures per artifact.
        val artifacts = DownloadPlan.artifacts(variant, includeEncoder = true)
        assertTrue(artifacts[0].useProviderChain)
        assertTrue(artifacts.drop(1).none { it.useProviderChain })
    }

    @Test
    fun `the encoder artifacts are gated and land in their own directory`() {
        val encoder = DownloadPlan.artifacts(variant, includeEncoder = true).drop(1)
        assertTrue("the EmbeddingGemma repo is gated", encoder.all { it.requiresAccessToken })
        assertTrue(encoder.all { it.relativeDir == EncoderModel.DIR_NAME })
    }

    // ── the number the user agrees to ─────────────────────────────────────────

    @Test
    fun `the total is every artifact summed`() {
        val artifacts = DownloadPlan.artifacts(variant, includeEncoder = true)
        assertEquals(
            500_000_000L + EncoderModel.TOKENIZER_BYTES + EncoderModel.TFLITE_BYTES,
            DownloadPlan.totalBytes(artifacts),
        )
    }

    @Test
    fun `without the encoder the total is the language model alone`() {
        assertEquals(
            500_000_000L,
            DownloadPlan.totalBytes(DownloadPlan.artifacts(variant, includeEncoder = false)),
        )
    }

    // ── what is left to fetch ─────────────────────────────────────────────────

    @Test
    fun `a complete artifact is not fetched again`() {
        // The upgrade path: someone who already has "simple words" downloads the
        // embeddings only, not another 500 MB.
        val root = temp.newFolder("files")
        write(root, "test-llm.litertlm", 500_000_000L)
        val remaining = DownloadPlan.remaining(root, DownloadPlan.artifacts(variant, includeEncoder = true))
        assertEquals(
            listOf(EncoderModel.TOKENIZER_FILE_NAME, EncoderModel.TFLITE_FILE_NAME),
            remaining.map { it.fileName },
        )
    }

    @Test
    fun `a half-transferred artifact is still outstanding`() {
        val root = temp.newFolder("files")
        write(root, "test-llm.litertlm", 250_000_000L)
        val remaining = DownloadPlan.remaining(root, DownloadPlan.artifacts(variant, includeEncoder = false))
        assertEquals(1, remaining.size)
    }

    @Test
    fun `an artifact longer than expected is outstanding too`() {
        // Longer means the file upstream is not the one these constants describe, so
        // resuming onto it would be worse than starting over.
        val root = temp.newFolder("files")
        write(root, "test-llm.litertlm", 500_000_001L)
        assertEquals(1, DownloadPlan.remaining(root, DownloadPlan.artifacts(variant, includeEncoder = false)).size)
    }

    @Test
    fun `nothing outstanding when every artifact is complete`() {
        val root = temp.newFolder("files")
        write(root, "test-llm.litertlm", 500_000_000L)
        write(root, "${EncoderModel.DIR_NAME}/${EncoderModel.TOKENIZER_FILE_NAME}", EncoderModel.TOKENIZER_BYTES)
        write(root, "${EncoderModel.DIR_NAME}/${EncoderModel.TFLITE_FILE_NAME}", EncoderModel.TFLITE_BYTES)
        assertTrue(DownloadPlan.remaining(root, DownloadPlan.artifacts(variant, includeEncoder = true)).isEmpty())
    }

    // ── free-space pre-flight ─────────────────────────────────────────────────

    @Test
    fun `remaining bytes exclude what is already on disk`() {
        // Reserving space for bytes already written is how a resumed download gets
        // refused on a device that has room for it.
        val root = temp.newFolder("files")
        write(root, "test-llm.litertlm", 500_000_000L)
        assertEquals(
            EncoderModel.TOKENIZER_BYTES + EncoderModel.TFLITE_BYTES,
            DownloadPlan.remainingBytes(root, DownloadPlan.artifacts(variant, includeEncoder = true)),
        )
    }

    @Test
    fun `a partial artifact still counts its whole length`() {
        // A truncated file is discarded rather than resumed, so the space it occupies
        // is not credit against what still has to arrive.
        val root = temp.newFolder("files")
        write(root, "test-llm.litertlm", 250_000_000L)
        assertEquals(
            500_000_000L,
            DownloadPlan.remainingBytes(root, DownloadPlan.artifacts(variant, includeEncoder = false)),
        )
    }

    @Test
    fun `a finished plan needs no space`() {
        val root = temp.newFolder("files")
        write(root, "test-llm.litertlm", 500_000_000L)
        assertEquals(
            0L,
            DownloadPlan.remainingBytes(root, DownloadPlan.artifacts(variant, includeEncoder = false)),
        )
    }

    // ── file resolution ───────────────────────────────────────────────────────

    @Test
    fun `artifacts resolve to their target paths`() {
        val root = temp.newFolder("files")
        val artifacts = DownloadPlan.artifacts(variant, includeEncoder = true)
        assertEquals(File(root, "test-llm.litertlm"), DownloadPlan.fileFor(root, artifacts[0]))
        assertEquals(
            File(File(root, EncoderModel.DIR_NAME), EncoderModel.TOKENIZER_FILE_NAME),
            DownloadPlan.fileFor(root, artifacts[1]),
        )
    }

    // ── what is on disk ───────────────────────────────────────────────────────
    // The card states on-disk against expected ("498 MB of 681 MB"). Counting only the
    // language model there makes a finished download read as permanently short.

    @Test
    fun `present bytes cover every artifact, not just the language model`() {
        val root = temp.newFolder("files")
        write(root, "test-llm.litertlm", 500_000_000L)
        write(root, "${EncoderModel.DIR_NAME}/${EncoderModel.TOKENIZER_FILE_NAME}", EncoderModel.TOKENIZER_BYTES)
        write(root, "${EncoderModel.DIR_NAME}/${EncoderModel.TFLITE_FILE_NAME}", EncoderModel.TFLITE_BYTES)
        val artifacts = DownloadPlan.artifacts(variant, includeEncoder = true)
        assertEquals(
            DownloadPlan.totalBytes(artifacts),
            DownloadPlan.presentBytes(root, artifacts),
        )
    }

    @Test
    fun `a half-finished download reports only what completed`() {
        val root = temp.newFolder("files")
        write(root, "test-llm.litertlm", 500_000_000L)
        assertEquals(
            500_000_000L,
            DownloadPlan.presentBytes(root, DownloadPlan.artifacts(variant, includeEncoder = true)),
        )
    }

    @Test
    fun `a truncated artifact contributes nothing`() {
        // Consistent with `remaining`: a short file still has to arrive, so counting it
        // would make the card claim progress the download does not have.
        val root = temp.newFolder("files")
        write(root, "test-llm.litertlm", 250_000_000L)
        assertEquals(
            0L,
            DownloadPlan.presentBytes(root, DownloadPlan.artifacts(variant, includeEncoder = false)),
        )
    }

    @Test
    fun `nothing on disk is nothing present`() {
        val root = temp.newFolder("files")
        assertEquals(
            0L,
            DownloadPlan.presentBytes(root, DownloadPlan.artifacts(variant, includeEncoder = true)),
        )
    }
}
