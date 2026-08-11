package com.medtroniclabs.microcoaching.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [VoiceInputController] backed by Android's platform `SpeechRecognizer`.
 *
 * Uses `SpeechRecognizer.createSpeechRecognizer()` — **not** the strict
 * `createOnDeviceSpeechRecognizer()` — so the platform transparently routes to
 * Google's on-device pack when one is installed for the active language, and
 * falls back to the cloud recognizer otherwise. This makes Bengali work
 * online without any extra integration on our side, while English typically
 * recognises on-device on Google-services Android.
 *
 * **Continuous dictation:** the session does not auto-finalise on silence —
 * natural end-of-utterance events (`onResults` / `ERROR_NO_MATCH` /
 * `ERROR_SPEECH_TIMEOUT`) restart the recognizer transparently and the
 * accumulated transcript keeps growing. Only [stopListening] (mic re-tap by
 * the user) or a fatal error closes the session. The session is capped at
 * [MAX_SESSION_MS] and [MAX_RESTARTS] to avoid runaway loops.
 *
 * `EXTRA_PREFER_OFFLINE` is set as a hint only when `checkRecognitionSupport`
 * confirms an on-device pack exists for the requested locale (API 33+).
 *
 * **Lifecycle:**
 *   - The underlying `SpeechRecognizer` is recreated on every [startListening]
 *     call. Reusing one across sessions is supported in theory but flaky on
 *     low-end devices where the OS kills Google's recognition service between
 *     sessions.
 *   - One instance of this engine per SDK; survives chat-fragment recreation.
 *   - Call [destroy] from the SDK teardown path.
 *
 * Sherpa-onnx offline fallback for Bengali is a separate engine; see the
 * STT plan in `docs/v3/chat/sherpa.md`.
 */
