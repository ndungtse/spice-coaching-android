package com.medtroniclabs.microcoaching.ai.model

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.ai.download.DownloadResult
import com.medtroniclabs.microcoaching.ai.download.ResumableHttpDownloader
import com.medtroniclabs.microcoaching.network.NetworkModule
import androidx.work.ForegroundInfo
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
 * **Provider fallback order** (default): Backend → HuggingFace → Kaggle
 *
 * Features:
 *   - Sequential fallback: moves to next provider if the current one fails
 *   - Resumable: uses HTTP `Range` requests if a partial file already exists
 *   - Progress: reports 0–99 via [KEY_PROGRESS] (plus [KEY_BYTES_DOWNLOADED] / [KEY_TOTAL_BYTES])
 *     while streaming; 100 on success
 *   - Foreground service: shows a persistent system notification via [ModelDownloadNotifier]
 *     so the OS keeps the download alive even when the host app is minimized or killed
 *   - Survives process death — WorkManager re-enqueues on restart
 *
 * On success, [KEY_FILE_PATH] in output data holds the absolute path to the model file.
 * [ModelManager] observes this worker's [WorkInfo][androidx.work.WorkInfo] to update [ModelState].
 *
 * Backend endpoint: `{backendUrl}/api/v1/models/gemma/download`
 * HuggingFace endpoint: the selected [ModelCatalog] variant's `downloadUrl`
 * (overridable via [MicroCoachingConfig.huggingFaceModelUrl]).
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(progress = 0, bytesDownloaded = 0L, totalBytes = 0L)

    override suspend fun doWork(): Result {
        // Promote to foreground service before any I/O so the OS keeps us alive
        // when the host app is backgrounded or killed. If POST_NOTIFICATIONS was
        // denied the call can fail — we log and continue: the download still works,
        // just without the elevated priority.
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Log.w(TAG, "setForeground at start failed: ${it.message}") }

        val providers = inputData.getStringArray(KEY_PROVIDERS)
            ?.mapNotNull { ModelProvider.fromKey(it) }
            ?.ifEmpty { ModelProvider.DEFAULT_ORDER }
            ?: ModelProvider.DEFAULT_ORDER

        // Resolve the selected model variant — single source of truth for the
        // download URL, on-disk filename, expected size (size floor), and whether
        // an access token is required. Falls back to the catalog default.
        val variant = inputData.getString(KEY_MODEL_ID)
            ?.let { ModelCatalog.byId(it) }
            ?: ModelCatalog.default()

        Log.i(TAG, "doWork start — providers=${providers.map { it::class.simpleName }}, model=${variant.id} (${variant.fileName})")
        logNetworkSnapshot("doWork")

        val outputDir = applicationContext.getExternalFilesDir(null)
            ?: run {
                Log.e(TAG, "External storage unavailable — failing")
                return Result.failure(workDataOf(KEY_ERROR to "External storage unavailable"))
            }

        val errors = mutableListOf<String>()

        for (provider in providers) {
            val providerName = provider::class.simpleName ?: "Unknown"
            Log.i(TAG, "Attempting download from provider: $providerName")

            val outcome = when (provider) {
                ModelProvider.Backend -> tryBackend(outputDir, variant)
                ModelProvider.HuggingFace -> tryHuggingFace(outputDir, variant)
                ModelProvider.Kaggle -> {
                    Log.w(TAG, "Kaggle provider not yet implemented — skipping")
                    DownloadOutcome.Failure("Kaggle provider not yet supported")
                }
            }

            when (outcome) {
                is DownloadOutcome.Success -> {
                    Log.i(TAG, "Download complete via $providerName → ${outcome.file.name} (${outcome.file.length() / 1_048_576} MB)")
                    // Persist the ready flag before returning so ModelManager.reconcileReadyState()
                    // on the next process start can emit Ready immediately even if the WorkInfo
                    // SUCCEEDED event was never observed by a live ModelManager (e.g. worker
                    // finished while the app was killed).
                    applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_MODEL_READY, true)
                        .putString(KEY_MODEL_PATH, outcome.file.absolutePath)
                        .apply()
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

    private suspend fun tryBackend(outputDir: File, variant: ModelVariant): DownloadOutcome {
        val backendUrl = inputData.getString(KEY_BACKEND_URL)
        if (backendUrl.isNullOrBlank()) {
            return DownloadOutcome.Failure("backendUrl not configured — set via Builder.backendUrl()")
        }

        val authToken = inputData.getString(KEY_AUTH_TOKEN) ?: ""
        val downloadUrl = "${backendUrl.trimEnd('/')}/api/v1/models/gemma/download"
        // Write to the variant's filename so on-disk resolution (ModelManager /
        // InferenceRouter, which match by exact fileName) finds it regardless of
        // which provider served the bytes.
        val outputFile = File(outputDir, variant.fileName)

        NetworkModule.logAuthFingerprint(authToken, "model-download")
        val headers = buildMap<String, String> {
            // Forwarded verbatim — the backend gateway expects the login token
            // as-is, with no `Bearer` prefix.
            if (authToken.isNotBlank()) put("Authorization", authToken)
            put("Client", NetworkModule.CLIENT_HEADER_VALUE)
        }

        return streamDownload(
            url = downloadUrl,
            headers = headers,
            outputFile = outputFile,
            minValidBytes = ModelSizeProbe.minValidSizeBytes(applicationContext, variant),
        )
    }

    // ── HuggingFace provider ──────────────────────────────────────────────────

    private suspend fun tryHuggingFace(outputDir: File, variant: ModelVariant): DownloadOutcome {
        // The variant's catalog URL is authoritative; KEY_HF_URL is an optional
        // host override for a file not in the allowlist.
        val hfUrl = inputData.getString(KEY_HF_URL)
            ?.takeIf { it.isNotBlank() }
            ?: variant.downloadUrl
        val hfToken = inputData.getString(KEY_HF_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: ModelProvider.DEFAULT_HF_TOKEN

        // On-disk filename always comes from the selected variant so resolution
        // (which matches by exact fileName) is deterministic.
        val outputFile = File(outputDir, variant.fileName)

        if (hfToken.isBlank() && variant.requiresAccessToken) {
            Log.w(TAG, "[HF] token=<BLANK> but model '${variant.id}' is gated — download will fail")
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

        return streamDownload(
            url = hfUrl,
            headers = headers,
            outputFile = outputFile,
            minValidBytes = ModelSizeProbe.minValidSizeBytes(applicationContext, variant),
        )
    }

    // ── Shared streaming download ─────────────────────────────────────────────

    /**
     * Streams [url] to [outputFile] (resume + throttled progress + size-floor check) via the
     * shared [ResumableHttpDownloader], reporting progress through [emitProgress]. Maps the
     * shared result back to this worker's provider-fallback [DownloadOutcome].
     */
    private suspend fun streamDownload(
        url: String,
        headers: Map<String, String>,
        outputFile: File,
        minValidBytes: Long,
    ): DownloadOutcome = when (
        val result = ResumableHttpDownloader.download(
            url = url,
            outputFile = outputFile,
            minValidBytes = minValidBytes,
            headers = headers,
            logTag = TAG,
        ) { percent, bytesDownloaded, totalBytes -> emitProgress(percent, bytesDownloaded, totalBytes) }
    ) {
        is DownloadResult.Success -> DownloadOutcome.Success(outputFile)
        is DownloadResult.Failure -> DownloadOutcome.Failure(result.reason)
    }

    // ── Foreground service + progress emission ────────────────────────────────

    /**
     * Builds the [ForegroundInfo] used both at worker start and on every
     * throttled progress update. On API ≥ Q the service is typed as
     * `DATA_SYNC`; on older releases the type argument is omitted (the OS
     * doesn't require it pre-Q).
     */
    private fun buildForegroundInfo(
        progress: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
    ): ForegroundInfo {
        val notification = ModelDownloadNotifier.buildNotification(
            context = applicationContext,
            progress = progress,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                ModelDownloadNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(ModelDownloadNotifier.NOTIFICATION_ID, notification)
        }
    }

    /**
     * Emits a progress update through both channels:
     *   - [setProgress] so [ModelManager]'s WorkInfo observer can update the in-app UI.
     *   - [setForeground] so the system notification reflects the latest MB / percent.
     *
     * Wrapped in [runCatching] because [setForeground] can throw on Android 13+
     * if POST_NOTIFICATIONS was denied; the underlying download must continue
     * regardless so we never propagate that failure.
     */
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
            )
        )
        runCatching {
            setForeground(buildForegroundInfo(percent, bytesDownloaded, totalBytes))
        }.onFailure {
            Log.w(TAG, "setForeground progress update failed: ${it.message}")
        }
    }

    // ── Internal result type ──────────────────────────────────────────────────

    private sealed class DownloadOutcome {
        data class Success(val file: File) : DownloadOutcome()
        data class Failure(val reason: String) : DownloadOutcome()
    }

    /**
     * The worker's own view of the network (vs `ModelManager`'s). Important for
     * the "WorkManager satisfied its constraint but the worker still can't reach
     * the network" debugging story — the worker's view is what actually matters
     * for HTTP success.
     *
     * @see NetworkDiagnostics.logSnapshot
     */
    private fun logNetworkSnapshot(stage: String) =
        com.medtroniclabs.microcoaching.util.NetworkDiagnostics.logSnapshot(applicationContext, TAG, stage)

    companion object {
        private const val TAG = "ModelDownloadWorker"
        // The completeness floor is per-variant (ModelCatalog.minValidSizeBytes),
        // passed into streamDownload.

        // ── WorkManager input keys ────────────────────────────────────────────
        /** String array of provider keys — see [ModelProvider.toKey]. */
        const val KEY_PROVIDERS = "providers"
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_HF_URL = "hf_url"
        const val KEY_HF_TOKEN = "hf_token"
        /** [ModelCatalog] variant id selected by the host (see [MicroCoachingConfig.selectedModelId]). */
        const val KEY_MODEL_ID = "model_id"

        // ── WorkManager progress / output keys ────────────────────────────────
        /** Int 0–100. Reports 100 only on [androidx.work.WorkInfo.State.SUCCEEDED]. */
        const val KEY_PROGRESS = "progress"
        /** Long. Bytes received so far. Present during RUNNING. */
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        /** Long. Total bytes expected, or 0 if unknown (chunked transfer). Present during RUNNING. */
        const val KEY_TOTAL_BYTES = "total_bytes"
        /** Absolute path to the downloaded model file. Present only on success. */
        const val KEY_FILE_PATH = "file_path"
        /** Human-readable error string. Present only on failure. */
        const val KEY_ERROR = "error"

        // Mirrors ModelManager constants — kept here so the worker can persist the
        // ready flag without needing a back-reference to the manager instance.
        private const val PREFS_NAME = com.medtroniclabs.microcoaching.util.PrefsNames.MODEL
        private const val KEY_MODEL_READY = "model_ready"
        private const val KEY_MODEL_PATH = "model_path"
    }
}
