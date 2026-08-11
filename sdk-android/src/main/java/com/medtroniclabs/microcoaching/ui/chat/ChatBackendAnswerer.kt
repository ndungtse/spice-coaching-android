package com.medtroniclabs.microcoaching.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.ChatScopeStrictness
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.chat.ChatMessage
import com.medtroniclabs.microcoaching.ui.chat.ChatRole
import com.medtroniclabs.microcoaching.ui.chat.MessageSource
import com.medtroniclabs.microcoaching.data.repository.ChatRepositoryImpl
import com.medtroniclabs.microcoaching.ai.model.ModelState
import com.medtroniclabs.microcoaching.ai.inference.SharedInferenceRouter
import com.medtroniclabs.microcoaching.ai.retrieval.ChatRefusal
import com.medtroniclabs.microcoaching.ai.retrieval.GroundingChunk
import com.medtroniclabs.microcoaching.ai.retrieval.GroundingSelector
import com.medtroniclabs.microcoaching.ai.retrieval.ModuleKnowledgeIndex
import com.medtroniclabs.microcoaching.ai.retrieval.OffTopicGuard
import com.medtroniclabs.microcoaching.ai.retrieval.ScopeClassifier
import com.medtroniclabs.microcoaching.ai.voice.CoachingTtsHelper
import com.medtroniclabs.microcoaching.network.RagQueryRequest
import com.medtroniclabs.microcoaching.network.SourceDocumentRef
import com.medtroniclabs.microcoaching.ui.document.DocumentPreviewActivity
import com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder
import com.medtroniclabs.microcoaching.domain.validation.OutputValidator
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper
import com.medtroniclabs.microcoaching.R
import java.util.Locale
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Backend RAG answer path for ChatViewModel — extracted verbatim as an extension
// (behaviour-preserving). Same package, so ChatViewModel call sites are unchanged.
/**
 * Backend RAG path — used when the device is online (any device class).
 * Sends [trimmed] to `POST /coaching/rag-query`, maps the response to a
 * [ChatMessage], persists it, and updates [_uiState]. No local scope-gate
 * or LLM; the backend handles retrieval and generation.
 *
 * **Language.** The backend only accepts `response_language = "bn"`. In an
 * English app the question is translated EN→BN before sending and the answer
 * BN→EN before display (on-device ML Kit pack); in Bangla it passes through.
 * If the pack is unavailable in English mode we show
 * [R.string.chat_error_language_pack] and return `true` (handled — no BM25
 * fallback, since the cause is the pack, not connectivity).
 *
 * @return `true` when the turn was **handled** here — a grounded answer was
 *   served, the backend returned a 2xx with a deliberately blank answer (a
 *   content decision: shown as [R.string.chat_error_no_response_available]),
 *   or the EN↔BN pack was unavailable (shown as
 *   [R.string.chat_error_language_pack]). Returns `false` on an
 *   **infrastructure** failure (network error / thrown exception, non-2xx,
 *   empty body) **without** setting an error, so the caller can fall back to
 *   the on-device pipeline — the device can still answer from the offline
 *   BM25 index. Connectivity is never the excuse.
 */
