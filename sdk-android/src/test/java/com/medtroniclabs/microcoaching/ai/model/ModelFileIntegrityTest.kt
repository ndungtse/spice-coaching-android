package com.medtroniclabs.microcoaching.ai.model

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pins the validator to structure rather than length: a `.task` is a zip, and a zip's
 * end-of-central-directory record sits at the end of the file, so a download that dies
 * near the end is plausibly-sized yet unopenable.
 */
class ModelFileIntegrityTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A zip with enough payload that truncating it still leaves a plausible size. */
    private fun writeValidBundle(name: String = "model.task"): File {
        val file = File(temp.root, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf("TF_LITE_PREFILL_DECODE", "TOKENIZER_MODEL", "METADATA").forEach { entry ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(ByteArray(8 * 1024) { it.toByte() })
                zip.closeEntry()
            }
        }
        return file
    }

    /**
     * Sparse replica of a production gemma3-270m-it-q8 bundle: the real length, the real
     * central-directory and EOCD bytes at their true offsets, zeros elsewhere. The
     * validator reads only the tail window and the directory signature, so this is
     * byte-equivalent to the real file.
     */
    private fun productionReplica(): File {
        val file = File(temp.root, "replica.task")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(PRODUCTION_LENGTH)
            raf.seek(PRODUCTION_LENGTH - PRODUCTION_TAIL.size)
            raf.write(PRODUCTION_TAIL)
        }
        return file
    }

    @Test
    fun `accepts a readable zip bundle`() {
        assertNull(ModelFileIntegrity.validateTaskBundle(writeValidBundle()))
    }

    /**
     * The layout that matters: production bundles carry leading padding, so no local file
     * header sits at offset zero. Android's `java.util.zip.ZipFile` rejects that layout at
     * open while the engine loads it — the validator must side with the engine.
     */
    @Test
    fun `accepts the production bundle layout`() {
        assertNull(ModelFileIntegrity.validateTaskBundle(productionReplica()))
    }

    @Test
    fun `rejects the production bundle layout when its tail is cut`() {
        val file = productionReplica()
        RandomAccessFile(file, "rw").use { it.setLength(PRODUCTION_LENGTH - 16) }

        assertNotNull(ModelFileIntegrity.validateTaskBundle(file))
    }

    @Test
    fun `rejects a bundle truncated near the end`() {
        val file = writeValidBundle()
        val full = file.length()
        // Dropping the tail takes the central directory with it.
        RandomAccessFile(file, "rw").use { it.setLength((full * 0.9).toLong()) }

        assertNotNull(ModelFileIntegrity.validateTaskBundle(file))
    }

    /**
     * Local file headers must not influence the verdict — real bundles need not begin
     * with one, and the engine never requires it. Only the directory at the tail decides.
     */
    @Test
    fun `accepts a bundle whose local headers are unreadable`() {
        val file = writeValidBundle()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(ByteArray(64) { 0 })
        }

        assertNull(ModelFileIntegrity.validateTaskBundle(file))
    }

    @Test
    fun `rejects an empty file`() {
        val file = File(temp.root, "empty.task").apply { createNewFile() }

        assertNotNull(ModelFileIntegrity.validateTaskBundle(file))
    }

    @Test
    fun `rejects a file that is not a zip at all`() {
        // An HTTP error body written to disk under a model filename.
        val file = File(temp.root, "error.task").apply {
            writeText("Access to model litert-community/gemma-3-270m-it is restricted.")
        }

        assertNotNull(ModelFileIntegrity.validateTaskBundle(file))
    }

    @Test
    fun `rejects a missing file`() {
        assertNotNull(ModelFileIntegrity.validateTaskBundle(File(temp.root, "absent.task")))
    }

    @Test
    fun `reason never leaks the absolute path`() {
        val file = File(temp.root, "error.task").apply { writeText("not a zip") }

        val reason = ModelFileIntegrity.validateTaskBundle(file)

        assertNotNull(reason)
        // The reason is rendered on screen.
        assert(!reason!!.contains(temp.root.absolutePath)) { "reason leaked a path: $reason" }
    }

    private companion object {
        /** Byte length of the production bundle the tail below was captured from. */
        const val PRODUCTION_LENGTH = 303_950_933L

        /** The production bundle's central directory + EOCD, verbatim. */
        val PRODUCTION_TAIL: ByteArray = (
            "504b01021403140000000000072d0e5b9b123046f05dd611f05dd6111600000000000000000000008001040000005446" +
                "5f4c4954455f50524546494c4c5f4445434f4445504b01021403140000000000072d0e5bfc06ef17b08c4700b08c4700" +
                "0f000000000000000000000080012b5ed611544f4b454e495a45525f4d4f44454c504b01021403140000000000072d0e" +
                "5b5f086505580000005800000008000000000000000000000080010aeb1d124d45544144415441504b05060000000003" +
                "000300b700000088eb1d120000"
            ).decodeHex()

        fun String.decodeHex(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
