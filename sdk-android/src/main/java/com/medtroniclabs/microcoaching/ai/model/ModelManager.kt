package com.medtroniclabs.microcoaching.ai.model

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.medtroniclabs.microcoaching.ModelDownloadStrategy
import com.medtroniclabs.microcoaching.ai.embedding.EncoderModel
import com.medtroniclabs.microcoaching.ai.embedding.EncoderModelRule
import com.medtroniclabs.microcoaching.ai.embedding.EncoderWaitAction
import com.medtroniclabs.microcoaching.domain.system.DeviceCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Manages the on-device model lifecycle: detection, download scheduling, and state.
 *
 * Download is performed by [ModelDownloadWorker] via WorkManager — survives process death
 * and respects [MicroCoachingConfig.wifiOnlyModelDownload]. Provider fallback order is
 * driven by [MicroCoachingConfig.modelProviders].
 *
 * A download counts as complete when the file passes its container's structural check
 * ([ModelFileIntegrity]). The container has no tail structure to inspect, so the expected
 * length carries that half of the verdict — the `Content-Length` observed for the variant,
 * or the catalog's figure until one has been seen. During a transfer the authoritative
 * length check is the server's `Content-Length`, enforced in
 * [com.medtroniclabs.microcoaching.ai.download.ResumableHttpDownloader]. There is no
 * automatic hash check — [verifyIntegrity] offers opt-in SHA-256 and nothing calls it.
 *
 * Readiness is persisted to [PREFS_NAME] so it survives process death without depending on
 * WorkInfo replay: [KEY_MODEL_READY] is set once a file has passed validation. On
 * construction the manager reconciles that flag against the file system and re-validates
 * before emitting [ModelState.Ready]. Every transition to Ready goes through
 * [emitReadyOrCorrupt]; there is no other way to reach it.
 */
class ModelManager(private val config: MicroCoachingConfig) {

    private val _state = MutableStateFlow<ModelState>(ModelState.Idle)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    // Long-lived scope for observing WorkManager state. Lives as long as ModelManager (app lifetime).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val prefs: SharedPreferences =
        config.context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    /**
     * Distinguishes a user-initiated pause from a genuine WorkManager cancellation
     * (e.g. constraint loss). Set by [pauseDownload]; consumed and cleared by the
     * WorkInfo observer's `CANCELLED` branch so a paused download surfaces as
     * [ModelState.Paused] (resumable) instead of [ModelState.DownloadFailed].
     */
    @Volatile
    private var userPauseRequested: Boolean = false

    /** Last progress percent observed while downloading — used to seed the Paused state. */
    @Volatile
    private var lastKnownProgress: Int = 0

    /**
     * Artifact last seen transferring. Seeds the Paused and WaitingForNetwork states, which
     * the manager builds itself and which still need the right label.
     */
    @Volatile
    private var lastKnownPhase: DownloadPhase = DownloadPhase.LANGUAGE_MODEL

    /**
     * The encoder half failed terminally on the last run.
     *
     * Once set, [ModelState.Ready] stops waiting for it and the download is not re-queued on
     * every SDK init. "Simple words" is the capability the user asked for; dense retrieval is
     * an improvement to it, and losing the improvement must not strand a language model that
     * has already arrived. Cleared by an explicit retry through [triggerDownload].
     */
    @Volatile
    private var encoderGaveUp: Boolean = false

    init {
        // Reconcile probes getExternalFilesDir + File.exists() — run it off the
        // constructing thread (the SDK can force this manager on the main
        // thread during Application.onCreate). Guarded on Idle so a WorkInfo
        // emission that lands first (a live download) is not overwritten.
        scope.launch(Dispatchers.IO) {
            if (_state.value is ModelState.Idle) reconcileReadyState()
        }
        observeUniqueWork()
    }

    /**
     * Cancels the WorkManager observation scope. Called by
     * [com.medtroniclabs.microcoaching.MicroCoachingSDK.shutdown] when the SDK
     * instance is replaced — WorkManager's flow listener is a GC root, so a
     * never-cancelled observer keeps this manager (and its config) reachable
     * for the process lifetime. The manager is unusable afterwards.
     */
    fun close() {
        scope.cancel()
    }

