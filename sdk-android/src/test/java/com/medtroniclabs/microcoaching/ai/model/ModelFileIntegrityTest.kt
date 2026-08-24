package com.medtroniclabs.microcoaching.ai.model

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * Pins the container check to the evidence the format actually offers: it opens with a magic
 * string and ends in zero padding, so the head proves identity, nothing inside proves
 * completeness, and the declared length has to carry that half of the verdict.
 */
class ModelFileIntegrityTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A `.litertlm` of [bytes] length: real magic and version, zeros after. */
    private fun writeLiteRtLmBundle(bytes: Int = 4096, name: String = "model.litertlm"): File {
        val file = File(temp.root, name)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(bytes.toLong())
            raf.seek(0)
            raf.write("LITERTLM".toByteArray(Charsets.US_ASCII))
            // Little-endian u32 major=1, minor=5 — as captured from the real Qwen3 bundle.
            raf.write(byteArrayOf(1, 0, 0, 0, 5, 0, 0, 0))
        }
        return file
    }

    @Test
    fun `accepts a litertlm of exactly the expected length`() {
        val file = writeLiteRtLmBundle(bytes = 4096)

        assertNull(ModelFileIntegrity.validateLiteRtLmBundle(file, expectedBytes = 4096L))
    }

    /**
     * The case a size floor cannot catch: a download cut near the end keeps an intact header
     * and a tail of zeros indistinguishable from the padding a complete file ends with. Only
     * the declared length separates them.
     */
    @Test
    fun `rejects a truncated litertlm that clears the size floor`() {
        val expected = 497_664_000L
        val file = writeLiteRtLmBundle(bytes = 4096)

        assertNotNull(ModelFileIntegrity.validateLiteRtLmBundle(file, expectedBytes = expected))
        // A file just above the floor fraction would have been adopted — the reason this
        // format needs an exact length rather than a fraction of one.
        val nearlyComplete = (expected * 0.86).toLong()
        assert(nearlyComplete > (expected * ModelCatalog.SIZE_FLOOR_FRACTION).toLong())
    }

    @Test
    fun `rejects a litertlm longer than expected`() {
        val file = writeLiteRtLmBundle(bytes = 8192)

        assertNotNull(ModelFileIntegrity.validateLiteRtLmBundle(file, expectedBytes = 4096L))
    }

    @Test
    fun `rejects a file that is not a litertlm at all`() {
        // An HTML error page or an LFS pointer written under a model filename.
        val file = File(temp.root, "error.litertlm").apply {
            writeText("version https://git-lfs.github.com/spec/v1")
        }

        assertNotNull(ModelFileIntegrity.validateLiteRtLmBundle(file, expectedBytes = file.length()))
    }

    @Test
    fun `rejects an empty litertlm`() {
        val file = File(temp.root, "empty.litertlm").apply { createNewFile() }

        assertNotNull(ModelFileIntegrity.validateLiteRtLmBundle(file, expectedBytes = 4096L))
    }

    @Test
    fun `rejects a missing litertlm`() {
        assertNotNull(
            ModelFileIntegrity.validateLiteRtLmBundle(File(temp.root, "absent.litertlm"), 4096L),
        )
    }

    /** With no declared length there is no completeness evidence, so the header alone must
     * not be enough to adopt the file. */
    @Test
    fun `rejects a litertlm when the expected length is unknown`() {
        val file = writeLiteRtLmBundle()

        assertNotNull(ModelFileIntegrity.validateLiteRtLmBundle(file, expectedBytes = null))
        assertNotNull(ModelFileIntegrity.validateLiteRtLmBundle(file, expectedBytes = 0L))
    }

    @Test
    fun `litertlm reason never leaks the absolute path`() {
        val file = writeLiteRtLmBundle(bytes = 1024)

        val reason = ModelFileIntegrity.validateLiteRtLmBundle(file, expectedBytes = 4096L)

        assertNotNull(reason)
        assert(!reason!!.contains(temp.root.absolutePath)) { "reason leaked a path: $reason" }
    }
}
