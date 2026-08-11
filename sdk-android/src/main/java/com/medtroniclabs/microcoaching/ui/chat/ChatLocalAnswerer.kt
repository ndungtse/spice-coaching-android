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

// On-device answer paths for ChatViewModel — extracted verbatim as extension functions
// (behaviour-preserving). Same package, so ChatViewModel call sites are unchanged.
/**
 * On-device Gemma path — used when offline on a capable (≥ 3 GB RAM) device.
 * Runs the full L0→L5 pipeline: deny-list, scope gate, BM25 retrieval,
 * Gemma generation, L3/L4 validators, and BN↔EN translation round-trip.
 *
 * [currentState] is the [ChatUiState.Ready] snapshot captured at the start of
 * [sendMessage] (before the user message was appended) so the prompt history
 * excludes the current turn — the current message is passed separately to
 * [ChatSession.buildPrompt].
 */
internal suspend fun ChatViewModel.handleLocalGemmaMessage(
    trimmed: String,
    moduleFamilyId: String?,
    currentState: ChatUiState.Ready,
) {
    // On-device Gemma. When the model isn't loaded — e.g. an always-online
    // device that never downloaded it, now falling back from a failed backend
    // call — degrade to the BM25-only path rather than erroring. Clinician-
    // authored content is still served from the offline index.
    val llm = inferenceRouter.activeService ?: run {
        Log.i(ChatViewModel.TRACE_TAG, "Gemma model unavailable → degrading to BM25-only")
        handleLowEndMessage(trimmed)
        return
    }

    // The on-device model is English-dominant, so when the SDK is configured for
    // Bangla we pre-translate the user's question to English before prompting (the
    // response is post-translated below). This keeps the UI bubble showing the
    // original Bangla input but lets the model understand the question.
    // `englishCurrent` is the LLM-facing question in both modes; Bengali retrieval
    // uses a separate `banglaQuery` computed further down.
    // Track BN↔EN passthrough across both pivots for F5 telemetry.
    var translationPassthrough = false
    val englishCurrent = if (sdk.language == Language.BANGLA) {
        val result = sdk.translator.translateBnToEnResult(trimmed)
        if (!result.translated) {
            translationPassthrough = true
            Log.w(ChatViewModel.TAG, "BN→EN input passthrough — untranslated Bangla sent to the LLM")
        }
        // translated=false means MLKit fell back to passthrough (pack not ready /
        // translate threw) → the LLM receives raw Bangla. altered=true means the
        // text actually changed, i.e. the model saw something different from what
        // the CHW typed — inspect `out` when an answer looks off-topic.
        Log.i(
            ChatViewModel.TRACE_TAG,
            "BN→EN translate: translated=${result.translated} altered=${result.text != trimmed} " +
                "in=\"${tracePreview(trimmed)}\" out=\"${tracePreview(result.text)}\"",
        )
        result.text
    } else {
        Log.i(
            ChatViewModel.TRACE_TAG,
            "BN→EN translate: SKIPPED (SDK language=${sdk.language}) — English question goes to the " +
                "LLM verbatim; retrieval translates it to BN separately",
        )
        trimmed
    }

    // L0 — Hard deny-list. Catches obvious out-of-scope topics (coding,
    // sports, weather, entertainment, etc.) before any LLM call. Applies
    // in BOTH Strict and ExtendedClinical modes because the 1B model has
    // proven unreliable at refusing these on its own even with a tight
    // open-scope prompt. Cheap (substring match against ~50 terms).
    val scopeClassifier = ScopeClassifier.buildFrom(sdk.morningModules.value)
    if (scopeClassifier.isOutOfScope(trimmed) || scopeClassifier.isOutOfScope(englishCurrent)) {
        Log.d(ChatViewModel.TAG, "L0 deny-list: hard out-of-scope match — refusing without LLM call")
        serveRefusal(ChatRefusal.Scope, groundedFrom = emptyList(), topScore = null)
        return
    }

    // L1 — Scope allow-list (chat_plan.md §B4). Advisory only: a keyword miss no
    // longer hard-refuses before retrieval — that pre-search gate caused
    // false refusals on legitimate clinical questions the gazetteer hadn't seen.
    // The real backstops are L2 retrieval (no grounding → honest refusal below)
    // and the OffTopicGuard clinical-overlap check. L0 (deny-list) still blocks
    // obvious out-of-scope topics hard, before any of this.
    val l1InScope = scopeClassifier.isInScope(trimmed) || scopeClassifier.isInScope(englishCurrent)
    if (!l1InScope) {
        Log.d(ChatViewModel.TAG, "L1 advisory: scope keyword miss — deferring to retrieval + OffTopicGuard")
    }

    // L2 — Retrieval. Module content is Bengali-first, so retrieval always anchors
    // on the BN index. In Bangla mode the native query is the typed text; in English
    // mode it's the EN→BN translation (with the original English used against the EN
    // index for any English content). English mode requires the EN↔BN pack — without
    // it we can't reach the bn-only content, so surface the language-pack error and
    // stop, mirroring the online RAG path. BM25 scores are not calibrated across
    // languages, so the selector merges the BN and EN hit lists and picks the
    // strongest card from the combined set.
    val banglaQuery: String? =
        if (sdk.language == Language.BANGLA) null
        else banglaRetrievalQueryOrError(trimmed) ?: return
    val knowledgeIndex = sdk.chatKnowledgeIndex.value
    val nativeLang = ModuleKnowledgeIndex.Lang.BN
    val nativeQuery = banglaQuery ?: trimmed
    val crossEnglishQuery = if (sdk.language == Language.BANGLA) englishCurrent else trimmed
    val bm25Threshold = config.chatTuning.bm25ScoreThreshold
    val selection = GroundingSelector.select(
        nativeQuery = nativeQuery,
        englishQuery = crossEnglishQuery,
        nativeLanguage = nativeLang,
        index = knowledgeIndex,
        k = ChatViewModel.GROUNDING_K,
        scoreThreshold = bm25Threshold,
    )
    val nativeHits = selection.nativeHits
    val translatedHits = selection.englishHits
    val nativeTop = nativeHits.firstOrNull()?.score ?: 0f
    val translatedTop = translatedHits.firstOrNull()?.score ?: 0f
    val grounding = selection.hits

    // BM25 is deterministic for a given query+corpus, so identical questions
    // should produce identical candidate sets here. If the served answers differ
    // anyway, the divergence is downstream (LLM sampling / validator branch), not
    // retrieval. Watch for an on-topic question grounding to an off-topic module
    // (e.g. a "low BP / hypotension" question matching a "hypertension" card — the
    // tokens overlap but the clinical meaning is opposite).
    Log.i(
        ChatViewModel.TRACE_TAG,
        "BM25 native[$nativeLang] hits=${nativeHits.size} topScore=%.2f".format(Locale.US, nativeTop),
    )
    nativeHits.forEachIndexed { i, h -> Log.i(ChatViewModel.TRACE_TAG, traceChunk("  native", i, h)) }
    if (translatedHits.isNotEmpty()) {
        Log.i(
            ChatViewModel.TRACE_TAG,
            "BM25 translated[EN] hits=${translatedHits.size} topScore=%.2f".format(Locale.US, translatedTop),
        )
        translatedHits.forEachIndexed { i, h -> Log.i(ChatViewModel.TRACE_TAG, traceChunk("  translated", i, h)) }
    }
    Log.i(ChatViewModel.TRACE_TAG, "BM25 grounding chosen=${selection.chosenLabel} size=${grounding.size}")

    // Phase-0 garbage guard. Refuses only when the top hit shares ZERO clinical
    // tokens with the query — the "BM25 latched onto a stop-word" failure that
    // the prior synonym-map bug also enabled (e.g. "low BP 90/60" returning a
    // diarrhoea card). The tuned refusal floor (Phase 2) replaces this once the
    // benchmark gives us in- vs out-of-corpus score distributions to calibrate.
    val guardQuery = when {
        banglaQuery != null -> "$trimmed $banglaQuery"          // English mode: EN typed + BN translation
        englishCurrent != trimmed -> "$trimmed $englishCurrent" // Bangla mode: BN typed + EN translation
        else -> trimmed
    }
    val guardPrimary = OffTopicGuard.bestMatchingHit(
        query = guardQuery,
        hits = grounding,
        clinicalTerms = scopeClassifier.scopeTerms(),
    )
    if (OffTopicGuard.isClearlyUnanswerable(
            query = guardQuery,
            hits = grounding,
            clinicalTerms = scopeClassifier.scopeTerms(),
        )
    ) {
        Log.i(ChatViewModel.TRACE_TAG, "Phase-0 garbage guard: zero clinical-token overlap across top hits — refusing")
        serveRefusal(
            ChatRefusal.NoGround,
            groundedFrom = emptyList(),
            topScore = grounding.firstOrNull()?.score,
        )
        return
    }

    // Routing — honest-refusal policy: we never let the 1B model answer
    // ungrounded clinical content. No grounding → honest refusal. With grounding
    // present the model answers from it (and may answer the part it covers — see
    // the hardened prompt). The open-scope general-knowledge path was removed.
    if (grounding.isEmpty()) {
        serveRefusal(ChatRefusal.NoGround, groundedFrom = emptyList(), topScore = null)
        return
    }
    val promptGrounding = if (guardPrimary == null) {
        grounding
    } else {
        listOf(guardPrimary) + grounding.filter { it.chunkId != guardPrimary.chunkId }
    }
    val promptMode = PromptMode.Grounded
    Log.d(ChatViewModel.TAG, "promptMode=$promptMode, grounding=${promptGrounding.size} chunks, topScore=${promptGrounding.firstOrNull()?.score}")

    // Build prompt — pass previous messages only; currentMessage is appended by buildPrompt.
    // Force the English system-prompt variant when round-tripping so all model-facing
    // instructions are in the language the model handles best.
    val promptLanguage = if (sdk.language == Language.BANGLA) "en-US" else sdk.language.bcp47
    val scopeTermsForPrompt = if (promptMode == PromptMode.OpenScope) {
        (ChatViewModel.CLINICAL_SCOPE_FLOOR + scopeClassifier.scopeTerms()).distinct()
    } else emptyList()
    // History is deliberately NOT replayed to the model — every turn is
    // independent. Verified 2026-06-11: with prior exchanges in the prompt,
    // the model imitates its own earlier free-form answers (which were
    // grounded on *different* references) and answers from pre-training
    // instead of the current reference block — the breastfeeding turn scored
    // groundedness 0.14 with history vs 0.50 without, identical retrieval.
    // The conversation stays visible in the UI; this only affects model
    // context. When follow-up support is needed, re-introduce history as a
    // standalone-question rewrite (use the last topic to rewrite the query
    // BEFORE retrieval) rather than verbatim turn replay.
    val prompt = session.buildPrompt(
        currentMessage = englishCurrent,
        history = emptyList(),
        language = promptLanguage,
        grounding = promptGrounding,
        mode = promptMode,
        scopeTerms = scopeTermsForPrompt,
    )
    Log.d(ChatViewModel.TAG, "Prompt: $prompt")

    // OTel span
    val modelName = config.modelPath.substringAfterLast("/").ifBlank { "gemma.task" }
    val engineName = "mediapipe"
    val inferenceSpan = telemetry.startInferenceStream(
        modelName = modelName,
        engineName = engineName,
        sessionId = session.sessionId,
    )

    val startMs = System.currentTimeMillis()
    val responseBuilder = StringBuilder()

    // Guard against endInferenceStream being called twice: once from .catch (on error)
    // and once from the success path below. The span must be ended exactly once.
    var inferenceSpanEnded = false

    val isBangla = sdk.language == Language.BANGLA

    llm.generateResponseStream(prompt)
        .catch { cause ->
            val errMsg = cause.message ?: "Generation failed"
            inferenceSpanEnded = true
            telemetry.endInferenceStream(
                span = inferenceSpan,
                estimatedInputTokens = (prompt.length / 4).toLong(),
                estimatedOutputTokens = (responseBuilder.length / 4).toLong(),
                latencyMs = System.currentTimeMillis() - startMs,
                success = false,
                errorMessage = errMsg,
            )
            // IT-help telemetry — inference threw. validator_status is left null
            // because no output reached the validator. payload_json carries the
            // error message for debug aggregation.
            eventRecorder.recordDigitalHelpUsed(
                inferenceMode = "edge",
                validatorStatus = null,
                fallbackUsed = false,
                networkState = currentNetworkState(),
                payloadJson = buildJsonObject {
                    currentQuestion?.takeIf { it.isNotBlank() }?.let { put("question", it) }
                    put("error", errMsg)
                }.toString(),
            )
            _uiState.update {
                (it as? ChatUiState.Ready)?.copy(isGenerating = false, error = errMsg) ?: it
            }
        }
        .takeWhile {
            // Stream cap — every chat mode mandates a 2–4 sentence answer. A
            // stream blowing far past that is the model ignoring the prompt
            // and free-styling from pre-training (verified 2026-06-11: a 28 s,
            // 1.3 k-char low-BP essay that the groundedness gate then refused
            // anyway). Cancelling early reaches the same outcome in a fraction
            // of the latency; the partial text still runs the normal gates.
            val streamCap = config.chatTuning.streamCapChars
            val withinCap = responseBuilder.length < streamCap
            if (!withinCap) {
                Log.i(
                    ChatViewModel.TRACE_TAG,
                    "stream-cap: aborted generation at ${responseBuilder.length} chars " +
                        "(cap=$streamCap — model ignored the 2–4 sentence rule)",
                )
            }
            withinCap
        }
        .collect { token ->
            responseBuilder.append(token)
            // Tokens are buffered, never streamed raw to the UI. The raw
            // English still has to pass the validation gates (groundedness,
            // question-echo, L4 block-list), any of which can replace it with
            // a refusal or card fallback — streaming it live means the CHW
            // watches an answer appear and then vanish (verified UX failure).
            // StreamingBubble shows "●●●" while we collect; the validated
            // text is typewritten afterwards, in every language.
        }

    val latencyMs = System.currentTimeMillis() - startMs
    // Strip <end_of_turn> and anything after it — Gemma appends it after its response.
    val untrimmedResponse = responseBuilder.toString()
        .substringBefore("<end_of_turn>")
        .trim()

    // The model's raw English output, pre-validation/translation.
    // sawEndOfTurn=false means generation stopped because the SESSION token
    // window (`maxInferenceTokens` = input + output, see MicroCoachingConfig)
    // was exhausted, not because the model finished — the reply is cut
    // mid-sentence. We salvage it by trimming back to the last complete
    // sentence ('.', '!', '?', or the Bangla danda '।') so the CHW never
    // sees a dangling fragment like "Pain can be alleviated by".
    val sawEndOfTurn = responseBuilder.contains("<end_of_turn>")
    val rawResponse =
        if (sawEndOfTurn) untrimmedResponse else ChatViewModel.trimToCompleteSentence(untrimmedResponse)
    Log.i(
        ChatViewModel.TRACE_TAG,
        "LLM raw: len=${untrimmedResponse.length} latencyMs=$latencyMs sawEndOfTurn=$sawEndOfTurn " +
            "temp=${config.inferenceTemperature} maxTokens=${config.maxInferenceTokens} " +
            "text=\"${tracePreview(untrimmedResponse, 220)}\"",
    )
    if (rawResponse.length != untrimmedResponse.length) {
        Log.i(
            ChatViewModel.TRACE_TAG,
            "truncation-trim: dropped ${untrimmedResponse.length - rawResponse.length} trailing chars " +
                "(window exhausted mid-sentence) kept=\"${tracePreview(rawResponse, 120)}\"",
        )
    }

    // L3 — Intercept the REFUSE_NO_GROUND sentinel before it reaches the user.
    // Treat the same as L2 (no grounding) but emit a distinct telemetry signal.
    if (outputValidator.isNoGroundSentinel(rawResponse)) {
        serveRefusal(
            ChatRefusal.NoGround,
            groundedFrom = grounding.map { it.chunkId },
            topScore = grounding.firstOrNull()?.score,
        )
        return
    }

    // L3b — Open-scope sentinel: the LLM judged the question off-topic. Serve
    // the same canned scope-refusal copy used by L1 in Strict mode.
    if (promptMode == PromptMode.OpenScope && outputValidator.isOutOfScopeSentinel(rawResponse)) {
        serveRefusal(ChatRefusal.Scope, groundedFrom = emptyList(), topScore = null)
        return
    }

    // L3c — Groundedness gate (grounded mode only). The [[REFUSE_NO_GROUND]]
    // sentinel relies on the model NOTICING the references don't cover the
    // question; when the references are merely adjacent (newborn-warmth cards
    // for a breast-engorgement question — the verified failure) a 1B model
    // answers fluently from pre-training instead. Reference vocabulary
    // survives honest paraphrase, so a near-zero content-word overlap means
    // the answer did not come from the references. Refuse rather than serve
    // confident pre-training content as if it were clinician-reviewed. Score
    // is traced on every grounded turn so the floor can be tuned from logs.
    if (promptMode == PromptMode.Grounded && rawResponse.isNotBlank()) {
        val tuning = config.chatTuning
        val topScore = promptGrounding.firstOrNull()?.score
        val groundedness = outputValidator.groundednessScore(rawResponse, promptGrounding)
        // Two-tier groundedness gate — always on, leniency scaled by retrieval
        // confidence. Reference vocabulary survives honest paraphrase, so a
        // near-zero content-word overlap means the answer did not come from the
        // references (the model free-styled from pre-training). When BM25 found a
        // strong match (top score ≥ strongRetrievalScore) we trust the right
        // references are present and apply the lenient floor so a paraphrase that
        // doesn't literally match still serves; a weak match uses the stricter
        // floor. Both floors are tunable via ChatTuning. Score is traced on every
        // grounded turn so the floors can be tuned from logs.
        // PHASE3_RETIRE: delete score-based bypass when OffTopicGuard is out-of-corpus-only.
        val strongRetrieval = (topScore ?: 0f) >= tuning.strongRetrievalScore
        val floor =
            if (strongRetrieval) tuning.strongRetrievalGroundednessFloor
            else tuning.groundednessFloor
        Log.i(
            ChatViewModel.TRACE_TAG,
            "groundedness=%.2f floor=%.2f strongRetrieval=%b topScore=%.2f grounded=%s".format(
                Locale.US, groundedness, floor, strongRetrieval,
                topScore ?: 0f, promptGrounding.map { it.chunkId },
            ),
        )
        if (groundedness < floor) {
            // The model's own answer isn't grounded enough — but BM25 DID select
            // relevant cards, so instead of telling the CHW "I don't have this"
            // we serve the clinician-authored card content (the BM25 result) so
            // they still get the full, authoritative answer. Same graceful
            // fallback the L4 validator uses.
            Log.i(
                ChatViewModel.TRACE_TAG,
                "groundedness %.2f < floor %.2f → serving BM25 card fallback"
                    .format(Locale.US, groundedness, floor),
            )
            serveGroundingFallbackOrRefuse(
                grounding = promptGrounding,
                isBangla = isBangla,
                validatorReason = "groundedness:%.2f".format(Locale.US, groundedness),
                queryForSafety = guardQuery,
                clinicalTerms = scopeClassifier.scopeTerms(),
            )
            return
        }
    }

    // L4 — Output validator. Reject responses that introduce drugs or dosages
    // not present in the retrieved candidates, or that exceed the length cap.
    // In the open-scope path there are no candidates by definition, so drop the
    // drug/dosage block-list — the safety caveat in the system prompt does the
    // work the block-list was meant to do.
    val validation = outputValidator.validateChatResponse(
        rawResponse,
        promptGrounding,
        maxWords = config.chatTuning.maxResponseWords,
        allowFreeText = (promptMode == PromptMode.OpenScope),
        // English text as it appeared in the prompt — covers Bangla mode too,
        // where the question is pre-translated before reaching the LLM.
        userQuestion = englishCurrent,
        enableDrugGuard = config.chatTuning.enableDrugGuard,
        enableDosageGuard = config.chatTuning.enableDosageGuard,
    )
    if (!validation.isValid) {
        Log.w(ChatViewModel.TAG, "L4 validator rejected: ${validation.failureReason}")
        serveGroundingFallbackOrRefuse(
            grounding = promptGrounding,
            isBangla = isBangla,
            validatorReason = validation.failureReason,
            queryForSafety = guardQuery,
            clinicalTerms = scopeClassifier.scopeTerms(),
        )
        return
    }

    val responseText = if (isBangla && rawResponse.isNotBlank()) {
        val outResult = sdk.translator.translateEnToBnResult(rawResponse)
        if (!outResult.translated) {
            translationPassthrough = true
            Log.w(ChatViewModel.TAG, "EN→BN output passthrough — untranslated English shown to the CHW")
        }
        val bn = outResult.text.ifBlank { rawResponse }
        Log.d(ChatViewModel.TAG, "Post-translated EN→BN (${rawResponse.length} → ${bn.length} chars)")
        // L5 — translation fidelity guard. If MLKit hands back an empty string
        // or a response that's still > 30% Latin chars, prefix the EN body with
        // the "(translation unavailable)" string so the CHW gets *something*
        // actionable rather than a blank Bangla bubble.
        val latinShare = bn.count { it.code in 0x41..0x7A }.toFloat() / bn.length.coerceAtLeast(1).toFloat()
        if (latinShare > 0.3f) {
            Log.w(ChatViewModel.TAG, "L5 translation degraded — latinShare=$latinShare; falling back to EN+prefix")
            localizedString(R.string.chat_translation_degraded)
                .format(rawResponse)
        } else {
            bn
        }
    } else rawResponse

    // No typewriter reveal: the assistant bubble renders markdown (**bold**,
    // bullet/numbered lists), and progressively revealing half-typed markdown
    // reflows and flashes raw markers. Instead the StreamingBubble shows only
    // the "●●●" typing dots (streamingText stays blank) for the whole
    // generation, then snaps to the fully-rendered message committed below.

    if (!inferenceSpanEnded) {
        telemetry.endInferenceStream(
            span = inferenceSpan,
            estimatedInputTokens = (prompt.length / 4).toLong(),
            estimatedOutputTokens = (responseText.length / 4).toLong(),
            latencyMs = latencyMs,
            success = responseText.isNotBlank(),
        )
    }
    telemetry.chatMessageCounter.add(1)

    if (responseText.isNotBlank()) {
        val happyOutcome = if (promptMode == PromptMode.OpenScope) "served_open_scope" else "served_grounded"
        Log.i(
            ChatViewModel.TRACE_TAG,
            "OUTCOME=$happyOutcome served len=${responseText.length} " +
                "grounded=${promptGrounding.map { it.chunkId }} text=\"${tracePreview(responseText)}\"",
        )
        val attribution = resolveSourceAttribution(promptGrounding)
        // Response object (offline shape) as a JSON string for payload_json.response.
        val responseJson = serializeChatResponse(offlineChatResponse(responseText, attribution.moduleId))
        val assistantMsg = ChatMessage(
            sessionId = session.sessionId,
            role = ChatRole.ASSISTANT,
            text = responseText,
            traceId = inferenceSpan.spanContext.traceId,
            source = MessageSource.LOCAL_MODEL,
            meta = ChatMessageMeta(
                outcome = happyOutcome,
                groundedFrom = promptGrounding.map { it.chunkId },
                moduleId = attribution.moduleId,
                inferenceMode = "edge",
                networkState = currentNetworkState(),
                validatorStatus = "pass",
                fallbackUsed = false,
                responseJson = responseJson,
                question = currentQuestion,
            ),
            sourceDocuments = attribution.docs,
            groundingModuleFamilyId = attribution.familyId,
            startPage = attribution.startPage,
        ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }
        _uiState.update {
            (it as? ChatUiState.Ready)?.copy(
                messages = (it as ChatUiState.Ready).messages + assistantMsg,
                isGenerating = false,
                streamingText = "",
            ) ?: it
        }
        // IT-help telemetry — happy path. `served_grounded` when retrieval surfaced
        // the answer; `served_open_scope` when the LLM judged scope itself.
        val topScore = promptGrounding.firstOrNull()?.score
        val chunkIds = promptGrounding.map { it.chunkId }
        eventRecorder.recordDigitalHelpUsed(
            inferenceMode = "edge",
            validatorStatus = "pass",
            fallbackUsed = false,
            networkState = currentNetworkState(),
            // Events-Modelling v1.2: the dominant grounding chunk's module
            // version is what grounded this served answer.
            moduleId = attribution.moduleId,
            payloadJson = buildRefusalPayload(
                outcome = happyOutcome,
                topScore = topScore,
                chunkIds = chunkIds,
                validatorReason = null,
                translationPassthrough = if (isBangla) translationPassthrough else null,
                response = responseJson,
            ),
        )
    } else {
        Log.i(ChatViewModel.TRACE_TAG, "OUTCOME=empty_response — LLM/translation produced no text; nothing served")
        _uiState.update {
            (it as? ChatUiState.Ready)?.copy(
                isGenerating = false,
                streamingText = "",
                error = localizedString(R.string.chat_error_no_response_generated),
            ) ?: it
        }
        // IT-help telemetry — empty-response branch. Tracked separately so we can
        // distinguish silent failures from thrown ones in the dashboard.
        eventRecorder.recordDigitalHelpUsed(
            inferenceMode = "edge",
            validatorStatus = "fail",
            fallbackUsed = false,
            networkState = currentNetworkState(),
            payloadJson = buildJsonObject {
                currentQuestion?.takeIf { it.isNotBlank() }?.let { put("question", it) }
                put("reason", "empty_response")
            }.toString(),
        )
    }
}