    /**
     * Reconcile the persisted flags against on-disk state, once per process.
     *
     * The decision itself is [modelFileVerdict]; this method supplies its inputs and carries
     * out the result. Splitting them keeps the one part with real branching — an incomplete
     * file is indistinguishable from a corrupt one, so only the surrounding state can tell
     * them apart — testable without a `Context` or WorkManager.
     */
    private fun reconcileReadyState() {
        val prefsReady = prefs.getBoolean(KEY_MODEL_READY, false)
        val file = findLocalModel()
        val paused = prefs.getBoolean(KEY_DOWNLOAD_PAUSED, false)
        val verdict = modelFileVerdict(
            prefsReady = prefsReady,
            fileExists = file != null,
            structurallyValid = file != null && validateLocalModel(file) == null,
            downloadWorkActive = file != null && isDownloadWorkActive(),
            userPaused = paused,
        )
        Log.i(
            TAG,
            "Reconcile: verdict=$verdict prefsReady=$prefsReady paused=$paused " +
                "file=${file?.name ?: "∅"} bytes=${file?.length() ?: 0}",
        )
        when (verdict) {
            ModelFileVerdict.ADOPT_READY -> emitReadyOrCorrupt(file!!)
            ModelFileVerdict.CLEAR_STALE_FLAG -> clearReadyFlag()
            ModelFileVerdict.RESUMABLE_PARTIAL -> {
                // Restores the pause the user left, rather than presenting their partial as a
                // damaged file. lastKnownProgress is seeded too, so a later pause without a
                // fresh RUNNING emission still reports the percent they last saw.
                val progress = prefs.getInt(KEY_DOWNLOAD_PAUSED_PROGRESS, 0)
                lastKnownProgress = progress
                _state.value = ModelState.Paused(progress, lastKnownPhase)
            }
            // The worker is mid-write; it will emit its own state when it finishes.
            ModelFileVerdict.LEAVE_IN_FLIGHT -> Unit
            // Re-runs the validation to produce the defect string for the Corrupt state, and
            // routes deletion through the single gate that owns it.
            ModelFileVerdict.DELETE_CORRUPT -> emitReadyOrCorrupt(file!!)
            ModelFileVerdict.NO_OP -> Unit
        }
    }

    /** Records a pause durably so [reconcileReadyState] can tell it from a failed download. */
    private fun persistPaused(progressPercent: Int) {
        prefs.edit()
            .putBoolean(KEY_DOWNLOAD_PAUSED, true)
            .putInt(KEY_DOWNLOAD_PAUSED_PROGRESS, progressPercent)
            .apply()
    }

    /**
     * Clears the durable pause. Called wherever a transfer stops being paused — resumed,
     * cancelled, or completed — so a stale flag can never rescue an unrelated partial file
     * from deletion later.
     */
    private fun clearPaused() {
        prefs.edit()
            .remove(KEY_DOWNLOAD_PAUSED)
            .remove(KEY_DOWNLOAD_PAUSED_PROGRESS)
            .apply()
    }

    /**
     * Single gate between "a file is on disk" and [ModelState.Ready].
     *
     * Passing the check persists the ready flag and emits Ready. Failing it deletes the file
     * — a re-download replaces anything lost — clears the flag, and emits
     * [ModelState.Corrupt] with both byte counts for the UI to report.
     *
     * Exception: while a download is in flight ([isDownloadWorkActive]) a failing check
     * means "not finished yet", so the file is left alone and no state is emitted. Every
     * partial download fails the check by definition.
     *
     * Variants with no validator are adopted without a structural check.
     */
    private fun emitReadyOrCorrupt(file: File) {
        val defect = validateLocalModel(file)
        if (defect == null) {
            val expected = config.selectedModelVariant().sizeInBytes
            Log.i(TAG, "Model ready: ${file.name} (${file.length()} bytes) → ${file.absolutePath}")
            // Informational, never fatal: a mismatch means a stale constant or a host URL
            // override, not a bad file. Length is the server's call, not the catalog's.
            if (file.length() != expected) {
                Log.w(TAG, "Model size differs from the catalog: ${file.length()} vs $expected bytes")
            }
            persistReadyFlag(file)
            // The language model is usable, but the mode it unlocks is announced only once
            // the whole download has landed. Reporting Ready here would load the engine and
            // flip the bar to "On this phone · simple words" while 171 MB is still arriving,
            // and the user would see the download apparently finish twice.
            when (
                EncoderModelRule.encoderWaitAction(
                    awaiting = encoderOutstanding(),
                    downloadActive = isDownloadWorkActive(),
                    autoDownloadAllowed = config.modelDownloadStrategy != ModelDownloadStrategy.PROVIDED &&
                        config.modelDownloadStrategy != ModelDownloadStrategy.MANUAL,
                )
            ) {
                EncoderWaitAction.HOLD -> {
                    Log.i(TAG, "Language model ready; encoder still downloading")
                    lastKnownPhase = DownloadPhase.EMBEDDINGS
                    _state.value = ModelState.Downloading(
                        progressPercent = lastKnownProgress,
                        phase = DownloadPhase.EMBEDDINGS,
                    )
                    return
                }
                EncoderWaitAction.SCHEDULE -> {
                    // The upgrade path: this device had the language model before dense
                    // retrieval existed, so it reaches the gate with nothing in flight.
                    Log.i(TAG, "Language model ready; scheduling the outstanding encoder")
                    lastKnownPhase = DownloadPhase.EMBEDDINGS
                    scheduleDownload()
                    return
                }
                EncoderWaitAction.RELEASE -> Unit
            }
            _state.value = ModelState.Ready(file)
            return
        }

        val onDiskBytes = file.length()
        val expectedBytes = config.selectedModelVariant().sizeInBytes

        // An in-flight worker's output file is supposed to fail the check: it stays an
        // invalid bundle until the last byte lands. Deleting it would destroy a healthy
        // transfer and leave the worker writing to an unlinked inode, so a live download
        // wins. Reachable when the process restarts mid-download.
        if (isDownloadWorkActive()) {
            Log.i(
                TAG,
                "Model file incomplete ($onDiskBytes of $expectedBytes bytes) but a download " +
                    "is in flight — leaving it alone and awaiting the worker",
            )
            return
        }

        Log.e(
            TAG,
            "Model file rejected: ${file.name} is $onDiskBytes bytes (expected $expectedBytes) — $defect. Deleting.",
        )
        if (file.delete()) Log.w(TAG, "Deleted unusable model file: ${file.name}")
        clearReadyFlag()
        _state.value = ModelState.Corrupt(
            reason = defect,
            onDiskBytes = onDiskBytes,
            expectedBytes = expectedBytes,
            canRetry = corruptRetriesRemaining() > 0,
        )
    }

