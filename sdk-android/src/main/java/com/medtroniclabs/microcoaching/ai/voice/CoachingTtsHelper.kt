package com.medtroniclabs.microcoaching.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Locale-agnostic Android Text-to-Speech wrapper.
 *
 * Used by both the chat (Bangla) and the lesson player (SDK-language-driven).
 * Differs from the previous Bangla-only helper in three ways:
 *  - Locale is a constructor argument, not hardcoded.
 *  - Wires an [UtteranceProgressListener] so callers can react to completion
 *    (the lesson player auto-advances on `onDone`).
 *  - Exposes a [state] flow so UI can swap icons / disable controls based on
 *    `Initializing / Idle / Speaking / LanguageMissing / Error`.
 */
class CoachingTtsHelper(
    private val context: Context,
    private val locale: Locale,
) {

    private var tts: TextToSpeech? = null
    private var pendingText: String? = null
    private var pendingOnDone: (() -> Unit)? = null

    /** Completion callback keyed by the utterance id that produced it. */
    private var activeUtteranceId: String? = null
    private var activeOnDone: (() -> Unit)? = null

    private val _state = MutableStateFlow<TtsState>(TtsState.Initializing)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            onInit(status)
        }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _state.value = TtsState.Error
            return
        }
        val result = tts?.isLanguageAvailable(locale)
        when (result) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                tts?.language = locale
                tts?.setOnUtteranceProgressListener(progressListener)
                _state.value = TtsState.Idle
                pendingText?.let { text ->
                    val cb = pendingOnDone
                    pendingText = null
                    pendingOnDone = null
                    speak(text, cb ?: {})
                }
            }
            TextToSpeech.LANG_MISSING_DATA -> {
                // Don't auto-bounce the user to system settings — that fired
                // unprompted on every VM init (chat AND lesson player). Just
                // surface the missing state; the coaching setup screen (and any
                // other caller) offers an explicit "Install" action wired to
                // [installLanguageData].
                _state.value = TtsState.LanguageMissing
            }
            else -> _state.value = TtsState.Error
        }
    }

    /**
     * Open the platform TTS-data installer so the user can download the missing
     * voice pack for [locale]. Called on demand (e.g. the setup screen's TTS
     * "Install" button) rather than automatically from [onInit]. Best-effort:
     * a device with no activity to handle the intent simply no-ops.
     */
    fun installLanguageData() {
        val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        runCatching { context.startActivity(intent) }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _state.value = TtsState.Speaking
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId == activeUtteranceId) {
                val cb = activeOnDone
                activeUtteranceId = null
                activeOnDone = null
                _state.value = TtsState.Idle
                cb?.invoke()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            if (utteranceId == activeUtteranceId) {
                activeUtteranceId = null
                activeOnDone = null
                _state.value = TtsState.Error
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            onError(utteranceId)
        }
    }

    /**
     * Speak [text] aloud, interrupting any current utterance. [onDone] runs on
     * successful completion of this exact utterance (later requests cancel it).
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        if (text.isBlank()) {
            onDone()
            return
        }
        val current = tts
        if (current == null || _state.value is TtsState.Initializing) {
            // Queue until the engine finishes initialising.
            pendingText = text
            pendingOnDone = onDone
            return
        }
        if (_state.value is TtsState.LanguageMissing || _state.value is TtsState.Error) return
        current.stop()
        val id = UUID.randomUUID().toString()
        activeUtteranceId = id
        activeOnDone = onDone
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        current.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    /** Cancel any in-flight utterance and clear its completion callback. */
    fun stop() {
        tts?.stop()
        activeUtteranceId = null
        activeOnDone = null
        pendingText = null
        pendingOnDone = null
        if (_state.value is TtsState.Speaking) _state.value = TtsState.Idle
    }

    /** Shut down the engine. Call from `ViewModel.onCleared`. */
    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }
}

sealed class TtsState {
    /** Engine still booting; `speak()` requests are queued. */
    data object Initializing : TtsState()

    /** Engine ready but not currently speaking. */
    data object Idle : TtsState()

    /** A `speak()` is in flight. */
    data object Speaking : TtsState()

    /**
     * Locale data isn't installed on the device. Read-aloud is unusable until
     * the user installs it. The helper no longer auto-launches the installer —
     * callers surface an explicit action wired to [CoachingTtsHelper.installLanguageData].
     */
    data object LanguageMissing : TtsState()

    /** Engine failed to initialise, or the locale isn't supported at all. */
    data object Error : TtsState()
}
