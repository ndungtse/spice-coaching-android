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

// Canned-response composition for ChatViewModel (refusal / grounding-fallback / L4 fallback)
// — extracted verbatim as extensions (behaviour-preserving). Same package; call sites unchanged.
/**
 * Serve a canned refusal message (chat_plan.md §B4 L1/L2/L4 paths). Persists an
 * assistant ChatMessage with the refusal copy, stamps `meta.outcome` so the
 * downstream TTS layer (Phase 6) can choose a distinctive voice, and emits one
 * IT-help telemetry row with the refusal detail in `payload_json`.
 */
internal suspend fun ChatViewModel.serveRefusal(
    refusal: ChatRefusal,
    groundedFrom: List<String>,
    topScore: Float?,
    validatorReason: String? = null,
) {
    Log.i(
        ChatViewModel.TRACE_TAG,
        "OUTCOME=REFUSAL key=${refusal.outcomeKey} topScore=$topScore " +
            "groundedFrom=$groundedFrom reason=${validatorReason ?: "∅"}",
    )
    // Use the SDK-locale-wrapped context so the refusal copy follows
    // `MicroCoachingSDK.language` regardless of the host app's device locale —
    // SPICE running in English would otherwise resolve every refusal through
    // its own `Resources` and emit English text inside a Bangla-mode chat.
    val ctx = SdkLocaleHelper.wrap(
        getApplication<android.app.Application>(),
        sdk.language,
    )
    val message = refusal.message(ctx)
    // Response object (offline shape) as a JSON string — a refusal has no grounded
    // module, so citedModuleIds stays empty.
    val responseJson = serializeChatResponse(offlineChatResponse(message))
    val assistantMsg = ChatMessage(
        sessionId = session.sessionId,
        role = ChatRole.ASSISTANT,
        text = message,
        source = MessageSource.LOCAL_MODEL,
        meta = ChatMessageMeta(
            outcome = refusal.outcomeKey,
            groundedFrom = groundedFrom,
            inferenceMode = "edge",
            networkState = currentNetworkState(),
            validatorStatus = "fail",
            fallbackUsed = false,
            responseJson = responseJson,
            question = currentQuestion,
        ),
    ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }
    _uiState.update {
        (it as? ChatUiState.Ready)?.copy(
            messages = (it as ChatUiState.Ready).messages + assistantMsg,
            isGenerating = false,
            streamingText = "",
        ) ?: it
    }
    eventRecorder.recordDigitalHelpUsed(
        inferenceMode = "edge",
        validatorStatus = "fail",
        fallbackUsed = false,
        networkState = currentNetworkState(),
        payloadJson = buildRefusalPayload(
            outcome = refusal.outcomeKey,
            topScore = topScore,
            chunkIds = groundedFrom,
            validatorReason = validatorReason,
            response = responseJson,
        ),
    )
}

/**
 * Serve the BM25-selected clinician content when the model's own answer is
 * rejected by a post-stream gate (the groundedness floor or the L4 validator).
 * BM25 already surfaced relevant cards, so the CHW gets the authoritative
 * answer instead of an "I don't have this" refusal. Order matters for tone:
 *   1) a linked quiz EXPLANATION (concise, already answer-shaped) — far better
 *      than a long third-person card body; served in the CHW's language directly.
 *   2) else the retrieved CARD body, clipped to a complete sentence so it never
 *      ends mid-sentence.
 *   3) else (no usable text at all) an honest Unsafe refusal.
 * Shared by the L3c groundedness gate and the L4 validator.
 */
