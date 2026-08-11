package com.medtroniclabs.microcoaching.ai.voice

/**
 * Internal extension of [VoiceInputController.TranscriptionListener] that lets an
 * engine tell the orchestrator WHETHER a failure is recoverable by retrying on a
 * different (offline) engine — without leaking that detail into the public
 * listener the host implements.
 *
 * Why this exists: the engine-routing decision in [ChatVoiceInputController] reads
 * a point-in-time connectivity check ([com.medtroniclabs.microcoaching.MicroCoachingSDK.isNetworkAvailable]).
 * That check lags the actual disconnect — right after the device goes offline the
 * OS hasn't torn down `activeNetwork` yet, so it briefly still reports "online".
 * The first mic tap then routes to the cloud [AndroidSpeechRecognizerEngine],
 * which fails with a network-class error; only the second tap (once the check has
 * caught up) routes offline. The authoritative signal that we're actually offline
 * is the recognition failure itself — so when it's network-class and an offline
 * engine is viable, the orchestrator retries offline instead of surfacing the error.
 */
internal interface SttErrorListener : VoiceInputController.TranscriptionListener {

    /**
     * @param message localized, user-facing error text (what to show if not recovered).
     * @param recoverableViaOffline true for network-class failures (no network /
     *   timeout / server / generic client errors that an offline on-device engine
     *   would not hit) — a hint that retrying on the offline engine may succeed.
     */
    fun onError(message: String, recoverableViaOffline: Boolean)

    /** Public 1-arg path defaults to "not recoverable" so plain listeners are unaffected. */
    override fun onError(message: String) = onError(message, recoverableViaOffline = false)
}
