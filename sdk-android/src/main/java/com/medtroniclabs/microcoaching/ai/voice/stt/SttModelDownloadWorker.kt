package com.medtroniclabs.microcoaching.ai.voice.stt

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.medtroniclabs.microcoaching.ai.download.DownloadResult
import com.medtroniclabs.microcoaching.ai.download.ResumableHttpDownloader
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads a sherpa-onnx STT model archive and extracts it into the configured
 * output directory. Foreground-service worker so the OS keeps it alive when the host
 * app is backgrounded — same pattern as the Gemma
 * [com.medtroniclabs.microcoaching.ai.model.ModelDownloadWorker]. Generic over URL +
 * output dir (Bengali is the only archive today).
 *
 * Flow:
 *   1. setForeground (notification visible)
 *   2. Stream-download `.tar.bz2` with HTTP `Range` resume
 *   3. Extract via Commons Compress (BZip2 → Tar)
 *   4. Verify the four required files exist (encoder/decoder/joiner/tokens)
 *   5. Delete the archive and return success ([SttModelManager] writes the ready flag
 *      on the observed SUCCEEDED — unlike the Gemma worker, this one doesn't persist it)
 *
 * An incomplete archive is removed by [ResumableHttpDownloader] for a clean retry; a
 * complete archive that fails extraction is kept, and re-extraction is idempotent.
 */
class SttModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(progress = 0, bytesDownloaded = 0L, totalBytes = 0L)

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Log.w(TAG, "setForeground at start failed: ${it.message}") }

        val url = inputData.getString(KEY_URL)
            ?: return Result.failure(workDataOf(KEY_ERROR to "missing url"))
        val outputDirPath = inputData.getString(KEY_OUTPUT_DIR)
            ?: return Result.failure(workDataOf(KEY_ERROR to "missing output dir"))
        val outputDir = File(outputDirPath)
        outputDir.mkdirs()
        val archiveFile = File(outputDir, ARCHIVE_FILENAME)

        // 1) Download with resume + throttled progress reporting.
        val downloadResult = ResumableHttpDownloader.download(
            url = url,
            outputFile = archiveFile,
            minValidBytes = MIN_VALID_ARCHIVE_SIZE_BYTES,
            logTag = TAG,
        ) { percent, bytesDownloaded, totalBytes -> emitProgress(percent, bytesDownloaded, totalBytes) }
        if (downloadResult is DownloadResult.Failure) {
            return Result.failure(workDataOf(KEY_ERROR to downloadResult.reason))
        }

        // 2) Extract the archive.
        emitExtracting()
        val extractOutcome = runCatching { extract(archiveFile, outputDir) }
        if (extractOutcome.isFailure) {
            val msg = extractOutcome.exceptionOrNull()?.message ?: "extraction failed"
            Log.e(TAG, "extract failed: $msg")
            return Result.failure(workDataOf(KEY_ERROR to msg))
        }

        // 3) Verify required files.
        val missing = REQUIRED_FILES.filterNot { File(outputDir, it).exists() }
        if (missing.isNotEmpty()) {
            return Result.failure(
                workDataOf(KEY_ERROR to "missing files after extract: $missing"),
            )
        }

        // 4) Cleanup archive, return success with the model dir path.
        archiveFile.delete()
        return Result.success(workDataOf(KEY_OUTPUT_DIR to outputDir.absolutePath))
    }

    // ── Extract ───────────────────────────────────────────────────────────────

    /**
     * Extract `archive` into `targetDir`, flattening any single-level top
     * directory (sherpa's tarballs wrap everything in
     * `sherpa-onnx-streaming-…/`). Skips unwanted files (READMEs, test wavs).
     */
    private fun extract(archive: File, targetDir: File) {
        BufferedInputStream(FileInputStream(archive)).use { fileIn ->
            BZip2CompressorInputStream(fileIn).use { bz2In ->
                TarArchiveInputStream(bz2In).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        val rawName = entry.name
                        // Strip the leading sherpa-onnx-streaming-…/ prefix.
                        val stripped = rawName.substringAfter('/', missingDelimiterValue = rawName)
                        if (entry.isDirectory || stripped.isBlank() || !shouldKeep(stripped)) {
                            entry = tarIn.nextTarEntry
                            continue
                        }
                        val outFile = File(targetDir, stripped)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> tarIn.copyTo(out) }
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
    }

    private fun shouldKeep(strippedName: String): Boolean {
        // Keep top-level files we care about; drop test wavs + READMEs.
        if (strippedName.startsWith("test_wavs/")) return false
        if (strippedName.endsWith(".md", ignoreCase = true)) return false
        return true
    }

    // ── Foreground info + progress emission ───────────────────────────────────

    private fun buildForegroundInfo(
        progress: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
        extracting: Boolean = false,
    ): ForegroundInfo {
        val notification = SttModelNotifier.buildNotification(
            context = applicationContext,
            progress = progress,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            extracting = extracting,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                SttModelNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(SttModelNotifier.NOTIFICATION_ID, notification)
        }
    }

    private suspend fun emitProgress(
        percent: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
    ) {
        setProgress(
            workDataOf(
                KEY_PROGRESS to percent,
                KEY_BYTES_DOWNLOADED to bytesDownloaded,
                KEY_TOTAL_BYTES to totalBytes,
            ),
        )
        runCatching {
            setForeground(buildForegroundInfo(percent, bytesDownloaded, totalBytes))
        }.onFailure {
            Log.w(TAG, "setForeground progress update failed: ${it.message}")
        }
    }

    private suspend fun emitExtracting() {
        setProgress(workDataOf(KEY_EXTRACTING to true))
        runCatching {
            setForeground(
                buildForegroundInfo(
                    progress = 99,
                    bytesDownloaded = 0L,
                    totalBytes = 0L,
                    extracting = true,
                ),
            )
        }.onFailure {
            Log.w(TAG, "setForeground extracting update failed: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "SttModelDownloadWorker"
        private const val MIN_VALID_ARCHIVE_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB
        private const val ARCHIVE_FILENAME = "_archive.tar.bz2"

        /** Files that must exist after extraction for the model to be usable by sherpa-onnx. */
        private val REQUIRED_FILES = listOf(
            "encoder.onnx",
            "decoder.onnx",
            "joiner.onnx",
            "tokens.txt",
        )

        // ── WorkManager input keys ────────────────────────────────────────────
        const val KEY_URL = "url"
        const val KEY_OUTPUT_DIR = "output_dir"

        // ── WorkManager progress / output keys ────────────────────────────────
        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_EXTRACTING = "extracting"
        const val KEY_ERROR = "error"
    }
}