internal class AndroidSpeechRecognizerEngine(
    private val appContext: Context,
) : VoiceInputController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val supportExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var currentListener: VoiceInputController.TranscriptionListener? = null

    private val isListening = AtomicBoolean(false)

    /** Cache the on-device-pack support per bcp47 code so we don't query every tap. */
    private val onDeviceCache = mutableMapOf<String, Boolean>()

    /**
     * Languages we've already asked the platform to download an on-device pack
     * for (API 33+ [SpeechRecognizer.triggerModelDownload]) — once per process
     * so repeated taps don't spam the Google download queue.
     */
    private val packDownloadRequested = mutableSetOf<String>()

    // ── Continuous-dictation session state (reset per startListening call) ────
    @Volatile private var userInitiatedStop = false
    @Volatile private var accumulatedText: String = ""
    @Volatile private var lastIntent: Intent? = null
    @Volatile private var sessionStartMs: Long = 0L
    @Volatile private var restartCount: Int = 0

    /**
     * One-shot retry flag: set to `true` when the recognizer reports
     * `LANGUAGE_UNAVAILABLE` and we had `EXTRA_PREFER_OFFLINE` set; the next
     * restart drops the offline hint and clears the cache entry so future
     * taps don't repeat the bad request. Reset on every fresh session.
     */
    @Volatile private var retryWithoutPreferOffline: Boolean = false

    /**
     * One-shot retry flag for `ERROR_SERVER_DISCONNECTED` — a transient drop of the
     * recognition service binding that commonly fires on the FIRST tap after a
     * connectivity change (e.g. offline→online), then succeeds on a fresh
     * recognizer. We recreate + retry once per session instead of making the CHW
     * tap again. Reset on every fresh session.
     */
    @Volatile private var retriedAfterDisconnect: Boolean = false

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun startListening(listener: VoiceInputController.TranscriptionListener) {
        if (!isAvailable()) {
            Log.w(TAG, "startListening: SpeechRecognizer.isRecognitionAvailable=false — aborting")
            listener.onError(
                withDebugDetail(
                    localized(R.string.chat_voice_error_engine),
                    "STT unavailable: SpeechRecognizer.isRecognitionAvailable=false",
                ),
            )
            return
        }
        runOnMain {
            // Reset per-session state.
            userInitiatedStop = false
            accumulatedText = ""
            sessionStartMs = SystemClock.elapsedRealtime()
            restartCount = 0
            retryWithoutPreferOffline = false
            retriedAfterDisconnect = false

            // Recreate the SpeechRecognizer on every session. Reusing a cached
            // instance is supported in theory but on low-end devices the
            // underlying Google recognition service often gets killed between
            // sessions (memory pressure) or lands in a stale state — subsequent
            // startListening() calls then fail with ERROR_CLIENT /
            // ERROR_RECOGNIZER_BUSY / ERROR_SERVER_DISCONNECTED.
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            isListening.set(false)
            currentListener = listener
            val sr = ensureRecognizer()
            val sdkLang = currentSdkLanguage()
            val language = sdkLang.bcp47
            val cached = onDeviceCache[language]
            // When offline, force the on-device pack even before the async support
            // probe has cached a result. Otherwise the FIRST offline tap (cached ==
            // null → preferOffline=false) builds a cloud-capable intent that fails
            // with ERROR_NETWORK, and only the SECOND tap (probe cached true) works.
            // If no on-device pack is actually installed, the LANGUAGE_UNAVAILABLE
            // recovery below drops the hint — so this is safe either way.
            val online = isOnline()
            val intent = buildIntent(language, preferOffline = cached == true || !online)
            lastIntent = intent

            Log.i(
                TAG,
                "startListening: sdkLang=$sdkLang bcp47=$language online=$online " +
                    "onDeviceCache[$language]=$cached " +
                    "preferOffline=${intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)} " +
                    "apiLevel=${Build.VERSION.SDK_INT}",
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && cached == null) {
                Log.d(TAG, "startListening: queueing async probeOnDeviceSupport for $language")
                probeOnDeviceSupport(language)
            }
            // Pack known-missing and we're online now (e.g. the first probe ran
            // while offline, so its download trigger was skipped) — make sure a
            // platform pack download is queued. Deduped per process; no-op < API 33.
            if (cached == false && online) {
                maybeTriggerPlatformPackDownload(language)
            }

            runCatching { sr.startListening(intent) }
                .onSuccess {
                    isListening.set(true)
                    Log.d(TAG, "sr.startListening dispatched OK")
                }
                .onFailure {
                    Log.w(TAG, "startListening threw: ${it.message}", it)
                    isListening.set(false)
                    currentListener = null
                    // Couldn't even dispatch to the cloud recognizer — treat as
                    // recoverable so the orchestrator can fall back to offline.
                    surfaceError(
                        listener,
                        withDebugDetail(
                            localized(R.string.chat_voice_error_generic),
                            "STT start failed: ${it.javaClass.simpleName}: ${it.message}",
                        ),
                        recoverableViaOffline = true,
                    )
                }
        }
    }

    override fun stopListening() {
        runOnMain {
            Log.i(
                TAG,
                "stopListening (user-initiated) — sessionMs=${SystemClock.elapsedRealtime() - sessionStartMs} " +
                    "restarts=$restartCount accumulatedLen=${accumulatedText.length}",
            )
            userInitiatedStop = true
            if (isListening.get()) {
                // Note: we do NOT clear isListening here — onResults / onError will.
                runCatching { recognizer?.stopListening() }
            } else {
                // We're between restarts. Finalise whatever we have.
                finaliseAccumulated()
            }
        }
    }

    /** Whether a recognition session is currently active. */
    fun isCurrentlyListening(): Boolean = isListening.get()

    /** Tear down the underlying recognizer. Safe to call multiple times. */
    fun destroy() {
        runOnMain {
            currentListener = null
            isListening.set(false)
            recognizer?.destroy()
            recognizer = null
        }
        supportExecutor.shutdownNow()
    }

    override fun release() = destroy()

    /**
     * Returns `true` if the current SDK language has an on-device pack installed.
     * `null` while the async check is still pending. API < 33: always `null`.
     */
    fun isOnDeviceForCurrentLanguage(): Boolean? = onDeviceCache[currentSdkLanguage().bcp47]

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun buildIntent(language: String, preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            // Belt-and-suspenders: some Google recognizer builds ignore
            // EXTRA_LANGUAGE and recognise in the *device's* default speech
            // language (often Bengali on these handsets), so an English app's
            // mic returns Bengali. Setting EXTRA_LANGUAGE_PREFERENCE too pins the
            // recognition language to the SDK language across those builds.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Loosen the silence timeouts so brief pauses inside a sentence
            // don't terminate the platform session and force a restart. These
            // are best-effort hints — many Google recognizers cap silently.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L)
            // Prefer on-device only when we've confirmed a pack exists for this
            // language; otherwise the recognizer should be free to use the cloud.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && preferOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        Log.d(TAG, "ensureRecognizer: creating fresh SpeechRecognizer instance")
        val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
        sr.setRecognitionListener(InternalListener())
        recognizer = sr
        return sr
    }

    private fun probeOnDeviceSupport(language: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val sr = recognizer ?: return
        val probeIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
        }
        runCatching {
            sr.checkRecognitionSupport(
                probeIntent,
                supportExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val lang2 = language.take(2).lowercase()
                        fun List<String>.hasLang() = any { it.lowercase().startsWith(lang2) }
                        val installed = support.installedOnDeviceLanguages.hasLang()
                        val downloadable = support.supportedOnDeviceLanguages.hasLang()
                        val pending = support.pendingOnDeviceLanguages.hasLang()
                        onDeviceCache[language] = installed
                        Log.i(
                            TAG,
                            "probeOnDeviceSupport[$language]: installed=" +
                                "${support.installedOnDeviceLanguages} downloadable=" +
                                "${support.supportedOnDeviceLanguages} pending=" +
                                "${support.pendingOnDeviceLanguages} → matched=$installed",
                        )
                        // The pack isn't installed but the platform CAN download it —
                        // ask it to, so offline dictation works from the next session
                        // without the user visiting Google-app settings.
                        if (!installed && !pending && downloadable) {
                            maybeTriggerPlatformPackDownload(language)
                        }
                    }

                    override fun onError(error: Int) {
                        onDeviceCache[language] = false
                        Log.w(TAG, "probeOnDeviceSupport[$language] error=$error (${SpeechRecognizerErrorMapper.errorName(error)})")
                    }
                },
            )
        }.onFailure { Log.w(TAG, "checkRecognitionSupport failed for $language: ${it.message}") }
    }

    /**
     * Ask the platform to download its on-device speech pack for [language]
     * (API 33+ [SpeechRecognizer.triggerModelDownload]). This is the missing
     * half of "make sure the language is downloaded before going offline": the
     * SDK manages its own sherpa Bengali model, but Google's platform packs were
     * never requested — only probed. Fire-and-forget (progress callbacks exist
     * only on API 34+); once per language per process, online only (the download
     * itself needs network). If it lands, the next offline tap recognises
     * on-device instead of failing with LANGUAGE_UNAVAILABLE.
     */
    private fun maybeTriggerPlatformPackDownload(language: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!isOnline()) return
        runOnMain {
            if (language in packDownloadRequested) return@runOnMain
            val sr = recognizer ?: return@runOnMain
            packDownloadRequested += language
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
            }
            runCatching { sr.triggerModelDownload(intent) }
                .onSuccess { Log.i(TAG, "triggerModelDownload($language) dispatched — platform pack queued") }
                .onFailure {
                    // Allow a later retry (e.g. next app open) if dispatch itself failed.
                    packDownloadRequested -= language
                    Log.w(TAG, "triggerModelDownload($language) failed: ${it.message}")
                }
        }
    }

    private fun currentSdkLanguage(): Language = runCatching {
        MicroCoachingSDK.getInstance().language
    }.getOrDefault(Language.ENGLISH)

    /** Best-effort connectivity snapshot; assumes online if the SDK isn't reachable. */
    private fun isOnline(): Boolean = runCatching {
        MicroCoachingSDK.getInstance().isNetworkAvailable()
    }.getOrDefault(true)

    private fun localized(resId: Int): String {
        val sdkLang = currentSdkLanguage()
        val ctx = SdkLocaleHelper.wrap(appContext, sdkLang)
        return ctx.getString(resId)
    }

    /**
     * Surface an error to [listener], passing the [recoverableViaOffline] hint when
     * the listener is an [SttErrorListener] (the orchestrator's wrapper). Plain
     * public listeners just get the 1-arg [VoiceInputController.TranscriptionListener.onError].
     */
    private fun surfaceError(
        listener: VoiceInputController.TranscriptionListener?,
        message: String,
        recoverableViaOffline: Boolean,
    ) {
        when (listener) {
            is SttErrorListener -> listener.onError(message, recoverableViaOffline)
            else -> listener?.onError(message)
        }
    }

    /**
     * TEMPORARY (QA field diagnostics): appends the raw STT error [detail] below
     * the localized [message] so a field screenshot reveals the true cause on
     * devices we can't debug directly. The localized message stays the primary
     * line; the detail is a small technical addendum. Set [DEBUG_STT_ERRORS] to
     * false to remove the addendum once the field issue is understood.
     */
    private fun withDebugDetail(message: String, detail: String): String =
        if (DEBUG_STT_ERRORS) "$message\n\n$detail" else message

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    /**
     * Restart the platform recognizer for the same session — used after natural
     * end-of-utterance events when the user has NOT tapped stop. Honours the
     * session-duration and restart-count caps to avoid runaway loops on
     * pathological devices.
     */
    private fun maybeRestart() {
        if (currentListener == null) {
            Log.d(TAG, "maybeRestart: no listener — session already finalised, skipping")
            return
        }
        val sessionMs = SystemClock.elapsedRealtime() - sessionStartMs
        if (sessionMs >= MAX_SESSION_MS) {
            Log.i(TAG, "maybeRestart: session exceeded $MAX_SESSION_MS ms — finalising")
            finaliseAccumulated()
            return
        }
        if (restartCount >= MAX_RESTARTS) {
            Log.w(TAG, "maybeRestart: restart cap $MAX_RESTARTS reached — finalising")
            finaliseAccumulated()
            return
        }
        val intent = lastIntent
        if (intent == null) {
            Log.w(TAG, "maybeRestart: no cached intent — finalising")
            finaliseAccumulated()
            return
        }
        restartCount += 1
        Log.d(TAG, "maybeRestart #$restartCount (sessionMs=$sessionMs accumulatedLen=${accumulatedText.length})")
        val sr = ensureRecognizer()
        runCatching { sr.startListening(intent) }
            .onSuccess { isListening.set(true) }
            .onFailure {
                Log.w(TAG, "maybeRestart: sr.startListening threw: ${it.message}", it)
                isListening.set(false)
                finaliseAccumulated()
            }
    }

    /**
     * Flush the accumulated transcript to the listener as a final result, then
     * clear the listener. Called when the user taps stop OR the session is
     * forcibly closed (timeout / restart-cap / fatal error).
     */
    private fun finaliseAccumulated() {
        val listener = currentListener ?: return
        val text = accumulatedText.trim()
        isListening.set(false)
        currentListener = null
        Log.i(TAG, "finaliseAccumulated: len=${text.length} restarts=$restartCount")
        listener.onResult(text)
    }

    private inner class InternalListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech (waiting for onResults / onError)")
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?: return
            if (text.isBlank()) return
            // Platform partials are scoped to the current sub-utterance; prepend
            // anything already accumulated from previous restarts so the input
            // field sees the full running transcript.
            val merged = if (accumulatedText.isBlank()) text
            else "${accumulatedText.trim()} ${text.trim()}"
            currentListener?.onPartial(merged)
        }

        override fun onResults(results: Bundle?) {
            isListening.set(false)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                accumulatedText = if (accumulatedText.isBlank()) text
                else "${accumulatedText.trim()} ${text.trim()}"
            }
            Log.d(
                TAG,
                "onResults: chunkLen=${text.length} accumulatedLen=${accumulatedText.length} " +
                    "userInitiatedStop=$userInitiatedStop",
            )
            if (userInitiatedStop) {
                finaliseAccumulated()
            } else {
                // Push the running transcript out as a partial so the input
                // field stays current, then restart the recognizer for the
                // next utterance.
                currentListener?.onPartial(accumulatedText)
                maybeRestart()
            }
        }

        override fun onError(error: Int) {
            isListening.set(false)
            val sessionMs = SystemClock.elapsedRealtime() - sessionStartMs
            Log.w(
                TAG,
                "SpeechRecognizer.onError code=$error (${SpeechRecognizerErrorMapper.errorName(error)}) " +
                    "sessionMs=$sessionMs restarts=$restartCount " +
                    "userInitiatedStop=$userInitiatedStop",
            )

            // LANGUAGE_UNAVAILABLE / LANGUAGE_NOT_SUPPORTED — surgical recovery:
            // clear the cache entry so future taps don't repeat the bad request,
            // and one-shot retry without EXTRA_PREFER_OFFLINE so the cloud
            // recognizer gets a chance.
            if (SpeechRecognizerErrorMapper.isLanguageError(error) &&
                !retryWithoutPreferOffline &&
                !userInitiatedStop
            ) {
                val lang = currentSdkLanguage().bcp47
                val wasOffline = lastIntent?.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false) == true
                Log.i(
                    TAG,
                    "Recovery: ${SpeechRecognizerErrorMapper.errorName(error)} for $lang — clearing onDeviceCache[$lang] " +
                        "(was=${onDeviceCache[lang]}); retryWithoutPreferOffline=$wasOffline",
                )
                onDeviceCache[lang] = false
                if (wasOffline) {
                    retryWithoutPreferOffline = true
                    lastIntent = buildIntent(lang, preferOffline = false)
                    maybeRestart()
                    return
                }
                // Wasn't using offline — nothing more we can do; fall through to error surfacing.
            }

            // Errors that can leave the underlying service in a broken state —
            // wipe the cached recognizer so the next startListening() builds a
            // fresh one even if the user re-taps within the same composition.
            if (error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_AUDIO ||
                error == SpeechRecognizerErrorMapper.SERVER_DISCONNECTED ||
                error == SpeechRecognizerErrorMapper.TOO_MANY_REQUESTS
            ) {
                runCatching { recognizer?.destroy() }
                recognizer = null
            }

            // ERROR_SERVER_DISCONNECTED is a transient drop of the recognition
            // service binding — it commonly fires on the FIRST tap after a
            // connectivity change (offline→online) even on a freshly-created
            // recognizer, then succeeds on the next attempt. The recognizer was
            // just destroyed above; recreate it and retry the session ONCE rather
            // than surfacing chat_voice_error_generic and forcing the CHW to tap
            // again. One-shot per session (guarded by [retriedAfterDisconnect]) so
            // a persistent disconnect still surfaces the error instead of looping.
            if (error == SpeechRecognizerErrorMapper.SERVER_DISCONNECTED && !retriedAfterDisconnect && !userInitiatedStop) {
                retriedAfterDisconnect = true
                Log.i(TAG, "ERROR_SERVER_DISCONNECTED — recreating recognizer and retrying once")
                maybeRestart()
                return
            }

            // Silence-class errors during continuous dictation are NOT fatal —
            // the user paused but hasn't tapped stop. Restart transparently.
            if ((error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                !userInitiatedStop
            ) {
                Log.d(TAG, "Silence-class error during continuous dictation — restarting")
                maybeRestart()
                return
            }

            // A terminal language error means the platform has no usable engine for
            // this locale right now. Two very different causes:
            //  - OFFLINE: the on-device pack simply isn't installed → tell the CHW
            //    the truthful, actionable thing (connect to internet; the pack
            //    auto-downloads), and queue the pack download for when network is
            //    back (no-op while offline; the probe path retries when online).
            //  - ONLINE: the recognizer genuinely doesn't support the locale
            //    (old/missing Google app, OEM recognizer) → say that. Still queue
            //    the pack download in case a newer Google app can fetch it.
            val isLanguageError = SpeechRecognizerErrorMapper.isLanguageError(error)
            if (isLanguageError) {
                maybeTriggerPlatformPackDownload(currentSdkLanguage().bcp47)
            }

            // Anything else is session-fatal. Map to the most specific true cause
            // we can — the generic "Voice input failed" is the LAST resort, only
            // for codes we can't attribute.
            val resId = SpeechRecognizerErrorMapper.resIdFor(error, isLanguageError) { isOnline() }
            // Network-class failures (no network / timeout / server / the generic
            // client error the cloud recognizer throws when it can't reach the
            // backend) are recoverable on the offline engine — flag them so the
            // orchestrator can retry offline instead of surfacing the error. This is
            // the authoritative "we're actually offline" signal that the racy
            // connectivity check missed on the first tap.
            // Language errors are recoverable too: the sherpa engine has its own
            // Bengali model and doesn't care that the PLATFORM lacks the locale —
            // for Bengali with the model on disk this turns "language unavailable"
            // into a working dictation. (English has no offline engine; the
            // orchestrator's offlineViable() check keeps it surfacing the message.)
            val recoverableViaOffline = SpeechRecognizerErrorMapper.recoverableViaOffline(error)
            // If we accumulated transcript before the error, prefer surfacing
            // it as a final result rather than throwing away the user's input.
            if (accumulatedText.isNotBlank() && userInitiatedStop) {
                finaliseAccumulated()
            } else {
                surfaceError(
                    currentListener,
                    withDebugDetail(localized(resId), "STT error: ${SpeechRecognizerErrorMapper.errorName(error)} (code $error)"),
                    recoverableViaOffline,
                )
                currentListener = null
            }
        }
    }

    companion object {
        private const val TAG = "AndroidSpeechRecognizer"

        /**
         * TEMPORARY: when true, the raw STT error (code name + number, or thrown
         * exception) is appended below the user-facing message so QA can screenshot
         * the true cause on devices we can't debug locally. Flip to false to hide
         * the technical addendum once the field issue is diagnosed.
         */
        private const val DEBUG_STT_ERRORS = true

        /** Hard cap on the wall-clock duration of a single continuous dictation session. */
        private const val MAX_SESSION_MS = 90_000L

        /** Hard cap on transparent platform restarts within one user-tap session. */
        private const val MAX_RESTARTS = 12
    }
}
