package com.medtroniclabs.microcoaching.ai.embedding

import java.io.File

/**
 * Lifecycle of the on-device query encoder, mirroring
 * [com.medtroniclabs.microcoaching.ai.voice.stt.SttModelState].
 *
 * [Skipped] has no STT equivalent and is the important one: the encoder is optional by
 * design, so "not here" is usually a correct outcome rather than a fault, and the
 * reason is the only way to tell a flag-off build from a device below the RAM tier from
 * a host that forgot its Hugging Face token.
 */
internal sealed class EncoderModelState {

    /** Nothing started; nothing on disk. */
    object Idle : EncoderModelState()

    /** @param progressPercent 0–100 once `Content-Length` is known, -1 while preparing. */
    data class Downloading(
        val progressPercent: Int,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
    ) : EncoderModelState()

    /** Both files are on disk at full length. */
    data class Ready(val modelDir: File) : EncoderModelState()

    /** Deliberately not downloaded — see [EncoderVerdict]. */
    data class Skipped(val verdict: EncoderVerdict) : EncoderModelState()

    /** The download failed. Chat stays on BM25-only; nothing surfaces to the CHW. */
    data class Failed(val reason: String) : EncoderModelState()
}
