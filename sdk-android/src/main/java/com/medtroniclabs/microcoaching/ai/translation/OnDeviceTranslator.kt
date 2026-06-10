package com.medtroniclabs.microcoaching.ai.translation

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lifecycle states of the on-device EN↔BN translation pack.
 *
 * Exposed via [OnDeviceTranslator.state] (and mirrored on
 * [com.medtroniclabs.microcoaching.MicroCoachingSDK.translationModelState])
 * so UI surfaces can render a download-progress chip without polling.
 */
sealed class TranslationModelState {
    object Unknown : TranslationModelState()
    object Downloading : TranslationModelState()
    object Ready : TranslationModelState()
    data class Failed(val reason: String) : TranslationModelState()
}

/**
 * On-device EN ↔ BN translation via ML Kit.
 *
 * Two [com.google.mlkit.nl.translate.Translator] clients share the same on-disk
 * language pack — one for EN→BN (post-translating LLM output for the CHW) and
 * one for BN→EN (pre-translating CHW input before sending to the English-dominant
 * Gemma model).
 *
 * The pack (~20 MB) downloads once and is cached. [ensureModelReady] should be
 * called at SDK init time so the pack is in place before the first card or chat
 * turn needs translation. [translateEnToBn] / [translateBnToEn] block-with-timeout
 * (default 8 s) on first use if the download is still in flight, then fall back
 * to passthrough — the caller's UI is expected to surface the [state] flow so
 * the CHW knows why text is rendered in English.
 *
 * Thread-safe: all public methods are suspend functions safe to call from any
 * dispatcher.
 */
class OnDeviceTranslator {

    private val enToBnClient = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.BENGALI)
            .build()
    )

    private val bnToEnClient = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.BENGALI)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    private val _state = MutableStateFlow<TranslationModelState>(TranslationModelState.Unknown)

    /** Observable lifecycle of the translation pack. */
    val state: StateFlow<TranslationModelState> = _state.asStateFlow()

    /** Convenience read for callers that want a snapshot. */
    val isModelReady: Boolean
        get() = _state.value is TranslationModelState.Ready

    /**
     * Downloads the translation pack if not already present, with exponential-backoff
     * retry on transient failures (3 attempts: immediate, +2 s, +6 s).
     *
     * Idempotent — concurrent callers all observe the final [state] without
     * triggering duplicate downloads (ML Kit's `downloadModelIfNeeded` is itself
     * idempotent).
     */
    suspend fun ensureModelReady() {
        if (_state.value is TranslationModelState.Ready) return
        if (_state.value is TranslationModelState.Downloading) return

        _state.value = TranslationModelState.Downloading
        val conditions = DownloadConditions.Builder().build()

        val backoffMs = listOf(0L, 2_000L, 6_000L)
        var lastError: String? = null
        for ((attempt, delayMs) in backoffMs.withIndex()) {
            if (delayMs > 0) delay(delayMs)
            try {
                // Both clients share the same on-disk pack, but ML Kit asks each
                // client to verify presence — calling on one is enough.
                enToBnClient.downloadModelIfNeeded(conditions).await()
                _state.value = TranslationModelState.Ready
                Log.i(TAG, "EN↔BN translation model ready (attempt ${attempt + 1})")
                return
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "Translation pack download attempt ${attempt + 1} failed: $lastError")
            }
        }
        _state.value = TranslationModelState.Failed(lastError ?: "unknown")
    }

    /**
     * Outcome of a translate call. [translated] is false when the call fell back
     * to **passthrough** (pack not ready, or the per-call translate threw) — in
     * which case [text] is the untranslated input. Callers that care about fidelity
     * (e.g. the chat pivot) use this to surface / measure passthrough rather than
     * silently shipping untranslated text. See [translateEnToBnResult] /
     * [translateBnToEnResult].
     */
    data class TranslationResult(val text: String, val translated: Boolean)

    /**
     * Translates [text] from English to Bengali.
     *
     * If the model pack is downloading, awaits up to [awaitMs] for it to land
     * before falling back to passthrough. If the pack has already failed, returns
     * [text] immediately.
     */
    suspend fun translateEnToBn(text: String, awaitMs: Long = DEFAULT_AWAIT_MS): String =
        translateInternal(text, awaitMs, enToBn = true).text

    /** As [translateEnToBn], but reports whether a passthrough occurred. */
    suspend fun translateEnToBnResult(text: String, awaitMs: Long = DEFAULT_AWAIT_MS): TranslationResult =
        translateInternal(text, awaitMs, enToBn = true)

    /**
     * Translates [text] from Bengali to English. Used to pre-translate CHW chat
     * input so the on-device English-dominant LLM can understand it.
     */
    suspend fun translateBnToEn(text: String, awaitMs: Long = DEFAULT_AWAIT_MS): String =
        translateInternal(text, awaitMs, enToBn = false).text

    /** As [translateBnToEn], but reports whether a passthrough occurred. */
    suspend fun translateBnToEnResult(text: String, awaitMs: Long = DEFAULT_AWAIT_MS): TranslationResult =
        translateInternal(text, awaitMs, enToBn = false)

    /** EN→BN parallel-friendly list translate. Failures fall through as passthrough per item. */
    suspend fun translateList(items: List<String>, awaitMs: Long = DEFAULT_AWAIT_MS): List<String> =
        items.map { translateEnToBn(it, awaitMs) }

    /**
     * Backward-compatible alias for [translateEnToBn]. Existing call-sites that
     * predate the bidirectional split (e.g.
     * [com.medtroniclabs.microcoaching.domain.decision.CoachingDecisionEngine]
     * and [com.medtroniclabs.microcoaching.ui.chat.ChatViewModel]) keep working
     * unchanged.
     */
    suspend fun translate(text: String): String = translateEnToBn(text)

    private suspend fun translateInternal(text: String, awaitMs: Long, enToBn: Boolean): TranslationResult {
        if (text.isBlank()) return TranslationResult(text, translated = false)

        val ready = when (val current = _state.value) {
            is TranslationModelState.Ready -> true
            is TranslationModelState.Failed -> {
                Log.d(TAG, "Translation pack failed (${current.reason}) — passthrough")
                false
            }
            TranslationModelState.Downloading, TranslationModelState.Unknown -> {
                // Wait for the download to settle, but don't block the caller forever.
                val settled = withTimeoutOrNull(awaitMs) {
                    state.first {
                        it is TranslationModelState.Ready || it is TranslationModelState.Failed
                    }
                }
                if (settled is TranslationModelState.Ready) {
                    true
                } else {
                    Log.w(TAG, "Translation pack not ready after ${awaitMs}ms — passthrough")
                    false
                }
            }
        }

        if (!ready) return TranslationResult(text, translated = false)

        return try {
            val client = if (enToBn) enToBnClient else bnToEnClient
            TranslationResult(client.translate(text).await(), translated = true)
        } catch (e: Exception) {
            Log.w(TAG, "Translation failed (${if (enToBn) "EN→BN" else "BN→EN"}): ${e.message}")
            TranslationResult(text, translated = false)
        }
    }

    private companion object {
        const val TAG = "OnDeviceTranslator"
        const val DEFAULT_AWAIT_MS = 8_000L
    }
}

/** Bridge between Google [com.google.android.gms.tasks.Task] and Kotlin coroutines. */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> cont.resume(result) }
        addOnFailureListener { e -> cont.resumeWithException(e) }
        addOnCanceledListener { cont.cancel() }
    }
