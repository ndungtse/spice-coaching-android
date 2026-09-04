package com.medtroniclabs.microcoaching.ai.embedding

import android.content.Context
import android.content.SharedPreferences
import android.os.StatFs
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.domain.system.DeviceCapability
import com.medtroniclabs.microcoaching.util.PrefsNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the on-device query encoder's files: decides whether this device should have
 * them, schedules the download, and reports where they are.
 *
 * Parallel to [com.medtroniclabs.microcoaching.ai.voice.stt.SttModelManager] rather
 * than to the LLM `ModelManager`, because the encoder is a two-file model that nothing
 * offers to the user and no UI waits on. Every decision worth arguing about is in
 * [EncoderModelRule] and [EncoderModel], which are pure and tested; what is left here
 * is WorkManager plumbing.
 *
 * The gates, in the order [EncoderModelRule] applies them: `enableDenseRetrieval`, the
 * 3 GB RAM tier, then what is already on disk. `wifiOnlyModelDownload` is honoured on
 * top — 171 MB over a metered connection is the CHW's money.
 */
internal class EncoderModelManager(private val config: MicroCoachingConfig) {

    private val _state = MutableStateFlow<EncoderModelState>(EncoderModelState.Idle)
    val state: StateFlow<EncoderModelState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val prefs: SharedPreferences =
        config.context.getSharedPreferences(PrefsNames.EMBED, Context.MODE_PRIVATE)

    init {
        // Probing external storage and two file lengths is disk work; the SDK builder
        // runs on the main thread.
        scope.launch(Dispatchers.IO) { reconcile() }
        observeUniqueWork()
    }

    fun close() {
        scope.cancel()
    }

    /** Where the encoder lives. May not exist yet. */
    fun modelDir(): File = File(config.context.getExternalFilesDir(null), EncoderModel.DIR_NAME)

    /** Both files present at full length. */
    fun isPresent(): Boolean = EncoderModel.filesPresent(modelDir())

    /**
     * Evaluate the gates and start the download if this device should have the encoder.
     * Safe to call repeatedly — the work is unique and a present model short-circuits.
     */
    fun scheduleDownloadIfNeeded() {
        scope.launch(Dispatchers.IO) {
            val verdict = evaluate()
            when (verdict) {
                EncoderVerdict.READY -> _state.value = EncoderModelState.Ready(modelDir())
                EncoderVerdict.DOWNLOAD -> {
                    val shortfall = insufficientSpaceReason()
                    if (shortfall != null) {
                        Log.w(TAG, shortfall)
                        _state.value = EncoderModelState.Failed(shortfall)
                    } else {
                        scheduleDownload()
                    }
                }
                else -> {
                    Log.i(TAG, "Query encoder not downloaded: $verdict")
                    _state.value = EncoderModelState.Skipped(verdict)
                }
            }
        }
    }

    private fun evaluate(): EncoderVerdict = EncoderModelRule.evaluate(
        enableDenseRetrieval = config.enableDenseRetrieval,
        isLowEndDevice = config.forceLowEndMode ?: DeviceCapability.isLowEndDevice(config.context),
        filesPresent = isPresent(),
        hasAccessToken = config.huggingFaceToken.isNotBlank(),
    )

    private fun reconcile() {
        val flagged = prefs.getBoolean(KEY_READY, false)
        val present = isPresent()
        when {
            present -> {
                if (!flagged) prefs.edit().putBoolean(KEY_READY, true).apply()
                _state.value = EncoderModelState.Ready(modelDir())
            }
            flagged -> {
                Log.w(TAG, "Reconcile: ready flag set but files missing — clearing")
                prefs.edit().remove(KEY_READY).apply()
            }
        }
    }

    private fun scheduleDownload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (config.wifiOnlyModelDownload) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()

        val request = OneTimeWorkRequestBuilder<EncoderModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    EncoderModelDownloadWorker.KEY_OUTPUT_DIR to modelDir().absolutePath,
                    EncoderModelDownloadWorker.KEY_HF_TOKEN to config.huggingFaceToken,
                ),
            )
            .addTag(DOWNLOAD_TAG)
            .build()

        WorkManager.getInstance(config.context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)

        _state.value = EncoderModelState.Downloading(progressPercent = -1)
        Log.i(TAG, "Query encoder download scheduled (${EncoderModel.totalBytes / 1_048_576} MB)")
    }

    private fun observeUniqueWork() {
        scope.launch {
            WorkManager.getInstance(config.context)
                .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
                .collect { infos ->
                    val info = infos.firstOrNull() ?: return@collect
                    when (info.state) {
                        WorkInfo.State.RUNNING -> _state.value = EncoderModelState.Downloading(
                            progressPercent = info.progress.getInt(EncoderModelDownloadWorker.KEY_PROGRESS, -1),
                            bytesDownloaded = info.progress.getLong(EncoderModelDownloadWorker.KEY_BYTES, 0L),
                            totalBytes = info.progress.getLong(EncoderModelDownloadWorker.KEY_TOTAL, 0L),
                        )
                        WorkInfo.State.SUCCEEDED -> {
                            prefs.edit().putBoolean(KEY_READY, true).apply()
                            _state.value = EncoderModelState.Ready(modelDir())
                        }
                        WorkInfo.State.FAILED -> _state.value = EncoderModelState.Failed(
                            info.outputData.getString(EncoderModelDownloadWorker.KEY_ERROR) ?: "download failed",
                        )
                        else -> Unit
                    }
                }
        }
    }

    /**
     * Null when there is room. Mirrors
     * [com.medtroniclabs.microcoaching.ai.model.ModelManager]'s check, counting only
     * the bytes still to fetch so a resumed download is not refused for space it
     * already occupies.
     */
    private fun insufficientSpaceReason(): String? {
        val dir = config.context.getExternalFilesDir(null) ?: return null
        val onDisk = modelDir().listFiles()?.sumOf { it.length() } ?: 0L
        val needed = (EncoderModel.totalBytes - onDisk).coerceAtLeast(0L) + SPACE_HEADROOM_BYTES
        val available = runCatching { StatFs(dir.path).availableBytes }.getOrElse { cause ->
            Log.w(TAG, "Could not read free space (${cause.message}) — proceeding")
            return null
        }
        if (available >= needed) return null
        return "Not enough free space for the query encoder: " +
            "${needed / 1_048_576} MB required, ${available / 1_048_576} MB available"
    }

    companion object {
        private const val TAG = "EncoderModelManager"

        const val DOWNLOAD_TAG = "microcoaching_encoder_download"
        const val UNIQUE_WORK_NAME = "microcoaching_encoder_download_embeddinggemma"

        private const val KEY_READY = "encoder_ready"

        /** Same headroom the LLM download reserves. */
        private const val SPACE_HEADROOM_BYTES = 64L * 1024L * 1024L
    }
}
