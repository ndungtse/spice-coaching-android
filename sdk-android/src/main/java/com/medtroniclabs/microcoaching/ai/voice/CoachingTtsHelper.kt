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
 * Locale-agnostic Android Text-to-Speech wrapper, used by the chat and the lesson
 * player.
 *
 * [locale] is the default voice; [speak] can override it per utterance so mixed-
 * language content is read by the voice matching each string (see
 * [localeForSpokenText]). Wires an [UtteranceProgressListener] so callers can react
 * to completion — the lesson player auto-advances on `onDone` — and exposes a
 * [state] flow so UI can swap icons or disable controls.
 */
class CoachingTtsHelper(
    private val context: Context,
    private val locale: Locale,
) {

    private var tts: TextToSpeech? = null
    private var pendingText: String? = null
    private var pendingLocale: Locale? = null
    private var pendingOnDone: (() -> Unit)? = null

    /**
     * The voice currently loaded into the engine, or null while no usable voice has
     * been applied. Tracked so [speak] only pays for `setLanguage` when the voice
     * actually has to change.
     */
    private var currentLocale: Locale? = null

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
        // Wired before the availability check: a device missing the default voice
        // can still speak another one via [speak]'s per-utterance override, and
        // without the listener its `onDone` would never fire, stalling the lesson
        // player's auto-advance.
        tts?.setOnUtteranceProgressListener(progressListener)
        when (tts?.isLanguageAvailable(locale)) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                tts?.language = locale
                currentLocale = locale
                _state.value = TtsState.Idle
                pendingText?.let { text ->
                    val cb = pendingOnDone
                    val pending = pendingLocale
                    pendingText = null
                    pendingLocale = null
                    pendingOnDone = null
                    speak(text, pending, cb ?: {})
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
     * Point the engine at [target], returning whether a usable voice is now loaded.
     *
     * A voice the device doesn't have leaves [currentLocale] alone, so the caller can
     * fall back to whatever is already loaded rather than going silent.
     */
    private fun applyLocale(target: Locale): Boolean {
        if (target == currentLocale) return true
        val engine = tts ?: return false
        val usable = when (engine.isLanguageAvailable(target)) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> true
            else -> false
        }
        if (!usable) return false
        engine.language = target
        currentLocale = target
        return true
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
     *
     * [utteranceLocale] overrides the default voice for this utterance only; null
     * uses the constructor locale. When the requested voice isn't installed the
     * currently-loaded one speaks instead — a wrong-accent reading beats silence.
     */
    fun speak(text: String, utteranceLocale: Locale? = null, onDone: () -> Unit = {}) {
        if (text.isBlank()) {
            onDone()
            return
        }
        val current = tts
        if (current == null || _state.value is TtsState.Initializing) {
            // Queue until the engine finishes initialising.
            pendingText = text
            pendingLocale = utteranceLocale
            pendingOnDone = onDone
            return
        }
        if (_state.value is TtsState.Error) return
        if (applyLocale(utteranceLocale ?: locale)) {
            // The default voice may be missing while this utterance's one is not, so
            // clear the prompt that says read-aloud is unusable — it plainly works.
            if (_state.value is TtsState.LanguageMissing) _state.value = TtsState.Idle
        } else if (currentLocale == null) {
            // No voice has ever loaded: nothing can be spoken. [state] already says
            // LanguageMissing, which is what surfaces the installer action.
            return
        }
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
        pendingLocale = null
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
