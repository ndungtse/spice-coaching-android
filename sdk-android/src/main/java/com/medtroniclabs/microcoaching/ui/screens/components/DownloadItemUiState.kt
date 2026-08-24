package com.medtroniclabs.microcoaching.ui.screens.components

import com.medtroniclabs.microcoaching.ai.model.ModelState
import com.medtroniclabs.microcoaching.ai.voice.stt.SttModelState

/**
 * Compact per-item state used by [DownloadItemCard]. Both the Gemma AI model
 * and the sherpa Bengali voice model map into this shape so the card composable
 * doesn't need to know which subsystem owns it.
 */
sealed class DownloadItemUiState {

    /** Not started — show a Download button. */
    object Idle : DownloadItemUiState()

    /** Queued by WorkManager but not yet streaming bytes. */
    object Preparing : DownloadItemUiState()

    /**
     * Scheduled, but its network constraint is unmet, so no bytes are moving and none will
     * until the network changes.
     *
     * Separate from [Preparing] because the wait is open-ended and has a cause the user can
     * act on. Shown as "preparing", a transfer waiting on Wi-Fi is indistinguishable from one
     * about to start, and the card invites waiting for something that will never arrive.
     *
     * @param wifiOnly true when an unmetered network is required.
     */
    data class WaitingForNetwork(
        val progressPercent: Int = -1,
        val wifiOnly: Boolean = false,
    ) : DownloadItemUiState()

    /**
     * Streaming bytes.
     *
     * @param progressPercent 0–100 once `Content-Length` is known, -1 while preparing.
     * @param bytesDownloaded received so far (0 until first emit).
     * @param totalBytes expected total, or 0 if the server hasn't reported it yet.
     */
    data class Downloading(
        val progressPercent: Int,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
    ) : DownloadItemUiState()

    /** Post-download archive extraction (sherpa STT model only). */
    object Extracting : DownloadItemUiState()

    /** User paused — partial bytes are preserved on disk; show a Resume button. */
    data class Paused(val progressPercent: Int) : DownloadItemUiState()

    /** Files are on disk and the engine accepts them. */
    object Done : DownloadItemUiState()

    /** Download or extraction failed; surface a Retry button. */
    data class Failed(val reason: String) : DownloadItemUiState()

    /**
     * The bytes arrived but are not usable — a truncated or damaged bundle. Distinct from
     * [Failed], where the transfer itself never finished, and from [Done], which would put a
     * check mark next to an error the user can't act on.
     *
     * @param reason short, user-safe explanation.
     * @param onDiskBytes what landed, so the card can show it against the expected size
     *   instead of asserting the expected size over a partial file.
     * @param canRetry false when the re-download budget is spent; the card then states the
     *   problem without offering an action.
     */
    data class Unusable(
        val reason: String,
        val onDiskBytes: Long? = null,
        val canRetry: Boolean = true,
    ) : DownloadItemUiState()
}

/**
 * Project the on-device model's lifecycle into the shared card shape.
 *
 * Maps straight off [ModelState] so the card and the model manager cannot disagree; the older
 * route through a screen's own flattened booleans had to be kept in sync by hand, and a stale
 * "downloaded" flag would render a check mark over a live transfer.
 *
 * @param damagedReason localized explanation for [ModelState.Corrupt] / [ModelState.LoadFailed],
 *   supplied by the caller because this function has no `Context`.
 */
fun ModelState.toAiDownloadItemState(damagedReason: String = ""): DownloadItemUiState =
    when (this) {
        is ModelState.Idle -> DownloadItemUiState.Idle
        is ModelState.WaitingForNetwork ->
            DownloadItemUiState.WaitingForNetwork(progressPercent, wifiOnly)
        is ModelState.Downloading ->
            if (progressPercent < 0) {
                DownloadItemUiState.Preparing
            } else {
                DownloadItemUiState.Downloading(
                    progressPercent = progressPercent.coerceAtLeast(0),
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytes,
                )
            }
        is ModelState.Paused -> DownloadItemUiState.Paused(progressPercent.coerceAtLeast(0))
        is ModelState.Ready -> DownloadItemUiState.Done
        is ModelState.DownloadFailed -> DownloadItemUiState.Failed(reason)
        // The bytes are wrong, which no retry of the load can fix — reported as damaged with
        // both counts so the card can state what arrived against what was expected.
        is ModelState.Corrupt -> DownloadItemUiState.Unusable(
            reason = damagedReason,
            onDiskBytes = onDiskBytes,
            canRetry = canRetry,
        )
        // The file is structurally sound and kept, so this is a retryable failure rather than
        // a damaged download.
        is ModelState.LoadFailed -> DownloadItemUiState.Failed(damagedReason)
    }

/** Project the sherpa Bengali voice model state into the shared card shape. */
fun SttModelState.toVoiceDownloadItemState(): DownloadItemUiState = when (this) {
    is SttModelState.Idle -> DownloadItemUiState.Idle
    is SttModelState.Downloading -> {
        if (progressPercent < 0) {
            DownloadItemUiState.Preparing
        } else {
            DownloadItemUiState.Downloading(
                progressPercent = progressPercent.coerceAtLeast(0),
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
            )
        }
    }
    is SttModelState.Extracting -> DownloadItemUiState.Extracting
    is SttModelState.Ready -> DownloadItemUiState.Done
    is SttModelState.Failed -> DownloadItemUiState.Failed(reason)
}
