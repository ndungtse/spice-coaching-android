package com.medtroniclabs.microcoaching.ai.model

import java.io.File
import java.io.RandomAccessFile

/**
 * Structural check for a downloaded `.litertlm` model container.
 *
 * The container opens with an ASCII magic string and ends in zero padding, which splits the
 * verdict in two: the head proves the bytes really are this format rather than an error page
 * or an LFS pointer, and since nothing inside proves the transfer finished, the declared
 * length has to carry that half. See [validateLiteRtLmBundle].
 */
internal object ModelFileIntegrity {

    /** Longest cause fragment appended to a reason; keeps the string UI-sized. */
    private const val MAX_DETAIL_CHARS = 80

    /** A `.litertlm` opens with this in ASCII, followed by little-endian u32 major/minor. */
    private val LITERTLM_MAGIC = "LITERTLM".toByteArray(Charsets.US_ASCII)

    /**
     * @param expectedBytes what a complete file weighs, per the serving host — the
     *   `Content-Length` observed for this variant, or the catalog's fallback. **Required.**
     * @return null when [file] is a complete container, otherwise a short reason safe to
     *   show a user: no absolute paths, no stack traces.
     *
     * Two checks, because neither alone is enough:
     *
     *  - **The magic** proves this is the container and not something wearing its name — an
     *    HTTP error body, an LFS pointer, a zero-length file.
     *  - **The length** is the only completeness evidence there is. A truncated download
     *    keeps an intact header and a tail of zeros indistinguishable from real padding,
     *    which is why a missing [expectedBytes] fails here instead of falling through to
     *    [ModelCatalog.SIZE_FLOOR_FRACTION] — a fraction of an expected size would adopt
     *    exactly the file this is meant to catch.
     *
     * Comparing against a locally-held size can reject a legitimately republished model,
     * which costs one re-download: that transfer is judged against the live
     * `Content-Length`, [ModelSizeProbe.recordObservedSize] caches it, and the next check
     * settles against the new size.
     */
    fun validateLiteRtLmBundle(file: File, expectedBytes: Long?): String? {
        if (!file.exists()) return "the model file is missing"
        val length = file.length()
        if (length == 0L) return "the model file is empty"
        if (length < LITERTLM_MAGIC.size) return "the model file is incomplete or damaged (too short to identify)"

        val magic = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                ByteArray(LITERTLM_MAGIC.size).also { raf.readFully(it) }
            }
        }.getOrElse { cause -> return "the model file could not be read${cause.detail(file)}" }

        if (!magic.contentEquals(LITERTLM_MAGIC)) {
            return "the model file is not a LiteRT-LM model (wrong file downloaded)"
        }

        if (expectedBytes == null || expectedBytes <= 0L) {
            return "the download size could not be confirmed, so the model may be incomplete"
        }
        if (length < expectedBytes) {
            return "the model file is incomplete ($length of $expectedBytes bytes)"
        }
        if (length > expectedBytes) {
            return "the model file is larger than expected ($length of $expectedBytes bytes)"
        }
        return null
    }

    /**
     * A parenthesised fragment of [this] cause with the file's location stripped —
     * some IO exceptions carry the full path in their message, and this reason is
     * rendered on screen.
     */
    private fun Throwable.detail(file: File): String {
        val raw = message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val scrubbed = raw
            .replace(file.absolutePath, file.name)
            .replace(file.parent.orEmpty(), "")
            .trim()
        if (scrubbed.isEmpty()) return ""
        return " (${scrubbed.take(MAX_DETAIL_CHARS)})"
    }
}
