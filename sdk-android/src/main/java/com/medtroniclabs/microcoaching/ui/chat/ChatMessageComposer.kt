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

// Canned-response composition for ChatViewModel (refusal / grounding-fallback / L4
// fallback), as extensions in the same package.
/**
 * Serve a canned refusal message (the L1/L2/L4 refusal paths). Persists an
 * assistant ChatMessage with the refusal copy, stamps `meta.outcome` so the
 * downstream TTS layer can choose a distinctive voice, and emits one
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
 * Serve the card the model was given, verbatim, when a post-model check rejects the model's
 * answer (the groundedness floor or the output validator).
 *
 * The card already passed the gate, so nothing here re-judges it: the checks decide which
 * text the CHW sees, never which card. The card's linked quiz explanation is served when it
 * has one, since it is already answer-shaped; otherwise its body, clipped to a complete
 * sentence. A card with no text at all refuses as unsafe.
 */
internal suspend fun ChatViewModel.serveCardVerbatim(
    card: GroundingChunk,
    isBangla: Boolean,
    validatorReason: String?,
) {
    val attribution = resolveSourceAttribution(listOf(card))
    val explanation = if (card.hasExplanation()) resolveExplanation(card, isBangla) else null
    val hasBody = card.source == GroundingChunk.Source.CARD &&
        (!card.bodyBn.isNullOrBlank() || !card.bodyEn.isNullOrBlank())
    when {
        explanation != null -> serveFallback(
            bodyBn = explanation,
            groundedFrom = listOf(card.chunkId),
            validatorReason = validatorReason,
            fallbackKind = "fallback_quiz_explanation",
            sourceDocuments = attribution.docs,
            groundingModuleFamilyId = attribution.familyId,
            groundingModuleId = attribution.moduleId,
            startPage = attribution.startPage,
        )
        hasBody -> serveFallback(
            bodyBn = clipToCompleteSentence(resolveCardBody(card, isBangla)),
            groundedFrom = listOf(card.chunkId),
            validatorReason = validatorReason,
            fallbackKind = "fallback_card_body",
            sourceDocuments = attribution.docs,
            groundingModuleFamilyId = attribution.familyId,
            groundingModuleId = attribution.moduleId,
            startPage = attribution.startPage,
        )
        else -> serveRefusal(
            ChatRefusal.Unsafe,
            groundedFrom = listOf(card.chunkId),
            topScore = card.score,
            validatorReason = validatorReason,
        )
    }
}

/**
 * Serve clinician-authored module text as the chat reply (L4 fallback).
 * Used when the validator rejects the model's free-form answer but a retrieved
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

