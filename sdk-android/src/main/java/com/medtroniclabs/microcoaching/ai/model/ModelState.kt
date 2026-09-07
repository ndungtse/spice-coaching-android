package com.medtroniclabs.microcoaching.ai.model

import java.io.File

/** Represents the current state of the on-device model lifecycle. */
sealed class ModelState {

    /** No model download has started and no model file is present. */
    object Idle : ModelState()

    /**
     * Model download is in progress.
     *
     * @param progressPercent 0–100 while running, -1 while preparing (queued / waiting for constraints).
     * @param bytesDownloaded total bytes received so far (0 until the first progress emit).
     * @param totalBytes total bytes expected, or 0 if the server hasn't reported `Content-Length`
     *   yet (chunked transfer). UI consumers should fall back to an indeterminate display when this is 0.
     * @param phase which artifact is moving. The figures above span the whole download, so
     *   this is the only thing that changes when the language model finishes and the encoder
     *   starts — it is what the label under the progress bar is chosen from.
     */
    data class Downloading(
        val progressPercent: Int,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
        val phase: DownloadPhase = DownloadPhase.LANGUAGE_MODEL,
    ) : ModelState()

    /**
     * The download is scheduled but its network constraint is unmet, so no bytes are moving
     * and none will until the network changes.
     *
     * Separate from [Downloading] because the two need opposite messages: a download that is
     * running wants a progress figure, whereas one waiting on Wi-Fi wants to say so. Reported
     * as indeterminate progress, they are indistinguishable, and a transfer that cannot start
     * reads as one that has stalled.
     *
     * @param progressPercent bytes already transferred by an earlier attempt, or -1 when none.
     * @param wifiOnly true when an unmetered network is required, which is the constraint a
     *   user can act on.
     */
    data class WaitingForNetwork(
        val progressPercent: Int = -1,
        val wifiOnly: Boolean = false,
        val phase: DownloadPhase = DownloadPhase.LANGUAGE_MODEL,
    ) : ModelState()

    /**
     * User explicitly paused an in-flight download. The partial file is
     * preserved on disk; calling [ModelManager.resumeDownload] continues from
     * where it left off via HTTP `Range` (see [ModelDownloadWorker.streamDownload]).
     *
     * Distinct from [DownloadFailed] so the UI can show a "Resume" affordance
     * without sounding like a recovery prompt.
     *
     * Durable: the pause is persisted, so it survives process death. Pausing cancels the
     * WorkManager job, which leaves a partial file next to a finished job — the same shape a
     * failed download leaves behind, and indistinguishable from it without the stored flag.
     */
    data class Paused(
        val progressPercent: Int,
        val phase: DownloadPhase = DownloadPhase.LANGUAGE_MODEL,
    ) : ModelState()

    /** Download failed. */
    data class DownloadFailed(val reason: String) : ModelState()

    /**
     * Model file is present and has passed its container's structural check
     * ([ModelFileIntegrity]). Ready for inference.
     *
     * Structure-verified, not hash-verified: the file is well-formed and complete, but not
     * proven to be the exact blob the catalog names. [ModelManager.verifyIntegrity] is the
     * opt-in SHA-256 check and nothing calls it.
     */
    data class Ready(val modelFile: File) : ModelState()

    /**
     * The engine rejected a file that passes the structural check — a transient native or
     * mmap failure, or a model this engine version can't run. Retryable: the file is kept
     * and re-entering chat re-attempts the load. Distinct from [Corrupt], where no retry
     * can succeed.
     */
    data class LoadFailed(val reason: String) : ModelState()

    /**
     * A model file failed the structural check, so its bytes are wrong. Already deleted;
     * only a fresh download resolves it. Separate from [LoadFailed] because offering a load
     * retry here would loop on the same bad bytes.
     *
     * @param onDiskBytes length of the rejected file, for the UI's "N of M".
     * @param expectedBytes what the selected variant should weigh.
     * @param canRetry false once the re-download budget is spent, so the UI stops offering
     *   an action that would be refused.
     */
    data class Corrupt(
        val reason: String,
        val onDiskBytes: Long,
        val expectedBytes: Long,
        val canRetry: Boolean = true,
    ) : ModelState()
}

/**
 * True while a transfer owns the model file — running, waiting on its network constraint, or
 * paused with a partial on disk.
 *
 * The three share one property that matters to every caller: the file is not finished, and
 * deleting it or handing it to the inference engine would destroy or misread a transfer that
 * is still going to complete.
 */
internal fun ModelState.isTransferInFlight(): Boolean = when (this) {
    is ModelState.Downloading,
    is ModelState.WaitingForNetwork,
    is ModelState.Paused -> true
    is ModelState.Idle,
    is ModelState.DownloadFailed,
    is ModelState.Ready,
    is ModelState.LoadFailed,
    is ModelState.Corrupt -> false
}
