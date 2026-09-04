package com.medtroniclabs.microcoaching.ai.embedding

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.medtroniclabs.microcoaching.ai.download.DownloadResult
import com.medtroniclabs.microcoaching.ai.download.ResumableHttpDownloader
import java.io.File

/**
 * Fetches the encoder's two files into the directory [KEY_OUTPUT_DIR] names, resuming
 * partial downloads through [ResumableHttpDownloader].
 *
 * Unlike [com.medtroniclabs.microcoaching.ai.voice.stt.SttModelDownloadWorker] and the
 * LLM worker this runs as an ordinary background worker with no foreground
 * notification: nothing in the UI is waiting on it and the CHW never asked for it, so
 * a progress notification would be noise about a capability they cannot see. If it
 * never finishes, chat stays on BM25-only, which is the behaviour with the flag off.
 *
 * The tokenizer is fetched first. It is 4.5 MB against the model's 171 MB, so a wrong
 * token or a revoked licence fails in a second instead of after a long transfer.
 */
internal class EncoderModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outputDirPath = inputData.getString(KEY_OUTPUT_DIR)
            ?: return Result.failure(workDataOf(KEY_ERROR to "missing output dir"))
        val token = inputData.getString(KEY_HF_TOKEN).orEmpty()
        val outputDir = File(outputDirPath).apply { mkdirs() }

        val headers = if (token.isNotBlank()) mapOf("Authorization" to "Bearer $token") else emptyMap()

        // Smallest file first: it shares the repo's gate, so a 401 costs 4.5 MB not 171.
        val steps = listOf(
            Triple(EncoderModel.TOKENIZER_URL, EncoderModel.TOKENIZER_FILE_NAME, EncoderModel.TOKENIZER_BYTES),
            Triple(EncoderModel.TFLITE_URL, EncoderModel.TFLITE_FILE_NAME, EncoderModel.TFLITE_BYTES),
        )

        var downloadedSoFar = 0L
        for ((url, name, expectedBytes) in steps) {
            val target = File(outputDir, name)
            if (target.length() == expectedBytes) {
                downloadedSoFar += expectedBytes
                continue
            }
            val alreadyDone = downloadedSoFar
            val outcome = ResumableHttpDownloader.download(
                url = url,
                outputFile = target,
                minValidBytes = (expectedBytes * MIN_VALID_FRACTION).toLong(),
                headers = headers,
                logTag = TAG,
            ) { _, bytes, _ ->
                // One progress track across both files, so the reported percentage does
                // not restart when the second download begins.
                val done = alreadyDone + bytes
                setProgress(
                    workDataOf(
                        KEY_PROGRESS to (done * 100 / EncoderModel.totalBytes).toInt(),
                        KEY_BYTES to done,
                        KEY_TOTAL to EncoderModel.totalBytes,
                    ),
                )
            }
            if (outcome is DownloadResult.Failure) {
                Log.w(TAG, "encoder download failed on $name: ${outcome.reason}")
                return Result.failure(workDataOf(KEY_ERROR to "$name: ${outcome.reason}"))
            }
            downloadedSoFar += expectedBytes
        }

        val missing = EncoderModel.missingFiles(outputDir)
        if (missing.isNotEmpty()) {
            // A short or overlong file here means the transfer completed against a
            // different artifact than these constants describe. Drop it so the next
            // attempt starts clean rather than resuming onto a mismatch.
            missing.forEach { File(outputDir, it).delete() }
            return Result.failure(workDataOf(KEY_ERROR to "incomplete after download: $missing"))
        }
        Log.i(TAG, "encoder ready at ${outputDir.absolutePath}")
        return Result.success(workDataOf(KEY_OUTPUT_DIR to outputDir.absolutePath))
    }

    companion object {
        private const val TAG = "EncoderModelDownload"

        const val KEY_OUTPUT_DIR = "output_dir"
        const val KEY_HF_TOKEN = "hf_token"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"

        /**
         * Floor below which a finished transfer is obviously not the model — an error
         * page, a Git LFS pointer. The exact-length check in
         * [EncoderModel.missingFiles] is what actually decides completeness.
         */
        private const val MIN_VALID_FRACTION = 0.85
    }
}
