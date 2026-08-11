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

    /** Model file is present and integrity-verified. Ready for inference. */
    data class Ready(val modelFile: File) : ModelState()

    /** Model failed to load into the inference engine. */
    data class LoadFailed(val reason: String) : ModelState()
}
