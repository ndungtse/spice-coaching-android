package com.medtroniclabs.microcoaching.ai.voice

import android.content.Context
import android.util.Log
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ai.voice.stt.SttModelManager
import com.medtroniclabs.microcoaching.ai.voice.stt.SttModelState
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Routes a chat mic tap to the right STT engine based on the current SDK
 * language, network state, and whether the offline Bengali model is on disk.
 *
 * Decision matrix (v1):
 *
 * | Language | Online? | Bengali model? | Engine                          |
 * |----------|---------|----------------|---------------------------------|
 * | English  | any     | n/a            | AndroidSpeechRecognizerEngine   |
 * | Bengali  | yes     | any            | AndroidSpeechRecognizerEngine   |
 * | Bengali  | no      | present        | Offline (sherpa) — when wired   |
 * | Bengali  | no      | missing        | error("model_missing")          |
 *
 * The offline engine slot ([offlineEngineFactory]) is **null** in v1; the
 * sherpa-onnx impl lands in the next change set. Until then, Bengali-offline
 * with-model surfaces the same "model_missing" error as without-model — the
 * chat UI uses [SttModelManager.state] to drive the download prompt either way.
 */
class ChatVoiceInputController internal constructor(
    private val appContext: Context,
    private val androidEngine: AndroidSpeechRecognizerEngine,
    private val sttModelManager: SttModelManager,
    /**
     * Factory for the offline Bengali engine. `null` until the sherpa-onnx
     * impl is wired (B2). When non-null, the orchestrator instantiates it
     * lazily with the model dir resolved by [SttModelManager.bengaliModelDir].
     */
    private val offlineEngineFactory: ((File) -> OfflineSttEngine)? = null,
) : VoiceInputController {

    /** Which backend served the *most recent* recognition attempt. */
    enum class Backend { PlatformOnDevice, PlatformCloud, OfflineSherpa, Unknown }

    private val _activeBackend = MutableStateFlow(Backend.Unknown)
    val activeBackend: StateFlow<Backend> = _activeBackend.asStateFlow()

    @Volatile
    private var offlineEngine: OfflineSttEngine? = null

    @Volatile
    private var activeEngine: VoiceInputController? = null

    override fun isAvailable(): Boolean =
        androidEngine.isAvailable() || (offlineEngineFactory != null && sttModelManager.isBengaliModelPresent())

    override fun startListening(listener: VoiceInputController.TranscriptionListener) {
        val sdk = runCatching { MicroCoachingSDK.getInstance() }.getOrNull()
        val language = sdk?.language ?: Language.ENGLISH
        val online = sdk?.isNetworkAvailable() ?: true

        val useOffline = language == Language.BANGLA &&
            !online &&
            sttModelManager.isBengaliModelPresent() &&
            offlineEngineFactory != null

        when {
            useOffline -> startOffline(listener)
            language == Language.BANGLA && !online && !sttModelManager.isBengaliModelPresent() -> {
                // No network + no offline model. Surface a model_missing error
                // so the chat UI can render the download prompt. Also auto-
                // trigger the download attempt so the worker is queued the
                // moment connectivity returns.
                _activeBackend.value = Backend.Unknown
                sttModelManager.triggerBengaliDownload()
                listener.onError(localized(R.string.chat_voice_error_offline_model_missing))
            }
            else -> {
                // Bengali + online + model missing: opportunistically kick off
                // the offline model download in the background while this
                // session goes through Google's cloud recognizer in parallel.
                // Defense-in-depth alongside the SDK-init auto-chain — catches
                // sideloaded AI installs where the chain emission never fired.
                if (language == Language.BANGLA && online &&
                    !sttModelManager.isBengaliModelPresent()
                ) {
                    val ss = sttModelManager.state.value
                    if (ss !is SttModelState.Downloading && ss !is SttModelState.Extracting) {
                        Log.i(TAG, "Mic-tap: BN+online+missing — kicking off background voice-model download")
                        sttModelManager.triggerBengaliDownload()
                    }
                }
                startPlatform(listener)
            }
        }
    }

    override fun stopListening() {
        activeEngine?.stopListening()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun startPlatform(listener: VoiceInputController.TranscriptionListener) {
        activeEngine = androidEngine
        _activeBackend.value =
            if (androidEngine.isOnDeviceForCurrentLanguage() == true) {
                Backend.PlatformOnDevice
            } else {
                Backend.PlatformCloud
            }
        // Wrap the host listener so we can clear activeEngine on completion
        // (idempotent — only the latest session matters).
        androidEngine.startListening(wrapListener(listener))
    }

    private fun startOffline(listener: VoiceInputController.TranscriptionListener) {
        val factory = offlineEngineFactory
        if (factory == null) {
            listener.onError(localized(R.string.chat_voice_error_engine))
            return
        }
        val engine = offlineEngine ?: factory(sttModelManager.bengaliModelDir()).also {
            offlineEngine = it
        }
        activeEngine = engine
        _activeBackend.value = Backend.OfflineSherpa
        engine.startListening(wrapListener(listener))
    }

    private fun wrapListener(
        delegate: VoiceInputController.TranscriptionListener,
    ): VoiceInputController.TranscriptionListener =
        object : VoiceInputController.TranscriptionListener {
            override fun onPartial(transcript: String) = delegate.onPartial(transcript)
            override fun onResult(transcript: String) {
                activeEngine = null
                delegate.onResult(transcript)
            }
            override fun onError(message: String) {
                activeEngine = null
                delegate.onError(message)
            }
        }

    private fun localized(resId: Int): String {
        val language = runCatching { MicroCoachingSDK.getInstance().language }
            .getOrDefault(Language.ENGLISH)
        return SdkLocaleHelper.wrap(appContext, language).getString(resId)
    }

    /** Observable lifecycle handle for downloads. Exposed to the chat UI. */
    val sttModelState: StateFlow<SttModelState> = sttModelManager.state

    fun release() {
        runCatching { androidEngine.destroy() }
            .onFailure { Log.w(TAG, "androidEngine.destroy threw: ${it.message}") }
        offlineEngine = null
        activeEngine = null
    }

    companion object {
        private const val TAG = "ChatVoiceInputController"
    }
}