    /**
     * True when the unique download work is queued, running, or waiting to retry — i.e.
     * when the model file on disk may still be growing.
     *
     * [_state] answers first and for free, covering the in-process cases. The blocking
     * WorkManager lookup covers the one it can't: a freshly constructed manager reconciling
     * against a download that survived process death. That path runs on [Dispatchers.IO],
     * and [WORK_QUERY_TIMEOUT_SECONDS] bounds a call arriving from the main thread.
     *
     * Fails safe — a query that throws or times out reports "active", since a false positive
     * only delays readiness whereas a false negative deletes a healthy transfer.
     */
    private fun isDownloadWorkActive(): Boolean {
        if (_state.value.isTransferInFlight()) return true

        return runCatching {
            WorkManager.getInstance(config.context)
                .getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
                .get(WORK_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .any { !it.state.isFinished }
        }.getOrElse { cause ->
            Log.w(TAG, "Could not determine download work state (${cause.message}) — assuming active")
            true
        }
    }

    /**
     * Structural verdict for [file] — null when usable, else a short reason.
     *
     * Routed by container, deliberately matching the download worker
     * ([ModelDownloadWorker.validatorFor]): a file adopted here without a check is a file
     * the engine is then handed. Returns null ("no objection") for a format whose container
     * cannot be cheaply asserted, so an unvalidatable one is never reported as corrupt.
     */
    private fun validateLocalModel(file: File): String? {
        val variant = config.selectedModelVariant()
        if (!ModelCatalog.isLiteRtLmBundle(variant)) return null
        return ModelFileIntegrity.validateLiteRtLmBundle(
            file,
            // The observed Content-Length once this variant has been downloaded; the
            // catalog figure on a first reconcile.
            ModelSizeProbe.cachedSize(config.context, variant) ?: variant.sizeInBytes,
        )
    }

    /**
     * Marks [file] as the ready model and resets the corrupt-retry budget, so a later
     * corruption starts with a fresh allowance instead of inheriting a spent one.
     */
    private fun persistReadyFlag(file: File) {
        prefs.edit()
            .putBoolean(KEY_MODEL_READY, true)
            .putString(KEY_MODEL_PATH, file.absolutePath)
            .remove(KEY_CORRUPT_RETRIES)
            // A completed file has nothing left to resume, so any pause recorded against it
            // is spent. Cleared here because every route to Ready passes through this method.
            .remove(KEY_DOWNLOAD_PAUSED)
            .remove(KEY_DOWNLOAD_PAUSED_PROGRESS)
            .apply()
    }

    private fun clearReadyFlag() {
        prefs.edit().remove(KEY_MODEL_READY).remove(KEY_MODEL_PATH).apply()
    }

    /**
     * Re-downloads still allowed after a confirmed-corrupt file. The model runs to hundreds
     * of MB and [MicroCoachingConfig.wifiOnlyModelDownload] may be off, so refetching until
     * it works is not free. Past the budget the UI still reports the file as unusable but
     * stops offering a retry.
     */
    private fun corruptRetriesRemaining(): Int =
        (MAX_CORRUPT_RETRIES - prefs.getInt(KEY_CORRUPT_RETRIES, 0)).coerceAtLeast(0)

    private fun recordCorruptRetry() {
        val used = prefs.getInt(KEY_CORRUPT_RETRIES, 0) + 1
        prefs.edit().putInt(KEY_CORRUPT_RETRIES, used).apply()
        Log.i(TAG, "Corrupt-model re-download $used of $MAX_CORRUPT_RETRIES")
    }

    /**
     * Returns the on-disk file for the **selected** model variant, or null.
     *
     * Matches the selected variant's exact [ModelVariant.fileName] (not "first
     * `.task` on disk") so multiple variants can coexist during A/B testing and
     * switching the selection is deterministic.
     */
    fun findLocalModel(): File? {
        val dir = config.context.getExternalFilesDir(null) ?: return null
        val expected = config.selectedModelVariant().fileName
        return dir.listFiles()?.firstOrNull { it.name == expected }
    }

    /**
     * The selected variant's real download size — network on the first call,
     * cached thereafter. Null when it can't be determined, leaving callers to
     * show the catalog's approximate value.
     */
    suspend fun resolveModelSizeBytes(): Long? =
        ModelSizeProbe.resolveSize(config.context, config.selectedModelVariant(), config.huggingFaceToken)
            ?.let { it + encoderBytes() }

    /** Previously resolved size for the selected variant, without touching the network. */
    fun cachedModelSizeBytes(): Long? =
        ModelSizeProbe.cachedSize(config.context, config.selectedModelVariant())
            ?.let { it + encoderBytes() }

    /**
     * The encoder's contribution to the figure quoted before opting in, or zero when this
     * device will not fetch it.
     *
     * Both sizes above are what the offer card and the answering sheet interpolate as the
     * one-time download, and the user is agreeing to one download covering both artifacts —
     * quoting the language model alone would understate it by ~176 MB.
     */
    private fun encoderBytes(): Long =
        if (includeEncoder()) EncoderModel.totalBytes else 0L

    /** Returns true if a model file is present on device (regardless of integrity). */
    fun isModelPresent(): Boolean = findLocalModel() != null

    /**
     * Bytes of the download already on disk, or null when none of it is. Callers pair it with
     * the expected size so the UI can state both rather than asserting the expected one over
     * a partial file.
     *
     * Covers every artifact, not just the language model: the figure it is compared against
     * is the whole download, so counting one file would leave a finished download reading
     * "498 MB of 681 MB" forever.
     */
    fun localModelSizeBytes(): Long? {
        val dir = config.context.getExternalFilesDir(null) ?: return findLocalModel()?.length()
        return DownloadPlan.presentBytes(dir, downloadPlan()).takeIf { it > 0L }
    }

    /**
     * Schedule a download via WorkManager if no model is present.
     * Download respects [MicroCoachingConfig.wifiOnlyModelDownload].
     *
     * No-op if:
     *   - A model file already exists on device
     *   - Strategy is [ModelDownloadStrategy.MANUAL] or [ModelDownloadStrategy.PROVIDED]
     *
     * Consent is the **caller's** precondition, not this method's: the model is optional, so
     * callers must confirm [com.medtroniclabs.microcoaching.MicroCoachingSDK.localModelEnabled]
     * first. Checked here it would need its own
     * [com.medtroniclabs.microcoaching.ai.model.LocalModelPrefs], and a second instance over
     * the same file carries a second `StateFlow` that no longer reflects writes through the
     * first.
     */
    fun scheduleDownloadIfNeeded() {
        if (config.modelDownloadStrategy == ModelDownloadStrategy.PROVIDED ||
            config.modelDownloadStrategy == ModelDownloadStrategy.MANUAL
        ) return

        val existing = findLocalModel()
        if (existing != null && encoderOutstanding()) {
            // The upgrade path: a device that already has the language model from before
            // dense retrieval existed needs the encoder alone, not another 500 MB.
            Log.i(TAG, "Model present but encoder missing — scheduling the encoder half")
            scheduleDownload()
            return
        }
        if (existing != null) {
            // Present is not the same as usable, so this goes through the validation gate
            // rather than announcing Ready on the strength of a filename. No replacement is
            // queued for a rejected file: a refetch that large is the user's call via the
            // setup card, not a side effect of opening the app.
            Log.i(TAG, "Model already present — validating instead of downloading")
            emitReadyOrCorrupt(existing)
            return
        }

        scheduleDownload()
    }

    /**
     * Whether the query encoder rides along with the language model on this device.
     *
     * [EncoderModelRule] is the one home for this gate; the verdict it returns names the
     * reason, which is the only way to tell a flag-off build from a device below the RAM
     * tier from a host that forgot its Hugging Face token.
     */
    private fun includeEncoder(): Boolean {
        val dir = config.context.getExternalFilesDir(null)
        val verdict = EncoderModelRule.evaluate(
            enableDenseRetrieval = config.enableDenseRetrieval,
            isLowEndDevice = config.forceLowEndMode ?: DeviceCapability.isLowEndDevice(config.context),
            filesPresent = dir != null && EncoderModel.filesPresent(File(dir, EncoderModel.DIR_NAME)),
            hasAccessToken = config.huggingFaceToken.isNotBlank(),
        )
        return with(EncoderModelRule) { verdict.wantsEncoder() }
    }

    /** The files this download covers, language model first. */
    private fun downloadPlan(): List<DownloadArtifact> =
        DownloadPlan.artifacts(config.selectedModelVariant(), includeEncoder())

    /**
     * True when the encoder is wanted but not yet completely on disk.
     *
     * Also false once the worker has reported the encoder as terminally failed, so a device
     * that cannot fetch it does not re-queue the download on every SDK init.
     */
    /** Phase of the first artifact still to fetch; the language model when nothing is. */
    private fun firstOutstandingPhase(): DownloadPhase {
        val dir = config.context.getExternalFilesDir(null) ?: return DownloadPhase.LANGUAGE_MODEL
        return DownloadPlan.remaining(dir, downloadPlan()).firstOrNull()?.phase
            ?: DownloadPhase.LANGUAGE_MODEL
    }

    private fun encoderOutstanding(): Boolean {
        val dir = config.context.getExternalFilesDir(null) ?: return false
        return EncoderModelRule.shouldAwaitEncoder(
            wantsEncoder = includeEncoder(),
            filesPresent = EncoderModel.filesPresent(File(dir, EncoderModel.DIR_NAME)),
            gaveUp = encoderGaveUp,
        )
    }

    /**
     * Manually trigger model download. Use when strategy is [ModelDownloadStrategy.MANUAL].
     * Safe to call multiple times — WorkManager deduplicates by unique work name.
     *
     * State-aware behaviour:
     *   - [ModelState.Corrupt]: the file was deleted when that state was emitted, so this is
     *     a fresh download spending one unit of the retry budget. Refused once it is gone.
     *   - [ModelState.LoadFailed]: a re-tap means "wipe and retry" — delete the file, clear
     *     the ready flag, re-download. The escape hatch for the structurally-valid but
     *     unloadable files [onModelLoadFailed] keeps.
     *   - Model present AND ready flag set: no-op, re-emit [ModelState.Ready] so a re-tap
     *     doesn't re-fetch an already-downloaded model.
     *   - Otherwise: schedule a new download.
     */
    fun triggerDownload() {
        // The user asking again clears a previous encoder give-up: a retry is exactly the
        // moment to attempt the half that failed last time.
        encoderGaveUp = false
        Log.i(TAG, "triggerDownload entry — currentState=${_state.value::class.simpleName}")
        logNetworkSnapshot("triggerDownload")

        val current = _state.value
        if (current is ModelState.Corrupt) {
            if (corruptRetriesRemaining() <= 0) {
                Log.w(TAG, "triggerDownload: corrupt-model retry budget spent — refusing to re-download")
                _state.value = current.copy(canRetry = false)
                return
            }
            Log.i(TAG, "triggerDownload: state=Corrupt → fresh download")
            // emitReadyOrCorrupt already deleted the file and cleared the flag; guard
            // anyway so a stale duplicate can't be resumed into.
            findLocalModel()?.let { f ->
                if (f.delete()) Log.w(TAG, "Deleted leftover unusable model file: ${f.name}")
            }
            clearReadyFlag()
            recordCorruptRetry()
            scheduleDownload()
            return
        }

        if (current is ModelState.LoadFailed) {
            Log.i(TAG, "triggerDownload: state=LoadFailed → wipe + redownload")
            findLocalModel()?.let { f ->
                if (f.delete()) Log.w(TAG, "Deleted unloadable model file: ${f.name}")
            }
            clearReadyFlag()
            scheduleDownload()
            return
        }

        val file = findLocalModel()
        // "Already ready" has to mean the whole plan, not just the language model. A device
        // that downloaded before dense retrieval existed has the file and the flag but no
        // encoder, and short-circuiting on those two alone is what would make an explicit
        // retry a no-op for the only artifact still missing.
        if (file != null && prefs.getBoolean(KEY_MODEL_READY, false) && !encoderOutstanding()) {
            Log.i(TAG, "triggerDownload: model already present and ready — no-op")
            _state.value = ModelState.Ready(file)
            return
        }
        scheduleDownload()
    }

    /**
     * Pause an in-flight download. Cancels the WorkManager job (stopping
     * network activity and freeing constraints) but leaves the partial file
     * on disk so [resumeDownload] can continue via HTTP `Range`.
     *
     * No-op when the current state is anything other than [ModelState.Downloading].
     * Sets [userPauseRequested] so the WorkInfo CANCELLED observer routes to
     * [ModelState.Paused] instead of treating this as a failure.
     */
    fun pauseDownload() {
        val current = _state.value
        if (current !is ModelState.Downloading && current !is ModelState.WaitingForNetwork) {
            Log.i(TAG, "pauseDownload: state=${current::class.simpleName} — ignored")
            return
        }
        userPauseRequested = true
        // Persisted before cancelling: cancellation is what makes the pause
        // indistinguishable from a failure on the next process start, so the record has to
        // outlive this process to be worth anything.
        persistPaused(lastKnownProgress)
        WorkManager.getInstance(config.context).cancelUniqueWork(UNIQUE_WORK_NAME)
        // Optimistic UI update — the CANCELLED observer fires asynchronously
        // and would otherwise leave the spinner spinning for a beat.
        _state.value = ModelState.Paused(lastKnownProgress, lastKnownPhase)
        Log.i(TAG, "pauseDownload: cancelled at ${lastKnownProgress}% — partial file kept")
    }

    /**
     * Resume a previously paused download. Re-enqueues the worker; the worker's
     * resumable streaming logic detects the partial file via `outputFile.exists()`
     * and asks the server for the remaining bytes with an HTTP `Range` header.
     */
    fun resumeDownload() {
        if (_state.value !is ModelState.Paused) {
            Log.i(TAG, "resumeDownload: state=${_state.value::class.simpleName} — ignored")
            return
        }
        scheduleDownload()
    }

    /**
     * Free space needed before a download is worth starting: whatever the variant still owes,
     * plus headroom so the system is not driven to zero.
     *
     * Returns null when there is enough, else a reason. An unreadable volume returns null —
     * the same fail-open stance [com.medtroniclabs.microcoaching.data.asset.AssetCache] takes,
     * since a failed probe is not evidence of a full disk.
     */
    private fun insufficientSpaceReason(): String? {
        val dir = config.context.getExternalFilesDir(null) ?: return null
        // Every artifact still outstanding, under one headroom allowance — reserving it
        // per file would demand 128 MB of slack for a two-part download.
        val outstanding = DownloadPlan.remainingBytes(dir, downloadPlan())
        if (outstanding <= 0L) return null
        val needed = outstanding + SPACE_HEADROOM_BYTES
        val available = runCatching { StatFs(dir.path).availableBytes }.getOrElse { cause ->
            Log.w(TAG, "Could not read free space (${cause.message}) — proceeding")
            return null
        }
        if (available >= needed) return null
        Log.e(TAG, "Refusing download: need $needed bytes, $available available")
        return "Not enough free space: ${needed / 1_048_576} MB required, " +
            "${available / 1_048_576} MB available"
    }

    /**
     * Cancel a download outright — stops the worker, deletes the partial file,
     * clears the persisted ready flag, and resets state to [ModelState.Idle].
     * Use when the user actively gives up on the download (as opposed to
     * deferring it via [pauseDownload]).
     */
    fun cancelDownload() {
        val current = _state.value
        if (!current.isTransferInFlight()) {
            Log.i(TAG, "cancelDownload: state=${current::class.simpleName} — ignored")
            return
        }
        userPauseRequested = false  // ensure observer doesn't misread as pause
        clearPaused()
        WorkManager.getInstance(config.context).cancelUniqueWork(UNIQUE_WORK_NAME)
        findLocalModel()?.let { f ->
            if (f.delete()) Log.w(TAG, "cancelDownload: deleted partial file ${f.name}")
        }
        clearReadyFlag()
        lastKnownProgress = 0
        _state.value = ModelState.Idle
        Log.i(TAG, "cancelDownload: partial file removed, state reset to Idle")
    }

    private fun scheduleDownload() {
        // Checked before enqueue so the failure is one sentence the user can act on, rather
        // than a native ENOSPC surfacing minutes into a transfer that was never going to fit.
        insufficientSpaceReason()?.let { reason ->
            _state.value = ModelState.DownloadFailed(reason)
            return
        }

        // The pause is over the moment new work is enqueued, and the flag has to go with it —
        // left set, it would later rescue an unrelated partial from deletion.
        clearPaused()

        val networkType = if (config.wifiOnlyModelDownload) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val providerKeys = config.modelProviders.map { it.toKey() }.toTypedArray()

        val workRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_PROVIDERS to providerKeys,
                    ModelDownloadWorker.KEY_BACKEND_URL to config.backendUrl,
                    ModelDownloadWorker.KEY_AUTH_TOKEN to config.authToken,
                    ModelDownloadWorker.KEY_HF_TOKEN to config.huggingFaceToken,
                    ModelDownloadWorker.KEY_HF_URL to config.huggingFaceModelUrl,
                    ModelDownloadWorker.KEY_MODEL_ID to config.selectedModelId,
                    ModelDownloadWorker.KEY_INCLUDE_ENCODER to includeEncoder(),
                )
            )
            .addTag(DOWNLOAD_TAG)
            .build()