/**
 * Retrieval-only path used on low-end (< 3 GB RAM) devices. Mirrors the
 * scope filters from the capable-device path (L0 deny-list, L1 allow-list
 * in Strict mode) but skips the LLM, the L3 sentinels, and the L4
 * validator entirely — the BM25 result is served verbatim.
 */
internal suspend fun ChatViewModel.handleLowEndMessage(trimmed: String) {
    val isBangla = sdk.language == Language.BANGLA
    val scopeClassifier = ScopeClassifier.buildFrom(sdk.morningModules.value)

    // L0 — hard deny-list. Cheap; runs on the original (untranslated) text.
    if (scopeClassifier.isOutOfScope(trimmed)) {
        Log.d(ChatViewModel.TAG, "Low-end L0 deny-list match — refusing without retrieval")
        serveRefusal(ChatRefusal.Scope, groundedFrom = emptyList(), topScore = null)
        return
    }

    // L1 — allow-list (Strict only; Extended skips and lets BM25 decide).
    val strictMode = config.chatScopeStrictness == ChatScopeStrictness.Strict
    if (strictMode && !scopeClassifier.isInScope(trimmed)) {
        serveRefusal(ChatRefusal.Scope, groundedFrom = emptyList(), topScore = null)
        return
    }

    // L2 — BM25 retrieval. Module content is Bengali-first, so retrieval anchors on
    // the BN index in both modes. Bangla mode also consults the EN index when the
    // typed question mixes English clinical terms (via BN→EN); English mode queries
    // the BN index with an EN→BN translation and the EN index with the original
    // English, requiring the EN↔BN pack — without it we surface the language-pack
    // error and stop, mirroring the online RAG path.
    val banglaQuery: String? =
        if (isBangla) null
        else banglaRetrievalQueryOrError(trimmed) ?: return
    val knowledgeIndex = sdk.chatKnowledgeIndex.value
    val searchLang = ModuleKnowledgeIndex.Lang.BN
    val nativeQuery = banglaQuery ?: trimmed
    val englishQuery = if (isBangla) {
        val result = sdk.translator.translateBnToEnResult(trimmed)
        Log.i(
            ChatViewModel.TRACE_TAG,
            "BN→EN translate (low-end): translated=${result.translated} " +
                "altered=${result.text != trimmed} in=\"${tracePreview(trimmed)}\" " +
                "out=\"${tracePreview(result.text)}\"",
        )
        result.text
    } else {
        trimmed
    }
    val guardQuery = when {
        banglaQuery != null -> "$trimmed $banglaQuery"        // English mode: EN typed + BN translation
        englishQuery != trimmed -> "$trimmed $englishQuery"   // Bangla mode: BN typed + EN translation
        else -> trimmed
    }
    val selection = GroundingSelector.select(
        nativeQuery = nativeQuery,
        englishQuery = englishQuery,
        nativeLanguage = searchLang,
        index = knowledgeIndex,
        k = ChatViewModel.GROUNDING_K,
        scoreThreshold = config.chatTuning.bm25ScoreThreshold,
    )
    val grounding = selection.hits
    Log.i(ChatViewModel.TRACE_TAG, "BM25 low-end[$searchLang] hits=${grounding.size} chosen=${selection.chosenLabel}")
    grounding.forEachIndexed { i, h -> Log.i(ChatViewModel.TRACE_TAG, traceChunk("  hit", i, h)) }
    if (grounding.isEmpty()) {
        Log.d(ChatViewModel.TAG, "Low-end retrieval miss — no usable grounding chunk")
        serveRefusal(ChatRefusal.NoGround, groundedFrom = emptyList(), topScore = null)
        return
    }
    // Phase-0 garbage guard. Refuse only on weak top scores with zero overlap;
    // confident hits bypass. Then pick serve target — BM25 #1 unless a later hit
    // is clearly better aligned (referral timing, stub body, title overlap).
    if (OffTopicGuard.shouldRefuseLowEnd(
            query = guardQuery,
            hits = grounding,
            clinicalTerms = scopeClassifier.scopeTerms(),
        )
    ) {
        Log.i(ChatViewModel.TRACE_TAG, "Phase-0 garbage guard (low-end): zero clinical-token overlap across top hits — refusing")
        serveRefusal(ChatRefusal.NoGround, groundedFrom = emptyList(), topScore = grounding.first().score)
        return
    }
    val top = OffTopicGuard.selectLowEndServeHit(
        query = guardQuery,
        hits = grounding,
        clinicalTerms = scopeClassifier.scopeTerms(),
    ) ?: grounding.first()
    Log.d(ChatViewModel.TAG, "Low-end serving retrieval-only — chunkId=${top.chunkId} score=${top.score}")
    val attribution = resolveSourceAttribution(listOf(top))
    // Prefer the concise linked quiz explanation; else the card body, clipped so
    // it never ends mid-sentence. Both are clinician-authored; served in the SDK
    // language (the other-language side is translated when only it is present) on
    // the LLM-less low-end path.
    val explanation = resolveExplanation(top, isBangla)
    serveFallback(
        bodyBn = explanation ?: clipToCompleteSentence(resolveCardBody(top, isBangla)),
        groundedFrom = listOf(top.chunkId),
        validatorReason = null,
        fallbackKind = if (explanation != null) "fallback_quiz_explanation" else "served_retrieval_only",
        sourceDocuments = attribution.docs,
        groundingModuleFamilyId = attribution.familyId,
        groundingModuleId = attribution.moduleId,
        startPage = attribution.startPage,
    )
}

