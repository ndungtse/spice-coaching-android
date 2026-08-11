package com.medtroniclabs.microcoaching.ai.voice.stt

import java.io.File

/**
 * Lifecycle state of an offline STT model managed by [SttModelManager]. Today the only
 * model is the Bengali sherpa-onnx streaming Zipformer.
 */
sealed class SttModelState {

    /** No download has started and no model is present on disk. */
    object Idle : SttModelState()

    /**
     * Download is in progress.
     *
     * @param progressPercent 0–100 once `Content-Length` is known, -1 while preparing.
     * @param bytesDownloaded total bytes received so far.
     * @param totalBytes total bytes expected, or 0 if the server hasn't reported it yet.
     */
    data class Downloading(
        val progressPercent: Int,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
    ) : SttModelState()

    /** Worker is extracting the .tar.bz2 archive after download. */
    object Extracting : SttModelState()

    /** Model files are unpacked on disk and ready for inference. */
    data class Ready(val modelDir: File) : SttModelState()

    /** Download or extraction failed. The chat UI surfaces [reason] and offers a retry. */
    data class Failed(val reason: String) : SttModelState()
}