internal suspend fun ChatViewModel.handleBackendRagMessage(trimmed: String): Boolean {
    val isBangla = sdk.language == Language.BANGLA

    // The backend only accepts response_language = "bn". When the app runs in
    // English we translate the question EN→BN before sending and translate the
    // answer BN→EN before display, via the on-device ML Kit pack. In Bangla the
    // text passes through untouched.
    val questionForBackend: String = if (isBangla) {
        trimmed
    } else {
        // Ensure the EN↔BN pack is present (downloads if missing). Without it we
        // can't speak to the bn-only backend, so surface an honest language-pack
        // error rather than sending English (which the backend rejects). This is
        // a distinct, accurate cause — not the generic "no response" message.
        sdk.translator.ensureModelReady()
        val inResult = sdk.translator.translateEnToBnResult(trimmed)
        if (!inResult.translated) {
            Log.w(ChatViewModel.TAG, "backend-rag: EN→BN pack unavailable — cannot query bn-only backend")
            Log.i(ChatViewModel.TRACE_TAG, "backend-rag ← LANG-PACK FAIL (EN→BN) state=${sdk.translationModelState.value}")
            eventRecorder.recordDigitalHelpUsed(
                inferenceMode = "online",
                validatorStatus = "fail",
                fallbackUsed = false,
                networkState = currentNetworkState(),
                payloadJson = buildRefusalPayload(
                    outcome = "language_pack_unavailable",
                    topScore = null,
                    chunkIds = emptyList(),
                    validatorReason = "en_to_bn_passthrough",
                    translationPassthrough = true,
                ),
            )
            _uiState.update {
                (it as? ChatUiState.Ready)?.copy(
                    isGenerating = false,
                    error = localizedString(R.string.chat_error_language_pack),
                ) ?: it
            }
            return true
        }
        inResult.text
    }

    Log.i(
        ChatViewModel.TRACE_TAG,
        "backend-rag → request lang=bn appLang=${if (isBangla) "bn" else "en"} " +
            "q=\"${tracePreview(questionForBackend)}\"",
    )
    try {
        val response = sdk.apiService.ragQuery(
            RagQueryRequest(
                question = questionForBackend,
                responseLanguage = "bn",
            ),
        )
        val body = response.body()
        // Infrastructure failure (non-2xx, empty body) — NOT a content
        // decision. Return false WITHOUT setting an error so sendMessage falls
        // back to on-device retrieval instead of dead-ending.
        if (!response.isSuccessful || body == null) {
            Log.w(ChatViewModel.TAG, "handleBackendRagMessage: non-success/empty body — HTTP ${response.code()} → on-device fallback")
            Log.i(
                ChatViewModel.TRACE_TAG,
                "backend-rag ← FAIL http=${response.code()} success=${response.isSuccessful} " +
                    "bodyNull=${body == null} → fallback",
            )
            return false
        }
        // 2xx with a deliberately blank answer: the backend retrieved nothing
        // groundable and chose to say nothing. Final — show the message and do
        // NOT fall back; a local BM25 guess would undercut that decision.
        if (body.answer.isBlank()) {
            Log.i(ChatViewModel.TRACE_TAG, "backend-rag ← 2xx blank answer — final (no fallback)")
            _uiState.update {
                (it as? ChatUiState.Ready)?.copy(
                    isGenerating = false,
                    error = localizedString(R.string.chat_error_no_response_available),
                ) ?: it
            }
            return true
        }

        // The backend re-embeds + retrieves on every call. Logging the retrieved
        // set (module + cosine_distance) and the cited ids across identical
        // questions tells you whether inconsistency is a *retrieval* problem
        // (different modules surface each time) or a *generation* problem (same
        // modules, different answer → server LLM sampling).
        Log.i(
            ChatViewModel.TRACE_TAG,
            "backend-rag ← HTTP ${response.code()} model=${body.model} " +
                "answerLen=${body.answer.length} citedModuleIds=${body.citedModuleIds} " +
                "answer=\"${tracePreview(body.answer)}\"",
        )
        body.retrievedModules.forEachIndexed { i, m ->
            Log.i(
                ChatViewModel.TRACE_TAG,
                "  retrieved[$i] module=${m.moduleId} cosineDist=${m.cosineDistance} " +
                    "domain=${m.domain} title=\"${tracePreview(m.titleEn ?: m.titleBn, 60)}\"",
            )
        }

        // English app: translate the bn answer back to English for display.
        // If translation fails (bn→en pack unavailable), we must NOT show raw
        // Bangla to an English user — surface the localized English "try again"
        // error instead and stop here (final; no on-device fallback, which could
        // also pass through Bangla). This overrides the older "an answer beats
        // none" passthrough for the English path.
        val answerForUser = if (isBangla) {
            body.answer
        } else {
            val outResult = sdk.translator.translateBnToEnResult(body.answer)
            if (!outResult.translated) {
                Log.d(
                    ChatViewModel.TRACE_TAG,
                    "backend-rag: BN→EN answer translation failed — showing EN error, not Bangla " +
                        "(answerLen=${body.answer.length})",
                )
                _uiState.update {
                    (it as? ChatUiState.Ready)?.copy(
                        isGenerating = false,
                        error = localizedString(R.string.chat_error_no_response_available),
                    ) ?: it
                }
                return true
            }
            outResult.text
        }

        val sourceDocs = body.sourceDocuments.map { doc ->
            SourceDocumentRef(
                id = doc.sourceDocumentId,
                title = doc.title,
                originalFilename = doc.originalFilename,
            )
        }

        // First positive page from source_pages, then from page_numbers fallback.
        val startPage = body.sourceDocuments.firstOrNull()?.let { doc ->
            doc.sourcePages.firstOrNull { it.pageNumber > 0 }?.pageNumber
                ?: doc.pageNumbers.firstOrNull { it > 0 }
        }

        // cited_module_ids are version UUIDs — look up the family UUID in local DB for
        // the chip-label fallback. Null if the module hasn't been synced yet.
        val familyId = body.citedModuleIds.firstOrNull()?.let { versionId ->
            runCatching { sdk.database.moduleDao().getById(versionId)?.moduleFamilyId }.getOrNull()
        }

        // The exact RAG response object, serialized as a JSON string for
        // payload_json.response (the `answer` inside stays the backend's bn text —
        // this captures the response as received from the API).
        val responseJson = serializeChatResponse(body)
        val assistantMsg = ChatMessage(
            sessionId = session.sessionId,
            role = ChatRole.ASSISTANT,
            text = answerForUser,
            source = MessageSource.RAG_API,
            meta = ChatMessageMeta(
                outcome = "served_grounded",
                groundedFrom = body.citedModuleIds,
                moduleId = body.citedModuleIds.firstOrNull(),
                inferenceMode = "online",
                networkState = currentNetworkState(),
                validatorStatus = "pass",
                fallbackUsed = false,
                responseJson = responseJson,
                question = currentQuestion,
            ),
            sourceDocuments = sourceDocs,
            groundingModuleFamilyId = familyId,
            startPage = startPage,
        ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }

        _uiState.update {
            (it as? ChatUiState.Ready)?.copy(
                messages = (it as ChatUiState.Ready).messages + assistantMsg,
                isGenerating = false,
                streamingText = "",
            ) ?: it
        }

        // Refresh the suggestion chips with the backend's contextual follow-ups.
        // The backend always answers in `response_language = "bn"`, so the chips
        // arrive in Bangla. For the English app we translate each to English and
        // DROP any that fail — an English user must never see Bengali chip text.
        // When the backend returns no usable follow-ups (none sent, or all dropped
        // because translation was unavailable), fall back to the curated hardcoded
        // suggestions (EN+BN) rather than leaving stale chips on screen.
        val followUps = body.suggestedQuestions
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .mapNotNull { bn ->
                if (isBangla) {
                    SuggestedQuestion(question = bn, banglaQuestion = bn)
                } else {
                    val out = runCatching { sdk.translator.translateBnToEnResult(bn) }.getOrNull()
                    if (out?.translated == true && out.text.isNotBlank()) {
                        SuggestedQuestion(question = out.text, banglaQuestion = bn)
                    } else {
                        null // never surface untranslated Bangla to an English user
                    }
                }
            }
        val nextSuggestions = followUps.ifEmpty { loadSuggestions() }
        _uiState.update {
            (it as? ChatUiState.Ready)?.copy(suggestedQuestions = nextSuggestions) ?: it
        }

        eventRecorder.recordDigitalHelpUsed(
            inferenceMode = "online",
            validatorStatus = "pass",
            fallbackUsed = false,
            networkState = currentNetworkState(),
            // The module that formed the response is
            // the top cited module version straight from the RAG response.
            moduleId = body.citedModuleIds.firstOrNull(),
            // Reaching here means the answer was served in the user's language:
            // bn for the Bangla app, successfully translated for the English app
            // (a failed BN→EN translation returns early above with an EN error,
            // never a Bangla passthrough). So there is no passthrough to record.
            // Events Modelling 1.4/1.5: payload_json.response is the full RAG
            // response object (JSON string), emitted for both languages.
            // translation_passthrough stays English-only.
            payloadJson = buildRefusalPayload(
                outcome = "served_grounded",
                topScore = null,
                chunkIds = body.citedModuleIds,
                validatorReason = null,
                translationPassthrough = if (isBangla) null else false,
                response = responseJson,
            ),
        )
        return true
    } catch (e: Exception) {
        // Network drop mid-request, timeout, deserialization — infrastructure,
        // not content. Don't set an error; fall back to on-device retrieval.
        Log.w(ChatViewModel.TAG, "handleBackendRagMessage failed: ${e.message}", e)
        Log.i(ChatViewModel.TRACE_TAG, "backend-rag ← EXCEPTION ${e.javaClass.simpleName} → on-device fallback")
        return false
    }
}