/**
 * English-mode retrieval query: [trimmed] translated EN→BN so the offline search
 * can reach the Bengali-first module content, mirroring the online RAG path
 * ([handleBackendRagMessage]). Returns the Bengali query, or `null` when the
 * EN↔BN pack is unavailable — in which case it has already shown
 * [R.string.chat_error_language_pack] and recorded telemetry, and the caller must
 * stop (offline can't answer bn-only content without the pack).
 */
internal suspend fun ChatViewModel.banglaRetrievalQueryOrError(trimmed: String): String? {
    sdk.translator.ensureModelReady()
    val inResult = sdk.translator.translateEnToBnResult(trimmed)
    if (!inResult.translated) {
        Log.w(ChatViewModel.TAG, "local: EN→BN pack unavailable — cannot search bn-only content")
        Log.i(
            ChatViewModel.TRACE_TAG,
            "local ← LANG-PACK FAIL (EN→BN) state=${sdk.translationModelState.value}",
        )
        eventRecorder.recordDigitalHelpUsed(
            inferenceMode = "edge",
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
        return null
    }
    Log.i(
        ChatViewModel.TRACE_TAG,
        "EN→BN retrieval translate: in=\"${tracePreview(trimmed)}\" out=\"${tracePreview(inResult.text)}\"",
    )
    return inResult.text
}
