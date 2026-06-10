package com.medtroniclabs.microcoaching.ai.model

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import com.medtroniclabs.microcoaching.BuildConfig
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that downloads the on-device Gemma model, trying each configured
 * [ModelProvider] in order and falling back to the next on failure.
 *
 * **Provider fallback order** (default): Backend → HuggingFace
 *
 * Features:
 *   - Sequential fallback: moves to next provider if the current one fails
 *   - Resumable: uses HTTP `Range` requests if a partial file already exists
 *   - Progress: reports 0–99 via [KEY_PROGRESS] while streaming; 100 on success
 *   - Survives process death — WorkManager re-enqueues on restart
 *
 * On success, [KEY_FILE_PATH] in output data holds the absolute path to the model file.
 * [ModelManager] observes this worker's [WorkInfo][androidx.work.WorkInfo] to update [ModelState].
 *
 * Backend endpoint: `{backendUrl}/api/v1/models/gemma/download`
 * HuggingFace endpoint: [MicroCoachingConfig.huggingFaceModelUrl] (default: Gemma3-1B-IT INT4)
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val providers = inputData.getStringArray(KEY_PROVIDERS)
            ?.mapNotNull { ModelProvider.fromKey(it) }
            ?.ifEmpty { ModelProvider.DEFAULT_ORDER }
            ?: ModelProvider.DEFAULT_ORDER

        val outputDir = applicationContext.getExternalFilesDir(null)
            ?: return Result.failure(workDataOf(KEY_ERROR to "External storage unavailable"))

        val errors = mutableListOf<String>()

        for (provider in providers) {
            val providerName = provider::class.simpleName ?: "Unknown"
            Log.i(TAG, "Attempting download from provider: $providerName")

            val outcome = when (provider) {
                ModelProvider.Backend -> tryBackend(outputDir)
                ModelProvider.HuggingFace -> tryHuggingFace(outputDir)
            }

            when (outcome) {
                is DownloadOutcome.Success -> {
                    Log.i(TAG, "Download complete via $providerName → ${outcome.file.name} (${outcome.file.length() / 1_048_576} MB)")
                    return Result.success(
                        workDataOf(
                            KEY_PROGRESS to 100,
                            KEY_FILE_PATH to outcome.file.absolutePath,
                        )
                    )
                }
                is DownloadOutcome.Failure -> {
                    val msg = "[$providerName] ${outcome.reason}"
                    Log.w(TAG, "$msg — trying next provider")
                    errors += msg
                }
            }
        }

        val combinedError = errors.joinToString(" | ")
        Log.e(TAG, "All providers failed: $combinedError")
        return Result.failure(workDataOf(KEY_ERROR to combinedError))
    }

    // ── Backend provider ──────────────────────────────────────────────────────

    private suspend fun tryBackend(outputDir: File): DownloadOutcome {
        val backendUrl = inputData.getString(KEY_BACKEND_URL)
        if (backendUrl.isNullOrBlank()) {
            return DownloadOutcome.Failure("backendUrl not configured — set via Builder.backendUrl()")
        }

        val authToken = inputData.getString(KEY_AUTH_TOKEN) ?: ""
        val downloadUrl = "${backendUrl.trimEnd('/')}/api/v1/models/gemma/download"
        val outputFile = File(outputDir, BACKEND_MODEL_FILENAME)

        val headers = buildMap<String, String> {
            if (authToken.isNotBlank()) put("Authorization", "Bearer $authToken")
        }

        return streamDownload(url = downloadUrl, headers = headers, outputFile = outputFile)
    }

    // ── HuggingFace provider ──────────────────────────────────────────────────

    private suspend fun tryHuggingFace(outputDir: File): DownloadOutcome {
        val hfUrl = inputData.getString(KEY_HF_URL)
            ?.takeIf { it.isNotBlank() }
            ?: ModelProvider.DEFAULT_HF_MODEL_URL
        val hfToken = inputData.getString(KEY_HF_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: ""

        // Derive the output filename from the URL so .task and .litertlm both work correctly.
        val filename = hfUrl.substringAfterLast("/").substringBefore("?")
            .takeIf { it.endsWith(".task") || it.endsWith(".litertlm") }
            ?: ModelProvider.DEFAULT_HF_MODEL_FILENAME
        val outputFile = File(outputDir, filename)

        if (hfToken.isBlank()) {
            Log.w(TAG, "[HF] token=<BLANK> — download will fail for gated models")
        } else if (BuildConfig.DEBUG) {
            Log.d(TAG, "[HF] token=${hfToken.take(1)}…${hfToken.takeLast(1)} (len=${hfToken.length})")
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[HF] url=$hfUrl")
            Log.d(TAG, "[HF] outputFile=${outputFile.absolutePath}")
        }

        val headers = buildMap<String, String> {
            if (hfToken.isNotBlank()) put("Authorization", "Bearer $hfToken")
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[HF] Authorization header present=${headers.containsKey("Authorization")}")
        }

        return streamDownload(url = hfUrl, headers = headers, outputFile = outputFile)
    }

    // ── Shared streaming download ─────────────────────────────────────────────

    /**
     * Streams [url] to [outputFile], resuming from existing bytes if the server supports Range.
     * Reports progress (0–99) via WorkManager [setProgress].
     */
    private suspend fun streamDownload(
        url: String,
        headers: Map<String, String>,
        outputFile: File,
    ): DownloadOutcome = runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()

        // Resume from partial file
        val existingBytes = if (outputFile.exists()) outputFile.length() else 0L
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()

        // Always close the response to avoid OkHttp connection leaks on early returns.
        response.use { resp ->
            val isPartialContent = resp.code == 206
            Log.d(TAG, "streamDownload → HTTP ${resp.code} ${resp.message} | url=$url")

            if (resp.code == 416) {
                // Range not satisfiable — server rejected our resume offset.
                // If the local file is already ≥ 500 MB it is almost certainly the complete model;
                // treat it as a successful download so inference can proceed.
                // If the file is smaller, it is a corrupt partial — delete it so the next attempt
                // starts from zero.
                val localSize = outputFile.length()
                return@runCatching if (localSize >= MIN_VALID_MODEL_SIZE_BYTES) {
                    Log.i(TAG, "HTTP 416 — local file is ${localSize / 1_048_576} MB, treating as already complete")
                    DownloadOutcome.Success(outputFile)
                } else {
                    outputFile.delete()
                    Log.w(TAG, "HTTP 416 — partial file deleted (${localSize / 1_048_576} MB), retry to start fresh")
                    DownloadOutcome.Failure("HTTP 416: partial file removed — retry to restart download")
                }
            }

            if (!resp.isSuccessful && !isPartialContent) {
                if (BuildConfig.DEBUG) {
                    val errorBody = resp.body?.string()?.take(500) ?: "<no body>"
                    Log.e(TAG, "streamDownload failed | HTTP ${resp.code} | body=$errorBody | headers=${resp.headers}")
                } else {
                    Log.e(TAG, "streamDownload failed | HTTP ${resp.code}: ${resp.message}")
                }
                return@runCatching DownloadOutcome.Failure("HTTP ${resp.code}: ${resp.message}")
            }

            val body = resp.body
                ?: return@runCatching DownloadOutcome.Failure("Empty response body")

            val contentLength = body.contentLength()
            val totalBytes = if (contentLength > 0L) contentLength + existingBytes else 0L
            val appendToFile = existingBytes > 0L && isPartialContent

            if (existingBytes > 0L) {
                Log.i(TAG, "Resuming download from byte $existingBytes (${existingBytes / 1_048_576} MB already present)")
            }

            body.byteStream().use { input ->
                FileOutputStream(outputFile, appendToFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalRead = existingBytes

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (totalBytes > 0L) {
                            val progress = ((totalRead * 100L) / totalBytes).toInt().coerceIn(0, 99)
                            setProgress(workDataOf(KEY_PROGRESS to progress))
                        }
                    }
                    output.flush()
                }
            }

            // Verify the download is complete. HuggingFace often uses chunked transfer
            // (no Content-Length), so the loop above may exit on a dropped connection with
            // a partial file. Treat anything below the minimum valid size as a failure
            // and delete it so the next attempt starts fresh.
            val finalSize = outputFile.length()
            if (totalBytes > 0L && finalSize < totalBytes) {
                outputFile.delete()
                return@runCatching DownloadOutcome.Failure(
                    "Incomplete download: received $finalSize of $totalBytes bytes — file deleted"
                )
            }
            if (totalBytes == 0L && finalSize < MIN_VALID_MODEL_SIZE_BYTES) {
                outputFile.delete()
                return@runCatching DownloadOutcome.Failure(
                    "Download too small (${finalSize / 1_048_576} MB < 750 MB minimum) — file deleted, retry required"
                )
            }

            DownloadOutcome.Success(outputFile)
        }
    }.getOrElse { cause ->
        Log.e(TAG, "streamDownload error for $url: ${cause.message}")
        DownloadOutcome.Failure(cause.message ?: "Unknown error")
    }

    // ── Internal result type ──────────────────────────────────────────────────

    private sealed class DownloadOutcome {
        data class Success(val file: File) : DownloadOutcome()
        data class Failure(val reason: String) : DownloadOutcome()
    }

    companion object {
        private const val TAG = "ModelDownloadWorker"
        private const val BUFFER_SIZE = 8 * 1024  // 8 KB
        // Gemma 3 1B INT4 is ~900 MB; anything ≥ 500 MB is almost certainly the complete model.
        // Gemma 3 1B INT4 is ~900 MB. Anything below 750 MB is definitely incomplete.
        private const val MIN_VALID_MODEL_SIZE_BYTES = 750L * 1024 * 1024

        /** Filename for models downloaded from the backend. */
        private const val BACKEND_MODEL_FILENAME = "gemma_model.task"

        // ── WorkManager input keys ────────────────────────────────────────────
        /** String array of provider keys — see [ModelProvider.toKey]. */
        const val KEY_PROVIDERS = "providers"
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_HF_URL = "hf_url"
        const val KEY_HF_TOKEN = "hf_token"

        // ── WorkManager progress / output keys ────────────────────────────────
        /** Int 0–100. Reports 100 only on [androidx.work.WorkInfo.State.SUCCEEDED]. */
        const val KEY_PROGRESS = "progress"
        /** Absolute path to the downloaded model file. Present only on success. */
        const val KEY_FILE_PATH = "file_path"
        /** Human-readable error string. Present only on failure. */
        const val KEY_ERROR = "error"
    }
}
