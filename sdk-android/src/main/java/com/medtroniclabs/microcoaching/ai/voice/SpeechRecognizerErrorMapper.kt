package com.medtroniclabs.microcoaching.ai.voice

import android.speech.SpeechRecognizer
import androidx.annotation.StringRes
import com.medtroniclabs.microcoaching.R

/**
 * Maps `SpeechRecognizer` error codes to user-facing string resources, offline-recovery
 * hints, and log labels. Extracted verbatim from [AndroidSpeechRecognizerEngine] so the
 * engine keeps only the recognizer lifecycle + restart state machine.
 *
 * Owns the API 31+/33+/34 error-code constants: they aren't compile-time constants on
 * `minSdk` 23, but the runtime can still surface the codes, so they're hard-coded here as
 * ints (and MUST match `android.speech.SpeechRecognizer` exactly — 8 is RECOGNIZER_BUSY,
 * 9 is INSUFFICIENT_PERMISSIONS, then:).
 */
internal object SpeechRecognizerErrorMapper {

    const val TOO_MANY_REQUESTS = 10       // ERROR_TOO_MANY_REQUESTS (API 31)
    const val SERVER_DISCONNECTED = 11     // ERROR_SERVER_DISCONNECTED (API 31)
    const val LANGUAGE_NOT_SUPPORTED = 12  // ERROR_LANGUAGE_NOT_SUPPORTED (API 33)
    const val LANGUAGE_UNAVAILABLE = 13    // ERROR_LANGUAGE_UNAVAILABLE (API 33)
    const val CANNOT_CHECK_SUPPORT = 14    // ERROR_CANNOT_CHECK_SUPPORT (API 33)
    const val CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15 // ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS (API 34)

    /** True for the platform "locale pack unavailable / not supported" codes. */
    fun isLanguageError(error: Int): Boolean =
        error == LANGUAGE_UNAVAILABLE || error == LANGUAGE_NOT_SUPPORTED

    /**
     * Map an error to the most specific user-facing message. The generic
     * `chat_voice_error_generic` is the last resort, only for codes we can't attribute.
     * [isOnline] is a lambda so the connectivity check is read only when the language
     * branch is reached (matching the original lazy evaluation).
     */
    @StringRes
    fun resIdFor(error: Int, isLanguageError: Boolean, isOnline: () -> Boolean): Int = when {
        error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.chat_voice_error_no_match
        error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            error == SpeechRecognizer.ERROR_SERVER -> R.string.chat_voice_error_network
        error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            R.string.chat_voice_permission_denied
        isLanguageError && !isOnline() -> R.string.chat_voice_error_offline_pack_missing
        isLanguageError -> R.string.chat_voice_error_language
        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
            error == TOO_MANY_REQUESTS -> R.string.chat_voice_error_busy
        error == SpeechRecognizer.ERROR_AUDIO -> R.string.chat_voice_error_audio
        error == SpeechRecognizer.ERROR_CLIENT ||
            error == SERVER_DISCONNECTED ||
            error == CANNOT_CHECK_SUPPORT ||
            error == CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> R.string.chat_voice_error_service
        else -> R.string.chat_voice_error_generic
    }

    /**
     * Network-class failures (no network / timeout / server / the generic client error the
     * cloud recognizer throws when it can't reach the backend) are recoverable on the offline
     * engine. Language errors are recoverable too — the sherpa engine has its own Bengali model
     * and doesn't care that the PLATFORM lacks the locale.
     */
    fun recoverableViaOffline(error: Int): Boolean = when (error) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_CLIENT,
        SERVER_DISCONNECTED,
        LANGUAGE_UNAVAILABLE,
        LANGUAGE_NOT_SUPPORTED -> true
        else -> false
    }

    /** Stringify a SpeechRecognizer error code for logging. */
    fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
        CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
        else -> "UNKNOWN"
    }
}
