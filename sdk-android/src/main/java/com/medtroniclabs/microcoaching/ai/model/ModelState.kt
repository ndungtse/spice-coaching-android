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
     */
    data class Downloading(
        val progressPercent: Int,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
    ) : ModelState()

    /**
     * User explicitly paused an in-flight download. The partial file is
     * preserved on disk; calling [ModelManager.resumeDownload] continues from
     * where it left off via HTTP `Range` (see [ModelDownloadWorker.streamDownload]).
     *
     * Distinct from [DownloadFailed] so the UI can show a "Resume" affordance
     * without sounding like a recovery prompt.
     */
    data class Paused(val progressPercent: Int) : ModelState()

    /** Download failed. */
    data class DownloadFailed(val reason: String) : ModelState()

    /**
     * Model file is present and has passed [ModelFileIntegrity.validateTaskBundle]. Ready
     * for inference.
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
