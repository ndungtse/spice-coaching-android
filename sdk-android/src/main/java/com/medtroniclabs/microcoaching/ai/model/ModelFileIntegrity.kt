package com.medtroniclabs.microcoaching.ai.model

import java.io.File
import java.io.RandomAccessFile

/**
 * Structural check for a downloaded MediaPipe `.task` bundle.
 *
 * A `.task` is a zip archive, so its end-of-central-directory (EOCD) record sits in the
 * file's final bytes — a transfer that stops short loses it, however plausible the byte
 * count looks. Finding a coherent EOCD is therefore the completeness test.
 *
 * The check must never be stricter than the engine: a file the engine would load must
 * pass. The engine's zip reader needs only the EOCD and the central directory it points
 * at, and these bundles do not necessarily begin with a local file header (the first
 * entry can sit behind leading padding). Platform zip readers reject exactly that —
 * Android's `java.util.zip.ZipFile` requires a local file header at offset zero — so
 * this check reads the archive structure itself and opens the file with no zip library.
 */
internal object ModelFileIntegrity {

    /** Longest cause fragment appended to a reason; keeps the string UI-sized. */
    private const val MAX_DETAIL_CHARS = 80

    private const val EOCD_SIZE = 22
    private const val MAX_COMMENT_SIZE = 0xFFFF
    private const val SIGNATURE_SIZE = 4
    private val EOCD_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val CD_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x01, 0x02)

    /** 16/32-bit fields at their max defer to a zip64 record this check doesn't parse. */
    private const val ZIP64_SENTINEL_16 = 0xFFFF
    private const val ZIP64_SENTINEL_32 = 0xFFFFFFFFL

    /**
     * @return null when [file] ends in a coherent archive directory, otherwise a short
     *   reason safe to show a user — no absolute paths, no stack traces. Callers log the
     *   reason alongside the file's length.
     */
    fun validateTaskBundle(file: File): String? {
        if (!file.exists()) return "the model file is missing"
        val length = file.length()
        if (length == 0L) return "the model file is empty"
        if (length < EOCD_SIZE) return "the model file is incomplete or damaged (no room for an archive directory)"
        return runCatching { checkArchiveTail(file, length) }
            .getOrElse { cause -> "the model file could not be read${cause.detail(file)}" }
    }

    private fun checkArchiveTail(file: File, length: Long): String? =
        RandomAccessFile(file, "r").use { raf ->
            // The EOCD ends the file, preceded at most by a comment of bounded size.
            val windowSize = minOf(length, (EOCD_SIZE + MAX_COMMENT_SIZE).toLong()).toInt()
            val windowStart = length - windowSize
            val window = ByteArray(windowSize)
            raf.seek(windowStart)
            raf.readFully(window)

            // Scan backward for an EOCD whose comment length places it exactly at EOF,
            // so a signature occurring by chance inside model weights doesn't match.
            var at = windowSize - EOCD_SIZE
            while (at >= 0) {
                if (hasSignature(window, at, EOCD_SIGNATURE) &&
                    windowStart + at + EOCD_SIZE + readLe16(window, at + 20) == length
                ) {
                    break
                }
                at--
            }
            if (at < 0) return@use "the model file is incomplete or damaged (end-of-archive record missing)"

            val entryCount = readLe16(window, at + 10)
            val directorySize = readLe32(window, at + 12)
            val recordedOffset = readLe32(window, at + 16)
            if (entryCount == ZIP64_SENTINEL_16 ||
                directorySize == ZIP64_SENTINEL_32 ||
                recordedOffset == ZIP64_SENTINEL_32
            ) {
                // zip64 bundle — the EOCD's presence already proves the tail arrived.
                return@use null
            }
            if (entryCount == 0) return@use "the model file contains no data"

            // The directory sits immediately before the EOCD. Leading padding can make
            // its recorded offset smaller than its physical position, never larger.
            val directoryAt = windowStart + at - directorySize
            if (directoryAt < 0 || directoryAt < recordedOffset) {
                return@use "the model file is incomplete or damaged (directory extends past the end)"
            }
            raf.seek(directoryAt)
            val signature = ByteArray(SIGNATURE_SIZE)
            raf.readFully(signature)
            if (!hasSignature(signature, 0, CD_SIGNATURE)) {
                return@use "the model file is incomplete or damaged (directory not where recorded)"
            }
            null
        }

    private fun hasSignature(bytes: ByteArray, at: Int, signature: ByteArray): Boolean {
        for (i in signature.indices) {
            if (bytes[at + i] != signature[i]) return false
        }
        return true
    }

    private fun readLe16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(bytes: ByteArray, at: Int): Long =
        readLe16(bytes, at).toLong() or (readLe16(bytes, at + 2).toLong() shl 16)

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
