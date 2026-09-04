package com.medtroniclabs.microcoaching.ai.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Presence checks for the encoder's two files. A partial download must never read as
 * ready: a truncated `.tflite` loads as a corrupt graph, and this is the only place
 * that can tell the difference before the interpreter does.
 */
class EncoderModelFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(dir: File, name: String, bytes: Long) {
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(1))
            // Sparse rather than 171 MB of zeros on every test run.
            java.io.RandomAccessFile(this, "rw").use { it.setLength(bytes) }
        }
    }

    @Test
    fun `both files at full size are present`() {
        val dir = temp.newFolder("embed")
        write(dir, EncoderModel.TFLITE_FILE_NAME, EncoderModel.TFLITE_BYTES)
        write(dir, EncoderModel.TOKENIZER_FILE_NAME, EncoderModel.TOKENIZER_BYTES)
        assertTrue(EncoderModel.filesPresent(dir))
        assertEquals(emptyList<String>(), EncoderModel.missingFiles(dir))
    }

    @Test
    fun `an empty directory is missing both files`() {
        val dir = temp.newFolder("embed")
        assertFalse(EncoderModel.filesPresent(dir))
        assertEquals(
            listOf(EncoderModel.TFLITE_FILE_NAME, EncoderModel.TOKENIZER_FILE_NAME),
            EncoderModel.missingFiles(dir),
        )
    }

    @Test
    fun `the tokenizer alone is not enough`() {
        val dir = temp.newFolder("embed")
        write(dir, EncoderModel.TOKENIZER_FILE_NAME, EncoderModel.TOKENIZER_BYTES)
        assertFalse(EncoderModel.filesPresent(dir))
        assertEquals(listOf(EncoderModel.TFLITE_FILE_NAME), EncoderModel.missingFiles(dir))
    }

    @Test
    fun `a truncated model is treated as missing`() {
        val dir = temp.newFolder("embed")
        // Half a download: present, wrong length. An interpreter would fail on it far
        // from here, so it must not count as ready.
        write(dir, EncoderModel.TFLITE_FILE_NAME, EncoderModel.TFLITE_BYTES / 2)
        write(dir, EncoderModel.TOKENIZER_FILE_NAME, EncoderModel.TOKENIZER_BYTES)
        assertFalse(EncoderModel.filesPresent(dir))
        assertEquals(listOf(EncoderModel.TFLITE_FILE_NAME), EncoderModel.missingFiles(dir))
    }

    @Test
    fun `a file longer than expected is also rejected`() {
        val dir = temp.newFolder("embed")
        write(dir, EncoderModel.TFLITE_FILE_NAME, EncoderModel.TFLITE_BYTES)
        write(dir, EncoderModel.TOKENIZER_FILE_NAME, EncoderModel.TOKENIZER_BYTES + 4096)
        assertFalse(EncoderModel.filesPresent(dir))
    }

    @Test
    fun `the declared sizes are the published ones`() {
        // Read from the HF API on 2026-09-03. The download's own Content-Length is what
        // actually gates completeness; these bound the free-space check and the
        // presence test, so a silent upstream reupload should fail loudly here.
        assertEquals(179_131_736L, EncoderModel.TFLITE_BYTES)
        assertEquals(4_683_319L, EncoderModel.TOKENIZER_BYTES)
        assertTrue(EncoderModel.TFLITE_URL.startsWith("https://huggingface.co/litert-community/embeddinggemma-300m/"))
        assertTrue(EncoderModel.TFLITE_URL.endsWith("embeddinggemma-300M_seq256_mixed-precision.tflite"))
    }
}