        WorkManager.getInstance(config.context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, workRequest)

        // Name the phase that is actually outstanding, so an encoder-only run does not
        // flash the "simple words" label before the worker's first progress arrives.
        _state.value = ModelState.Downloading(progressPercent = 0, phase = firstOutstandingPhase())
        Log.i(
            TAG,
            "Model download scheduled — providers=${config.modelProviders.map { it::class.simpleName }}, " +
                "wifiOnly=${config.wifiOnlyModelDownload}, requiredNetworkType=$networkType",
        )
        logNetworkSnapshot("scheduleDownload")
        // Observation runs for the manager's lifetime (see [observeUniqueWork] from init),
        // so no per-schedule observer is attached here.
    }

    /**
     * Long-lived collector for the unique download work's [WorkInfo] flow. Started once
     * from [init] so the manager reflects in-flight work even when no one called
     * [scheduleDownload] this process (e.g. a download that continued across a
     * screen-off / process restart).
     *
     * Guards against demoting terminal states ([ModelState.Ready] / [ModelState.LoadFailed]):
     * `getWorkInfosForUniqueWorkFlow` can replay a stale SUCCEEDED for a short retention
     * window, which must not overwrite a freshly-reconciled Ready.
     */
    private fun observeUniqueWork() {
        scope.launch {
            WorkManager.getInstance(config.context)
                .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
                .collect { infoList ->
                    val info = infoList.firstOrNull() ?: return@collect
                    Log.i(TAG, "observeUniqueWork: state=${info.state} jobId=${info.id}")
                    // Once the model is genuinely loaded or we've explicitly
                    // marked it bad, ignore any further WorkInfo emissions.
                    val current = _state.value
                    if (current is ModelState.Ready && info.state != WorkInfo.State.RUNNING) {
                        return@collect
                    }
                    if (current is ModelState.LoadFailed) return@collect
                    // Corrupt is terminal in the same way: the file is gone and only an
                    // explicit re-download moves us on (which sets Downloading first, so
                    // this guard can't block the recovery it enables). Without it, the
                    // SUCCEEDED replay window would re-announce the deleted file as Ready.
                    if (current is ModelState.Corrupt) return@collect

                    when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                            // Enqueued means the constraints are not yet satisfied, so no
                            // bytes are moving. Saying so lets the UI name the thing the user
                            // can act on instead of showing a progress bar that cannot move.
                            // A more specific state already held for this run wins.
                            if (current !is ModelState.Downloading && current !is ModelState.Paused) {
                                _state.value = ModelState.WaitingForNetwork(
                                    progressPercent = lastKnownProgress.takeIf { it > 0 } ?: -1,
                                    wifiOnly = config.wifiOnlyModelDownload,
                                    phase = lastKnownPhase,
                                )
                            }
                        }
                        WorkInfo.State.RUNNING -> {
                            val pct = info.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                            val bytes = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
                            val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                            val phase = info.progress.getString(ModelDownloadWorker.KEY_PHASE)
                                ?.let { name -> runCatching { DownloadPhase.valueOf(name) }.getOrNull() }
                                ?: lastKnownPhase
                            lastKnownProgress = pct
                            lastKnownPhase = phase
                            _state.value = ModelState.Downloading(
                                progressPercent = pct,
                                bytesDownloaded = bytes,
                                totalBytes = total,
                                phase = phase,
                            )
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            // A successful download that could not fetch the encoder says so
                            // here. Recording it before the gate below is what lets Ready be
                            // announced on the language model alone rather than waiting for
                            // an artifact that is not coming.
                            info.outputData.getString(ModelDownloadWorker.KEY_ENCODER_ERROR)
                                ?.takeIf { it.isNotBlank() }
                                ?.let { reason ->
                                    Log.w(TAG, "Encoder unavailable, dense retrieval stays off: $reason")
                                    encoderGaveUp = true
                                }
                            val path = info.outputData.getString(ModelDownloadWorker.KEY_FILE_PATH)
                            val file = path?.let { File(it) }?.takeIf { it.exists() }
                            if (file != null) {
                                // The worker already validated before reporting success; re-checking
                                // here costs milliseconds and closes the gap where a replayed
                                // SUCCEEDED refers to a file that has since been truncated or
                                // swapped. Ready is only ever emitted through this gate.
                                //
                                // This collector runs on Main.immediate, so the check is hopped to
                                // IO — it opens a file, unlike the rest of this branch.
                                withContext(Dispatchers.IO) { emitReadyOrCorrupt(file) }
                            } else {
                                clearReadyFlag()
                                _state.value =
                                    ModelState.DownloadFailed("Model file missing after download completed")
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                                ?: "All providers failed"
                            Log.e(TAG, "Download failed: $error")
                            _state.value = ModelState.DownloadFailed(error)
                        }
                        WorkInfo.State.CANCELLED -> {
                            // Distinguish a user pause from a genuine cancellation. The
                            // pause flow cancels the unique work to stop network activity,
                            // but the partial file is preserved on disk and resumeDownload()
                            // picks it back up via HTTP Range.
                            if (userPauseRequested) {
                                userPauseRequested = false
                                Log.i(TAG, "Download paused at ${lastKnownProgress}%")
                                _state.value = ModelState.Paused(lastKnownProgress, lastKnownPhase)
                            } else if (current !is ModelState.Ready) {
                                _state.value = ModelState.DownloadFailed("Download cancelled")
                            }
                        }
                    }
                }
        }
    }

    /**
     * Called when the inference engine fails to load the model file. The structural check,
     * not the file's length, decides its fate, because the two causes need opposite handling:
     *
     *   - fails validation → the bytes are wrong and no retry can load it. Delete and
     *     surface [ModelState.Corrupt], which offers a re-download.
     *   - passes validation → the bytes are fine and something else failed (transient
     *     native/mmap error, engine version mismatch). Keep it and surface
     *     [ModelState.LoadFailed] so re-entering chat re-attempts the load.
     *
     * To delete without consulting the validator, use [deleteModelAndReset].
     */
    fun onModelLoadFailed(reason: String = "Model file failed to load") {
        // A load failure while a download is in flight means the engine raced the
        // worker on a partial file — not a real failure. Ignoring it avoids locking in
        // LoadFailed (which observeUniqueWork then respects) and deleting in-flight bytes.
        val currentState = _state.value
        if (currentState.isTransferInFlight()) {
            Log.w(
                TAG,
                "onModelLoadFailed: download in flight ($currentState) — ignoring '$reason'",
            )
            return
        }

        val file = findLocalModel()
        if (file == null) {
            Log.w(TAG, "onModelLoadFailed with no file on disk: $reason")
            _state.value = ModelState.LoadFailed(reason)
            return
        }

        val defect = validateLocalModel(file)
        if (defect != null) {
            Log.e(
                TAG,
                "Load failed and the file is unusable (${file.length()} bytes, $defect) — deleting so a re-download can fix it",
            )
            emitReadyOrCorrupt(file)   // validation already failed → deletes and emits Corrupt
            return
        }

        Log.w(
            TAG,
            "Load failed but ${file.name} is structurally valid (${file.length()} bytes) — keeping for retry: $reason",
        )
        _state.value = ModelState.LoadFailed(reason)
    }

    /**
     * Removes the model at the user's request and returns to [ModelState.Idle].
     *
     * Distinct from [deleteModelAndReset], which lands on [ModelState.LoadFailed]: that is the
     * right report for a file that failed us, and the wrong one for a file the user chose to
     * remove. Presented as a failure, a deliberate opt-out reads as something to fix, and the
     * UI offers a retry for it.
     *
     * The ready flag and the pause record go with the file, so a later opt-in starts clean
     * rather than inheriting state describing bytes that are gone. The corrupt-retry budget is
     * deliberately left alone — it tracks a server sending bad bytes, which this says nothing
     * about.
     *
     * Callers must have unloaded the inference engine first; deleting a file the engine holds
     * mapped is a native crash.
     */
    fun deleteModelForUserOptOut() {
        val file = findLocalModel()
        if (file == null) {
            Log.i(TAG, "deleteModelForUserOptOut: no file on disk — resetting state only")
        } else if (file.delete()) {
            Log.i(TAG, "deleteModelForUserOptOut: deleted ${file.name} (${file.length()} bytes)")
        } else {
            Log.w(TAG, "deleteModelForUserOptOut: could not delete ${file.name}")
        }
        // The encoder came down as part of the same download and the space figure the user
        // was shown covers both, so opting out has to reclaim both. Left behind it would be
        // 176 MB the user was told they had freed.
        config.context.getExternalFilesDir(null)?.let { dir ->
            DownloadPlan.artifacts(config.selectedModelVariant(), includeEncoder = true)
                .filter { it.phase == DownloadPhase.EMBEDDINGS }
                .map { DownloadPlan.fileFor(dir, it) }
                .forEach { encoderFile ->
                    if (encoderFile.exists() && encoderFile.delete()) {
                        Log.i(TAG, "deleteModelForUserOptOut: deleted ${encoderFile.name}")
                    }
                }
        }
        encoderGaveUp = false
        clearReadyFlag()
        clearPaused()
        lastKnownProgress = 0
        _state.value = ModelState.Idle
    }

    /**
     * Explicit teardown for a confirmed-bad model: deletes the file, clears the
     * persisted ready flag, and surfaces [ModelState.LoadFailed]. Use this only when
     * corruption is positively confirmed (e.g. a failed [verifyIntegrity] check) or when
     * the user explicitly chooses "re-download" from the LoadFailed CTA.
     */
    fun deleteModelAndReset(reason: String = "Model file deleted by user action") {
        findLocalModel()?.let { file ->
            if (file.delete()) {
                Log.w(TAG, "Deleted model file: ${file.name}")
            }
        }
        clearReadyFlag()
        _state.value = ModelState.LoadFailed(reason)
    }

    /**
     * Opt-in SHA-256 integrity check for a model file. Not called automatically — the
     * download path uses only the size floor; wire this in where a hash is available.
     * @param expectedHash Expected hex digest, or null to skip verification.
     */
    fun verifyIntegrity(file: File, expectedHash: String?): Boolean {
        if (expectedHash == null) return true
        val actualHash = file.sha256()
        val ok = actualHash.equals(expectedHash, ignoreCase = true)
        if (!ok) Log.e(TAG, "Integrity check failed for ${file.name}: expected=$expectedHash actual=$actualHash")
        return ok
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** @see NetworkDiagnostics.logSnapshot */
    private fun logNetworkSnapshot(stage: String) =
        com.medtroniclabs.microcoaching.util.NetworkDiagnostics.logSnapshot(config.context, TAG, stage)

    companion object {
        private const val TAG = "ModelManager"
        const val DOWNLOAD_TAG = "microcoaching_model_download"
        const val UNIQUE_WORK_NAME = "microcoaching_model_download"

        // Completeness floor is per-variant — see ModelCatalog.minValidSizeBytes().

        private const val PREFS_NAME = com.medtroniclabs.microcoaching.util.PrefsNames.MODEL
        private const val KEY_MODEL_READY = "model_ready"
        private const val KEY_MODEL_PATH = "model_path"

        /**
         * Durable record of a user-initiated pause. Pausing cancels the WorkManager job, so
         * without this the partial file is indistinguishable from a failed download on the
         * next process start.
         */
        private const val KEY_DOWNLOAD_PAUSED = "download_paused"
        private const val KEY_DOWNLOAD_PAUSED_PROGRESS = "download_paused_progress"

        /**
         * Free space kept in reserve beyond the model's own size, so a download that just
         * fits doesn't leave the device with nothing for Room, logs, or the OS.
         */
        private const val SPACE_HEADROOM_BYTES = 64L * 1024L * 1024L

        /** Re-download attempts allowed after a confirmed-corrupt file, per good copy. */
        private const val MAX_CORRUPT_RETRIES = 2
        private const val KEY_CORRUPT_RETRIES = "corrupt_retries"

        /** Cap on the blocking WorkManager lookup in [isDownloadWorkActive]. */
        private const val WORK_QUERY_TIMEOUT_SECONDS = 1L
    }
}
