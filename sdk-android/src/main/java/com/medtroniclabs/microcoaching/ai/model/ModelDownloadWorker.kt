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
 * WorkManager worker that downloads the on-device model, trying each configured
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
 * Backend endpoint: `{backendUrl}/api/v1/models/gemma/download` — serves a `.task`, which
 * no bundled engine loads, so this provider is skipped (see [tryBackend]).
 * HuggingFace endpoint: the selected [ModelCatalog] variant's `downloadUrl`
 * (overridable via [MicroCoachingConfig.huggingFaceModelUrl]).
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /** Last `Content-Length` handed to [recordObservedSize]; dedupes the preference write. */
    private var lastRecordedTotalBytes: Long = 0L

    /** Sum of every artifact in the plan — the denominator of the single progress bar. */
    private var planTotalBytes: Long = 0L

    /** Bytes belonging to artifacts already finished or skipped this run. */
    private var completedBytes: Long = 0L

    /** Artifact currently transferring; chooses the label under the bar. */
    private var currentPhase: DownloadPhase = DownloadPhase.LANGUAGE_MODEL

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

        val includeEncoder = inputData.getBoolean(KEY_INCLUDE_ENCODER, false)
        val plan = DownloadPlan.artifacts(variant, includeEncoder)

        Log.i(
            TAG,
            "doWork start — providers=${providers.map { it::class.simpleName }}, model=${variant.id} " +
                "(${variant.fileName}), artifacts=${plan.size}, encoder=$includeEncoder",
        )
        logNetworkSnapshot("doWork")

        val outputDir = applicationContext.getExternalFilesDir(null)
            ?: run {
                Log.e(TAG, "External storage unavailable — failing")
                return Result.failure(workDataOf(KEY_ERROR to "External storage unavailable"))
            }

        // One progress track across every artifact, so the percentage does not restart
        // when the language model finishes and the encoder begins. Artifacts already at
        // their expected length still count towards the total — the bar has to read 40%
        // when 40% of what the user agreed to is on disk, not 0% of what is left.
        planTotalBytes = DownloadPlan.totalBytes(plan)
        completedBytes = 0L

        var languageModelFile: File? = null
        var encoderError: String? = null

        for (artifact in plan) {
            currentPhase = artifact.phase
            val target = DownloadPlan.fileFor(outputDir, artifact)

            if (target.length() == artifact.expectedBytes) {
                Log.i(TAG, "Already present, skipping: ${artifact.fileName}")
                completedBytes += artifact.expectedBytes
                if (artifact.phase == DownloadPhase.LANGUAGE_MODEL) languageModelFile = target
                continue
            }

            when (val outcome = fetch(artifact, target, variant, providers)) {
                is DownloadOutcome.Success -> {
                    Log.i(
                        TAG,
                        "Downloaded ${artifact.fileName} " +
                            "(${outcome.file.length()} bytes, expected ${artifact.expectedBytes})",
                    )
                    completedBytes += artifact.expectedBytes
                    if (artifact.phase == DownloadPhase.LANGUAGE_MODEL) {
                        languageModelFile = outcome.file
                        // Persist before the encoder phase begins so a worker that dies
                        // partway still leaves a usable language model behind for
                        // ModelManager.reconcileReadyState() on the next process start.
                        persistLanguageModelReady(outcome.file)
                    }
                }
                is DownloadOutcome.Failure -> {
                    if (artifact.phase == DownloadPhase.LANGUAGE_MODEL) {
                        Log.e(TAG, "Language model failed: ${outcome.reason}")
                        return Result.failure(workDataOf(KEY_ERROR to outcome.reason))
                    }
                    // The encoder is an improvement to retrieval, not the capability the
                    // user asked for. Losing it must not strand a language model that has
                    // already arrived, so the work succeeds and dense retrieval stays off.
                    Log.w(TAG, "Encoder artifact ${artifact.fileName} failed: ${outcome.reason}")
                    encoderError = "${artifact.fileName}: ${outcome.reason}"
                    break
                }
            }
        }

        val modelFile = languageModelFile
            ?: return Result.failure(workDataOf(KEY_ERROR to "Language model missing after download"))

        return Result.success(
            workDataOf(
                KEY_PROGRESS to 100,
                KEY_FILE_PATH to modelFile.absolutePath,
                KEY_ENCODER_ERROR to encoderError,
            ),
        )
    }

    /**
     * Fetches one artifact. The language model walks the provider fallback chain; the
     * encoder goes straight to Hugging Face, which is the only place it exists.
     */
    private suspend fun fetch(
        artifact: DownloadArtifact,
        target: File,
        variant: ModelVariant,
        providers: List<ModelProvider>,
    ): DownloadOutcome {
        target.parentFile?.mkdirs()
        if (!artifact.useProviderChain) return tryEncoderArtifact(artifact, target)

        val outputDir = target.parentFile ?: return DownloadOutcome.Failure("No parent directory")
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
                is DownloadOutcome.Success -> return outcome
                is DownloadOutcome.Failure -> {
                    val msg = "[$providerName] ${outcome.reason}"
                    Log.w(TAG, "$msg — trying next provider")
                    errors += msg
                }
            }
        }
        return DownloadOutcome.Failure(errors.joinToString(" | "))
    }

    /**
     * Hugging Face fetch for an encoder artifact. Unlike the language model this has no
     * catalog entry, no host URL override and no structural validator — a `.tflite` has
     * nothing inside it that proves the transfer finished, so the exact length recorded in
     * [DownloadPlan] is the completeness check, applied by the caller.
     */
    private suspend fun tryEncoderArtifact(
        artifact: DownloadArtifact,
        target: File,
    ): DownloadOutcome {
        val token = inputData.getString(KEY_HF_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: ModelProvider.DEFAULT_HF_TOKEN
        if (token.isBlank() && artifact.requiresAccessToken) {
            return DownloadOutcome.Failure("no Hugging Face token for gated ${artifact.fileName}")
        }
        val headers = if (token.isNotBlank()) mapOf("Authorization" to "Bearer $token") else emptyMap()

        val result = ResumableHttpDownloader.download(
            url = artifact.downloadUrl,
            outputFile = target,
            minValidBytes = (artifact.expectedBytes * ENCODER_SIZE_FLOOR_FRACTION).toLong(),
            headers = headers,
            logTag = TAG,
        ) { _, bytesDownloaded, _ -> emitPlanProgress(bytesDownloaded) }

        return when (result) {
            is DownloadResult.Success ->
                if (target.length() == artifact.expectedBytes) {
                    DownloadOutcome.Success(target)
                } else {
                    // Wrong length means the artifact upstream is not the one the plan
                    // describes. Drop it so the next attempt starts clean instead of
                    // resuming onto a mismatch.
                    target.delete()
                    DownloadOutcome.Failure(
                        "${artifact.fileName} arrived as ${target.length()} bytes, expected ${artifact.expectedBytes}",
                    )
                }
            is DownloadResult.Failure -> DownloadOutcome.Failure(result.reason)
        }
    }

    /**
     * Records the language model as usable. Written before the encoder phase so a worker
     * killed mid-encoder still leaves "simple words" recoverable on the next start.
     */
    private fun persistLanguageModelReady(file: File) {
        applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MODEL_READY, true)
            .putString(KEY_MODEL_PATH, file.absolutePath)
            .apply()
    }

    // ── Backend provider ──────────────────────────────────────────────────────

    private suspend fun tryBackend(outputDir: File, variant: ModelVariant): DownloadOutcome {
        val backendUrl = inputData.getString(KEY_BACKEND_URL)
        if (backendUrl.isNullOrBlank()) {
            return DownloadOutcome.Failure("backendUrl not configured — set via Builder.backendUrl()")
        }

        // This endpoint serves a `.task`, which no bundled engine can load, so its bytes
        // are certain to fail validation — after transferring hundreds of megabytes over
        // what may be a metered field connection. Skipping to the next provider is the
        // difference between one wasted download per device and none. Using this provider
        // again means serving `.litertlm` from it.
        if (!variant.fileName.endsWith(".task")) {
            return DownloadOutcome.Failure(
                "backend endpoint serves a Gemma .task, which no bundled engine loads — " +
                    "'${variant.fileName}' must come from another provider",
            )
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
            validate = validatorFor(variant),
            variant = variant,
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
        } else if (BuildConfig.DEBUG && hfToken.isNotBlank()) {
            Log.d(TAG, "[HF] token=${hfToken.take(1)}…${hfToken.takeLast(1)} (len=${hfToken.length})")
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[HF] url=$hfUrl")
            Log.d(TAG, "[HF] outputFile=${outputFile.absolutePath}")
        }

        // Send the token only where it can help. On an ungated repo — which the default
        // Qwen3 variant is — a stale or revoked token turns a download that would have
        // worked anonymously into a 401, and there is no reason to hand credentials to a
        // public URL either. A host `huggingFaceModelUrl` override is the exception: it may
        // well point at a gated file the catalog knows nothing about, so it keeps the token.
        val usesHostUrl = hfUrl != variant.downloadUrl
        val sendToken = hfToken.isNotBlank() && (variant.requiresAccessToken || usesHostUrl)
        val headers = buildMap<String, String> {
            if (sendToken) put("Authorization", "Bearer $hfToken")
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "[HF] Authorization header present=${headers.containsKey("Authorization")} " +
                    "(gated=${variant.requiresAccessToken}, hostUrlOverride=$usesHostUrl)",
            )
        }

        return streamDownload(
            url = hfUrl,
            headers = headers,
            outputFile = outputFile,
            minValidBytes = ModelSizeProbe.minValidSizeBytes(applicationContext, variant),
            validate = validatorFor(variant),
            variant = variant,
        )
    }

    // ── Completeness validation ───────────────────────────────────────────────

    /**
     * Structural check for [variant]'s format, or null when there is none. The expected
     * length has to be passed in, because nothing inside the container proves the transfer
     * finished.
     *
     * That length is whatever the host has said: the `Content-Length` observed for this
     * variant — [ModelSizeProbe.recordObservedSize] caches it as this very download
     * progresses — falling back to the catalog's figure on a first run.
     */
    private fun validatorFor(variant: ModelVariant): ((File) -> String?)? =
        if (ModelCatalog.isLiteRtLmBundle(variant)) {
            { file ->
                val expected = ModelSizeProbe.cachedSize(applicationContext, variant)
                    ?: variant.sizeInBytes
                ModelFileIntegrity.validateLiteRtLmBundle(file, expected)
            }
        } else {
            null
        }

    /**
     * Caches the served `Content-Length` so the displayed size comes from the host instead
     * of a constant that goes stale when the model is republished. Guarded to one write per
     * distinct value, since progress fires repeatedly.
     */
    private fun recordObservedSize(variant: ModelVariant, totalBytes: Long) {
        if (totalBytes <= 0L || totalBytes == lastRecordedTotalBytes) return
        lastRecordedTotalBytes = totalBytes
        ModelSizeProbe.recordObservedSize(applicationContext, variant, totalBytes)
    }

    // ── Shared streaming download ─────────────────────────────────────────────

    /**
     * Streams [url] to [outputFile] (resume + throttled progress + size floor + [validate])
     * via the shared [ResumableHttpDownloader], reporting progress through [emitProgress].
     * Maps the result to this worker's provider-fallback [DownloadOutcome], so a rejected
     * file falls through to the next provider instead of being stored.
     */
    private suspend fun streamDownload(
        url: String,
        headers: Map<String, String>,
        outputFile: File,
        minValidBytes: Long,
        validate: ((File) -> String?)?,
        variant: ModelVariant,
    ): DownloadOutcome = when (
        val result = ResumableHttpDownloader.download(
            url = url,
            outputFile = outputFile,
            minValidBytes = minValidBytes,
            headers = headers,
            logTag = TAG,
            validate = validate,
        ) { _, bytesDownloaded, totalBytes ->
            recordObservedSize(variant, totalBytes)
            emitPlanProgress(bytesDownloaded)
        }
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
                KEY_PHASE to currentPhase.name,
            )
        )
        runCatching {
            setForeground(buildForegroundInfo(percent, bytesDownloaded, totalBytes))
        }.onFailure {
            Log.w(TAG, "setForeground progress update failed: ${it.message}")
        }
    }

    /**
     * Reports [bytesInCurrentArtifact] as a position within the whole plan.
     *
     * The user agreed to one figure and watches one bar, so every artifact contributes to
     * the same numerator and denominator. Capped at 99 for the same reason the underlying
     * downloader caps: 100 belongs to the terminal success, not to the last byte of an
     * intermediate file.
     */
    private suspend fun emitPlanProgress(bytesInCurrentArtifact: Long) {
        val done = completedBytes + bytesInCurrentArtifact
        val percent = if (planTotalBytes > 0L) {
            ((done * 100L) / planTotalBytes).toInt().coerceIn(0, 99)
        } else {
            -1
        }
        emitProgress(percent, done, planTotalBytes)
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
        /** Boolean. Whether the dense-retrieval encoder rides along with the language model. */
        const val KEY_INCLUDE_ENCODER = "include_encoder"

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
        /** Name of the [DownloadPhase] currently transferring. Present during RUNNING. */
        const val KEY_PHASE = "phase"
        /**
         * Why the encoder half did not arrive, on an otherwise successful download. Non-null
         * means "simple words" works but dense retrieval stays off.
         */
        const val KEY_ENCODER_ERROR = "encoder_error"

        /**
         * Floor below which a finished encoder transfer is obviously not the artifact — an
         * error page or a Git LFS pointer. The exact-length check in [tryEncoderArtifact]
         * is what actually decides completeness.
         */
        private const val ENCODER_SIZE_FLOOR_FRACTION = 0.85

        // Mirrors ModelManager constants — kept here so the worker can persist the
        // ready flag without needing a back-reference to the manager instance.
        private const val PREFS_NAME = com.medtroniclabs.microcoaching.util.PrefsNames.MODEL
        private const val KEY_MODEL_READY = "model_ready"
        private const val KEY_MODEL_PATH = "model_path"
    }
}
