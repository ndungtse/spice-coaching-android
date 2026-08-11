package com.medtroniclabs.microcoaching.ai.voice.stt

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manages the on-device Bengali STT model lifecycle (download / extract /
 * present / failed). Parallel to the Gemma
 * [com.medtroniclabs.microcoaching.ai.model.ModelManager] but Bengali-only and
 * much simpler: there's a single source (the sherpa-onnx Bengali Zipformer
 * release archive), so no provider fallback chain.
 *
 * **Storage layout** under `getExternalFilesDir(null)/stt/bn/`:
 *   - `encoder.onnx`
 *   - `decoder.onnx`
 *   - `joiner.onnx`
 *   - `tokens.txt`
 *   - `bpe.model` (extra, ignored by sherpa runtime)
 *   - `_archive.tar.bz2` (transient — deleted on successful extraction)
 *
 * Ready-state persistence mirrors the Gemma flow — a [PREFS_NAME] boolean is
 * flipped after extraction so subsequent process starts can short-circuit the
 * worker observation.
 */
class SttModelManager(private val config: MicroCoachingConfig) {

    private val _state = MutableStateFlow<SttModelState>(SttModelState.Idle)
    val state: StateFlow<SttModelState> = _state.asStateFlow()

    // Long-lived; lives as long as the manager (app lifetime).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val prefs: SharedPreferences =
        config.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        reconcileReadyState()
        observeUniqueWork()
    }

    /** Absolute path to the Bengali model directory. May not exist yet. */
    fun bengaliModelDir(): File = File(
        config.context.getExternalFilesDir(null),
        "stt/bn",
    )

    /** Whether all required Bengali model files are on disk. */
    fun isBengaliModelPresent(): Boolean {
        val dir = bengaliModelDir()
        if (!dir.isDirectory) return false
        return REQUIRED_FILES.all { File(dir, it).exists() }
    }

    /**
     * Kick off the Bengali model download. Safe to call multiple times —
     * WorkManager deduplicates by unique work name. No-op if the model is
     * already present.
     */
    fun triggerBengaliDownload() {
        if (isBengaliModelPresent()) {
            Log.i(TAG, "Bengali model already present — skipping download")
            _state.value = SttModelState.Ready(bengaliModelDir())
            return
        }
        scheduleDownload()
    }

    /** Cancel an in-flight download; partial files are preserved for resume. */
    fun pauseBengaliDownload() {
        if (_state.value !is SttModelState.Downloading) return
        WorkManager.getInstance(config.context).cancelUniqueWork(UNIQUE_WORK_NAME)
        Log.i(TAG, "Bengali download paused — partial archive retained for resume")
    }

    /** Cancel the worker AND wipe the partial archive + extracted dir. */
    fun cancelBengaliDownload() {
        WorkManager.getInstance(config.context).cancelUniqueWork(UNIQUE_WORK_NAME)
        bengaliModelDir().deleteRecursively()
        prefs.edit().remove(KEY_BN_READY).apply()
        _state.value = SttModelState.Idle
        Log.i(TAG, "Bengali download cancelled — disk wiped")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun reconcileReadyState() {
        val prefsReady = prefs.getBoolean(KEY_BN_READY, false)
        val present = isBengaliModelPresent()
        when {
            prefsReady && present -> {
                _state.value = SttModelState.Ready(bengaliModelDir())
                Log.i(TAG, "Reconcile: Bengali model ready at ${bengaliModelDir().absolutePath}")
            }
            prefsReady && !present -> {
                Log.w(TAG, "Reconcile: prefs say ready but files missing — clearing flag")
                prefs.edit().remove(KEY_BN_READY).apply()
            }
            !prefsReady && present -> {
                Log.i(TAG, "Reconcile: files present without flag — adopting")
                prefs.edit().putBoolean(KEY_BN_READY, true).apply()
                _state.value = SttModelState.Ready(bengaliModelDir())
            }
            else -> { /* clean slate */ }
        }
    }

    private fun scheduleDownload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SttModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    SttModelDownloadWorker.KEY_URL to BENGALI_MODEL_URL,
                    SttModelDownloadWorker.KEY_OUTPUT_DIR to bengaliModelDir().absolutePath,
                ),
            )
            .addTag(DOWNLOAD_TAG)
            .build()

        WorkManager.getInstance(config.context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)

        _state.value = SttModelState.Downloading(progressPercent = 0)
        Log.i(TAG, "Bengali STT download scheduled")
    }

    private fun observeUniqueWork() {
        scope.launch {
            WorkManager.getInstance(config.context)
                .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
                .collect { infoList ->
                    val info = infoList.firstOrNull() ?: return@collect
                    // Never demote a confirmed Ready back to anything else.
                    val current = _state.value
                    if (current is SttModelState.Ready && info.state != WorkInfo.State.RUNNING) {
                        return@collect
                    }

                    when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                            if (current !is SttModelState.Downloading &&
                                current !is SttModelState.Extracting
                            ) {
                                _state.value = SttModelState.Downloading(progressPercent = -1)
                            }
                        }
                        WorkInfo.State.RUNNING -> {
                            val extracting = info.progress.getBoolean(
                                SttModelDownloadWorker.KEY_EXTRACTING,
                                false,
                            )
                            if (extracting) {
                                _state.value = SttModelState.Extracting
                            } else {
                                val pct = info.progress.getInt(
                                    SttModelDownloadWorker.KEY_PROGRESS, 0,
                                )
                                val bytes = info.progress.getLong(
                                    SttModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L,
                                )
                                val total = info.progress.getLong(
                                    SttModelDownloadWorker.KEY_TOTAL_BYTES, 0L,
                                )
                                _state.value = SttModelState.Downloading(
                                    progressPercent = pct,
                                    bytesDownloaded = bytes,
                                    totalBytes = total,
                                )
                            }
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            if (isBengaliModelPresent()) {
                                prefs.edit().putBoolean(KEY_BN_READY, true).apply()
                                _state.value = SttModelState.Ready(bengaliModelDir())
                                Log.i(TAG, "Bengali STT model ready")
                            } else {
                                _state.value = SttModelState.Failed(
                                    "Worker reported success but model files missing",
                                )
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val err = info.outputData.getString(
                                SttModelDownloadWorker.KEY_ERROR,
                            ) ?: "download failed"
                            Log.e(TAG, "Bengali STT download failed: $err")
                            _state.value = SttModelState.Failed(err)
                        }
                        WorkInfo.State.CANCELLED -> {
                            // Treat cancellation like a soft idle — the user
                            // either paused (partial preserved) or cancelled
                            // (cancelBengaliDownload wipes things explicitly).
                            if (current !is SttModelState.Ready &&
                                current !is SttModelState.Failed
                            ) {
                                _state.value = SttModelState.Idle
                            }
                        }
                    }
                }
        }
    }

    companion object {
        private const val TAG = "SttModelManager"

        /** Bengali sherpa-onnx streaming Zipformer (vosk-2026-02-09 release). */
        const val BENGALI_MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09.tar.bz2"

        const val DOWNLOAD_TAG = "microcoaching_stt_download"
        const val UNIQUE_WORK_NAME = "microcoaching_stt_download_bn"

        private const val PREFS_NAME = "microcoaching_stt_prefs"
        private const val KEY_BN_READY = "stt_bn_ready"

        private val REQUIRED_FILES = listOf(
            "encoder.onnx",
            "decoder.onnx",
            "joiner.onnx",
            "tokens.txt",
        )
    }
}
