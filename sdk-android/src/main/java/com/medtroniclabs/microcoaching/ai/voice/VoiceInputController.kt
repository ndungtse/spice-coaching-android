package com.medtroniclabs.microcoaching.ai.voice

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper

/**
 * Speech-to-text controller for the chat mic button.
 *
 * Phase 3 ships this as a public interface with a no-op default impl. The mic
 * icon is rendered in [com.medtroniclabs.microcoaching.ui.common.ChatInputBar]
 * regardless — it telegraphs the upcoming feature to CHWs and lets hosts
 * preview the chrome without committing to a real STT engine.
 *
 * Phase 6 of the SDK roadmap (voice I/O) plugs in a real implementation
 * (Bangla Whisper / on-device STT). Hosts may also supply their own impl —
 * register via `MicroCoachingSDK.Builder.voiceInputController(...)` once the
 * builder method exists; until then, the SDK uses [NoOpVoiceInputController].
 *
 * **Lifecycle:** the controller is invoked from the Compose mic IconButton
 * tap handler. Implementations are responsible for permission acquisition,
 * audio capture, transcription, and calling [TranscriptionListener.onResult]
 * on the main thread.
 */
interface VoiceInputController {

    /** Whether the controller has a working STT engine ready to transcribe. */
    fun isAvailable(): Boolean

    /**
     * Begin capturing audio. Call [TranscriptionListener.onResult] with the
     * final transcript when listening completes. May call [TranscriptionListener.onPartial]
     * during capture to surface live transcript updates in the UI.
     */
    fun startListening(listener: TranscriptionListener)

    /** Cancel any in-flight capture. Safe to call when not capturing. */
    fun stopListening()

    interface TranscriptionListener {
        fun onPartial(transcript: String) {}
        fun onResult(transcript: String)
        fun onError(message: String) {}
    }
}

/**
 * Default no-op implementation. Surfaces a localized "coming soon" toast on tap.
 * Used when [com.medtroniclabs.microcoaching.MicroCoachingConfig.enableVoice] is false
 * (the default) or no host-provided controller is registered.
 */
class NoOpVoiceInputController(private val appContext: Context) : VoiceInputController {

    override fun isAvailable(): Boolean = false

    override fun startListening(listener: VoiceInputController.TranscriptionListener) {
        Log.i(TAG, "Voice input requested but no STT engine is wired — showing placeholder toast.")
        // Resolve the placeholder copy against the SDK-configured locale, not
        // the host's `appContext` default — matches AndroidSpeechRecognizerEngine.localized().
        val sdkLang = runCatching {
            MicroCoachingSDK.getInstance().language
        }.getOrDefault(Language.ENGLISH)
        val localedCtx = SdkLocaleHelper.wrap(appContext, sdkLang)
        Toast.makeText(
            appContext,
            localedCtx.getString(R.string.chat_voice_input_unavailable),
            Toast.LENGTH_SHORT,
        ).show()
    }

    override fun stopListening() = Unit

    private companion object {
        const val TAG = "NoOpVoiceInputController"
    }
}
