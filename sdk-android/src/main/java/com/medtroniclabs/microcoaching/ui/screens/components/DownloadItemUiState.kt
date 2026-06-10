package com.medtroniclabs.microcoaching.ui.screens.components

import com.medtroniclabs.microcoaching.ai.voice.stt.SttModelState
import com.medtroniclabs.microcoaching.ui.chat.ChatUiState

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
}

/**
 * Project the Gemma AI model's fields out of the chat [ChatUiState.ModelNotReady]
 * into the shared card shape. Note we don't expose Paused directly from
 * the chat layer yet — Gemma's pause flag lives on `ChatUiState.ModelNotReady`
 * but the existing UX folds it under `isDownloading=false isPaused=true`.
 */
fun ChatUiState.ModelNotReady.toAiDownloadItemState(modelPresent: Boolean): DownloadItemUiState =
    when {
        modelPresent -> DownloadItemUiState.Done
        isDownloading && downloadProgress < 0 -> DownloadItemUiState.Preparing
        isDownloading -> DownloadItemUiState.Downloading(
            progressPercent = downloadProgress.coerceAtLeast(0),
            bytesDownloaded = downloadBytesDownloaded,
            totalBytes = downloadTotalBytes,
        )
        isPaused -> DownloadItemUiState.Paused(downloadProgress.coerceAtLeast(0))
        else -> DownloadItemUiState.Idle
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