internal suspend fun ChatViewModel.serveGroundingFallbackOrRefuse(
    grounding: List<GroundingChunk>,
    isBangla: Boolean,
    validatorReason: String?,
    queryForSafety: String,
    clinicalTerms: Set<String>,
) {
    val fallbackHit = OffTopicGuard.bestFallbackHit(
        query = queryForSafety,
        hits = grounding,
        clinicalTerms = clinicalTerms,
        minScore = config.chatTuning.minFallbackServeScore,
    )
    if (fallbackHit == null) {
        Log.i(
            ChatViewModel.TRACE_TAG,
            "fallback-refusal: refusing instead of serving weak/irrelevant grounding " +
                "topScore=${grounding.firstOrNull()?.score} reason=${validatorReason ?: "∅"}",
        )
        serveRefusal(
            ChatRefusal.NoGround,
            groundedFrom = grounding.map { it.chunkId },
            topScore = grounding.firstOrNull()?.score,
            validatorReason = validatorReason,
        )
        return
    }
    val fbAttribution = resolveSourceAttribution(grounding)
    val explanationChunk = grounding.firstOrNull {
        it.chunkId == fallbackHit.chunkId && it.hasExplanation()
    }
    val cardFallback = grounding.firstOrNull {
        it.chunkId == fallbackHit.chunkId &&
            it.source == GroundingChunk.Source.CARD &&
            (!it.bodyBn.isNullOrBlank() || !it.bodyEn.isNullOrBlank())
    }
    when {
        explanationChunk != null -> serveFallback(
            bodyBn = resolveExplanation(explanationChunk, isBangla).orEmpty(),
            groundedFrom = listOf(explanationChunk.chunkId),
            validatorReason = validatorReason,
            fallbackKind = "fallback_quiz_explanation",
            sourceDocuments = fbAttribution.docs,
            groundingModuleFamilyId = fbAttribution.familyId,
            groundingModuleId = fbAttribution.moduleId,
            startPage = fbAttribution.startPage,
        )
        cardFallback != null -> serveFallback(
            bodyBn = clipToCompleteSentence(resolveCardBody(cardFallback, isBangla)),
            groundedFrom = listOf(cardFallback.chunkId),
            validatorReason = validatorReason,
            fallbackKind = "fallback_card_body",
            sourceDocuments = fbAttribution.docs,
            groundingModuleFamilyId = fbAttribution.familyId,
            groundingModuleId = fbAttribution.moduleId,
            startPage = fbAttribution.startPage,
        )
        else -> serveRefusal(
            ChatRefusal.Unsafe,
            groundedFrom = grounding.map { it.chunkId },
            topScore = grounding.firstOrNull()?.score,
            validatorReason = validatorReason,
        )
    }
}

/**
 * Serve clinician-authored module text as the chat reply (L4 fallback).
 * Used when the validator rejects Gemma's free-form answer but a retrieved
 * grounding chunk carries trustworthy source text. [fallbackKind] is the
 * `ChatMessageMeta.outcome` key — `fallback_quiz_explanation` for QUIZ
 * chunks, `fallback_card_body` for CARD chunks.
 */
internal suspend fun ChatViewModel.serveFallback(
    bodyBn: String,
    groundedFrom: List<String>,
    validatorReason: String?,
    fallbackKind: String = "fallback_quiz_explanation",
    sourceDocuments: List<SourceDocumentRef> = emptyList(),
    groundingModuleFamilyId: String? = null,
    groundingModuleId: String? = null,
    startPage: Int? = null,
) {
    // The LLM answer was rejected (or skipped on low-end) and we are serving
    // clinician-authored module text verbatim instead. Two identical questions
    // taking different branches — one served the LLM answer, one fell back here —
    // is itself a source of the "different answer each time" report.
    Log.i(
        ChatViewModel.TRACE_TAG,
        "OUTCOME=FALLBACK kind=$fallbackKind groundedFrom=$groundedFrom " +
            "reason=${validatorReason ?: "∅"} bodyLen=${bodyBn.length} " +
            "body=\"${tracePreview(bodyBn)}\"",
    )
    val responseJson = serializeChatResponse(offlineChatResponse(bodyBn, groundingModuleId))
    val assistantMsg = ChatMessage(
        sessionId = session.sessionId,
        role = ChatRole.ASSISTANT,
        text = bodyBn,
        source = MessageSource.LOCAL_MODEL,
        meta = ChatMessageMeta(
            outcome = fallbackKind,
            groundedFrom = groundedFrom,
            moduleId = groundingModuleId,
            inferenceMode = "edge",
            networkState = currentNetworkState(),
            validatorStatus = "fail",
            fallbackUsed = true,
            responseJson = responseJson,
            question = currentQuestion,
        ),
        sourceDocuments = sourceDocuments,
        groundingModuleFamilyId = groundingModuleFamilyId,
        startPage = startPage,
    ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }
    _uiState.update {
        (it as? ChatUiState.Ready)?.copy(
            messages = (it as ChatUiState.Ready).messages + assistantMsg,
            isGenerating = false,
            streamingText = "",
        ) ?: it
    }
    eventRecorder.recordDigitalHelpUsed(
        inferenceMode = "edge",
        validatorStatus = "fail",
        fallbackUsed = true,
        networkState = currentNetworkState(),
        // A clinician-authored module body IS the served response here, so
        // module_id is the module that formed it (Events-Modelling v1.2).
        moduleId = groundingModuleId,
        payloadJson = buildRefusalPayload(
            outcome = fallbackKind,
            topScore = null,
            chunkIds = groundedFrom,
            validatorReason = validatorReason,
            response = responseJson,
        ),
    )
}

