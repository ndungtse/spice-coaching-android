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

// Backend RAG answer path for ChatViewModel, kept out of the view model as an
// extension. Same package, so ChatViewModel call sites need no import.
/**
 * Backend RAG path — used when the device is online (any device class).
 * Sends [trimmed] to `POST /coaching/rag-query`, maps the response to a
 * [ChatMessage], persists it, and updates [_uiState]. No local scope-gate
 * or LLM; the backend handles retrieval and generation.
 *
 * **Language.** The backend answers in whichever `response_language` it is asked
 * for, so the question goes out as typed and the answer comes back ready to
 * display. Nothing is translated on this path — the on-device EN↔BN pack is only
 * needed by the offline pipeline now.
 *
 * @return `true` when the turn was **handled** here — a grounded answer was
 *   served, or the backend returned a 2xx with a deliberately blank answer (a
 *   content decision: shown as [R.string.chat_error_no_response_available]).
 *   Returns `false` on an **infrastructure** failure (network error / thrown
 *   exception, non-2xx, empty body) **without** setting an error, so the caller
 *   can fall back to the on-device pipeline — the device can still answer from
 *   the offline BM25 index. Connectivity is never the excuse.
 */
internal suspend fun ChatViewModel.handleBackendRagMessage(trimmed: String): Boolean {
    val responseLanguage = if (sdk.language == Language.BANGLA) "bn" else "en"

    Log.i(
        ChatViewModel.TRACE_TAG,
        "backend-rag → request lang=$responseLanguage q=\"${tracePreview(trimmed)}\"",
    )
    try {
        val response = sdk.apiService.ragQuery(
            RagQueryRequest(
                question = trimmed,
                responseLanguage = responseLanguage,
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

        // The URL and path ride along so the citation can open a document that is in
        // no synced catalogue, which the local URL store has no way to resolve.
        val sourceDocs = body.sourceDocuments.map { doc ->
            SourceDocumentRef(
                id = doc.sourceDocumentId,
                title = doc.title,
                originalFilename = doc.originalFilename,
                presignedUrl = doc.presignedUrl,
                storagePath = doc.storagePath,
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
        // payload_json.response — the response as received from the API.
        val responseJson = serializeChatResponse(body)
        val assistantMsg = ChatMessage(
            sessionId = session.sessionId,
            role = ChatRole.ASSISTANT,
            text = body.answer,
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
        // They arrive in the language we asked for, so both fields carry the same
        // text: `ChatViewModel.onSuggestionTap` reads whichever matches the app
        // language and falls back to the other, and either way that is the text the
        // backend gave us. When no usable follow-ups come back, fall back to the
        // curated suggestions rather than leaving stale chips on screen.
        val followUps = body.suggestedQuestions
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .map { SuggestedQuestion(question = it, banglaQuestion = it) }
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
            // No translation_passthrough on this path: the backend answers in the
            // requested language, so nothing is translated and there is no pivot to
            // report. The field stays meaningful for the on-device pipeline, which
            // still uses the EN↔BN pack. Events Modelling 1.4/1.5:
            // payload_json.response is the full RAG response object (JSON string).
            payloadJson = buildRefusalPayload(
                outcome = "served_grounded",
                topScore = null,
                chunkIds = body.citedModuleIds,
                validatorReason = null,
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
