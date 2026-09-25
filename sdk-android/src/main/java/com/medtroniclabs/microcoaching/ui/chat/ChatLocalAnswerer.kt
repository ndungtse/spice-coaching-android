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
import com.medtroniclabs.microcoaching.ai.retrieval.ServeGate
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

// On-device answer paths for ChatViewModel, as extensions in the same package.
/**
 * On-device model path — used offline on a device capable of running the local model.
 * Runs the full L0→L5 pipeline: deny-list, scope gate, BM25 retrieval, generation,
 * L3/L4 validators, and the BN↔EN translation round-trip.
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
    // With no model loaded — an always-online device that never downloaded one, now
    // falling back from a failed backend call — degrade to the retrieval-only path rather
    // than erroring: clinician-authored content is still served from the offline index.
    val llm = inferenceRouter.activeService ?: run {
        Log.i(ChatViewModel.TRACE_TAG, "on-device model unavailable → degrading to BM25-only")
        handleRetrievalOnlyMessage(trimmed)
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

    // L0 — Hard deny-list. Catches obvious out-of-scope topics (coding, sports,
    // weather, entertainment) before any LLM call. Applies in BOTH Strict and
    // ExtendedClinical modes: a small on-device model is unreliable at refusing these
    // on its own, even with a tight open-scope prompt. Cheap substring match.
    val scopeClassifier = sdk.chatScopeClassifier.value
    if (scopeClassifier.isOutOfScope(trimmed) || scopeClassifier.isOutOfScope(englishCurrent)) {
        Log.d(ChatViewModel.TAG, "L0 deny-list: hard out-of-scope match — refusing without LLM call")
        serveRefusal(ChatRefusal.Scope, groundedFrom = emptyList(), topScore = null)
        return
    }

    // L1 — Scope allow-list. Advisory only: refusing here on a keyword miss rejects
    // legitimate clinical questions whose vocabulary the gazetteer has not seen. The
    // backstops that do refuse are L2 retrieval (no grounding) and [ServeGate]
    // (grounding without topical evidence); L0 still blocks out-of-scope topics hard.
    val l1InScope = scopeClassifier.isInScope(trimmed) || scopeClassifier.isInScope(englishCurrent)
    if (!l1InScope) {
        Log.d(ChatViewModel.TAG, "L1 advisory: scope keyword miss — deferring to retrieval + ServeGate")
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
        dense = denseCandidates(trimmed),
        rrfK = config.chatTuning.serve.rrfK,
        denseAdmitFloor = config.chatTuning.serve.cosFloor,
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
    Log.i(ChatViewModel.TRACE_TAG, GroundingSelector.describeFused(selection.fused))

    val guardQuery = when {
        banglaQuery != null -> "$trimmed $banglaQuery"          // English mode: EN typed + BN translation
        englishCurrent != trimmed -> "$trimmed $englishCurrent" // Bangla mode: BN typed + EN translation
        else -> trimmed
    }
    // The same decision the retrieval-only path makes, so the model can only reword a
    // card a CHW would otherwise have been shown verbatim, and a refusal costs no
    // inference. Honest-refusal policy: the on-device model never answers ungrounded
    // clinical content, nor from a card that shares no topic with the question.
    val decision = ServeGate.decide(
        query = guardQuery,
        hits = grounding,
        clinicalTerms = scopeClassifier.scopeTerms(),
        tuning = config.chatTuning.serve,
        isBanglaTurn = sdk.language == Language.BANGLA,
    )
    ServeGate.describeRank(decision)?.let { Log.i(ChatViewModel.TRACE_TAG, it) }
    Log.i(ChatViewModel.TRACE_TAG, ServeGate.describeGate(decision))
    val guardPrimary = when (decision) {
        is ServeGate.Decision.Refuse -> {
            serveRefusal(
                ChatRefusal.NoGround,
                groundedFrom = grounding.map { it.chunkId },
                topScore = grounding.firstOrNull()?.score,
                validatorReason = decision.reason.name.lowercase(),
            )
            return
        }
        is ServeGate.Decision.Serve -> decision.hit
    }
    // Translated to English before it reaches either the prompt or the groundedness check.
    // The corpus is Bengali-authored and the model is always prompted in English, so
    // untranslated references are text the model cannot read: it answers from pre-training
    // instead, and the groundedness comparison across languages is zero overlap by
    // arithmetic rather than by quality — which discards every answer and serves the card
    // verbatim.
    val ordered = readableGrounding(
        listOf(guardPrimary) + grounding.filter { it.chunkId != guardPrimary.chunkId },
    )
    // Capped here rather than at the prompt, so everything downstream judges the answer
    // against exactly what the model saw: otherwise the drug/dosage block-list could clear
    // a name that came from a card the model never read, and attribution could cite it.
    val promptGrounding = ordered.take(config.chatTuning.llmContextCards)
    val promptMode = PromptMode.Grounded
    Log.d(ChatViewModel.TAG, "promptMode=$promptMode, grounding=${promptGrounding.size} chunks, topScore=${promptGrounding.firstOrNull()?.score}")

    // History is deliberately NOT replayed to the model — every turn is independent.
    // With prior exchanges in the prompt the model imitates its own earlier answers,
    // which were grounded on *different* references, and drifts to pre-training
    // instead of the current reference block. The conversation stays visible in the
    // UI; this affects model context only. Follow-up support, if added, belongs as a
    // standalone-question rewrite applied BEFORE retrieval, not verbatim turn replay.
    // Exactly the text the retrieval-only path would have served for these cards — the
    // model's job is to reword it, so it must not see a different or shorter version. The
    // engine renders the model's own chat template, so this stays plain text.
    val prompt = buildContextAnswerPrompt(
        context = buildGroundingContext(promptGrounding.map { servedCardText(it, isBangla = false) }),
        question = englishCurrent,
    )
    Log.d(ChatViewModel.TAG, "Prompt: $prompt")

    // OTel span. Named from the selected variant, not `config.modelPath`, which is blank
    // unless the host pre-provisioned a file.
    val variant = config.selectedModelVariant()
    val modelName = variant.fileName
    val engineName = "litertlm"
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

    // Set when the stream cap below aborts generation — with no end-of-turn marker in the
    // output, this is the only evidence that the answer was cut short.
    var streamCapTripped = false

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
            // Stream cap — every chat mode mandates a 2–4 sentence answer, so a stream
            // running far past that is the model ignoring the prompt and free-styling
            // from pre-training. Such answers fail the groundedness gate anyway;
            // cancelling early reaches the same outcome for a fraction of the latency,
            // and the partial text still runs every normal gate.
            val streamCap = config.chatTuning.streamCapChars
            val withinCap = responseBuilder.length < streamCap
            if (!withinCap) {
                streamCapTripped = true
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
            // Tokens are buffered, never streamed raw to the UI: the raw text still has
            // to clear the validation gates (groundedness, question-echo, L4 block-list),
            // any of which can replace it with a refusal or a card fallback. Streaming
            // live would show the CHW an answer that then vanishes. StreamingBubble
            // shows "●●●" while collecting; validated text is revealed afterwards.
        }

    val latencyMs = System.currentTimeMillis() - startMs
    // The engine decodes special tokens itself, so there is no end-of-turn marker to cut
    // at. A `<think>` span still has to go: thinking is disabled, but a bundle whose
    // template ignores that must not leak the model's reasoning to the CHW.
    val untrimmedResponse = ChatViewModel.stripSourcePreamble(
        ChatViewModel.normalizeModelWhitespace(
            ChatViewModel.stripThinkSpans(responseBuilder.toString()),
        ),
    )

    // The model's raw English output, pre-validation/translation.
    //
    // A completed stream is a finished turn for this engine, so the only thing that can cut
    // the text short is our own stream cap. When it trips the reply stops mid-sentence, so
    // it is trimmed back to the last complete sentence ('.', '!', '?', or the Bangla danda
    // '।') — the CHW must never see a dangling fragment like "Pain can be alleviated by".
    val sawEndOfTurn = !streamCapTripped
    val rawResponse =
        if (sawEndOfTurn) untrimmedResponse else ChatViewModel.trimToCompleteSentence(untrimmedResponse)
    Log.i(
        ChatViewModel.TRACE_TAG,
        "LLM raw: len=${untrimmedResponse.length} latencyMs=$latencyMs sawEndOfTurn=$sawEndOfTurn " +
            "temp=${variant.temperature ?: config.inferenceTemperature} " +
            "maxTokens=${variant.maxTokens ?: config.maxInferenceTokens} " +
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

    // One POST line per assisted turn: what the post-model checks found and which text the
    // CHW is shown. The checks choose the text; the card was fixed by the gate.
    var groundednessNote = "-"
    fun postLine(validator: String, translation: String, shown: String) =
        "POST groundedness=$groundednessNote validator=$validator translation=$translation shown=$shown"

    // L3c — Groundedness gate (grounded mode only). The [[REFUSE_NO_GROUND]] sentinel
    // relies on the model NOTICING that the references don't cover the question; when
    // the references are merely adjacent, a small model answers fluently from
    // pre-training instead. Reference vocabulary survives honest paraphrase, so a
    // near-zero content-word overlap means the answer did not come from the references.
    // Refuse rather than serve pre-training content as if it were clinician-reviewed.
    // Score is traced on every grounded turn so the floor can be tuned from logs.
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
        val strongRetrieval = (topScore ?: 0f) >= tuning.strongRetrievalScore
        val floor =
            if (strongRetrieval) tuning.strongRetrievalGroundednessFloor
            else tuning.groundednessFloor
        groundednessNote = "%.2f floor=%.2f".format(Locale.US, groundedness, floor)
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
            Log.i(ChatViewModel.TRACE_TAG, postLine(validator = "skipped", translation = "skipped", shown = "card"))
            serveCardVerbatim(
                card = ordered.first(),
                isBangla = isBangla,
                validatorReason = "groundedness:%.2f".format(Locale.US, groundedness),
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
        Log.i(ChatViewModel.TRACE_TAG, postLine(validator = validation.failureReason ?: "rejected", translation = "skipped", shown = "card"))
        serveCardVerbatim(
            card = ordered.first(),
            isBangla = isBangla,
            validatorReason = validation.failureReason,
        )
        return
    }

    var translationNote = if (isBangla) "ok" else "n/a"
    val responseText = if (isBangla && rawResponse.isNotBlank()) {
        val outResult = sdk.translator.translateEnToBnResult(rawResponse)
        if (!outResult.translated) {
            translationPassthrough = true
            translationNote = "passthrough"
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
            translationNote = "degraded"
            Log.w(ChatViewModel.TAG, "L5 translation degraded — latinShare=$latinShare; falling back to EN+prefix")
            localizedString(R.string.chat_translation_degraded)
                .format(rawResponse)
        } else {
            bn
        }
    } else rawResponse
    Log.i(ChatViewModel.TRACE_TAG, postLine(validator = "ok", translation = translationNote, shown = "rewrite"))

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
 * Retrieval-only path: the selected card's clinician-authored text is served as written.
 *
 * The floor for every device, not a low-end special case — reached whenever the model is
 * absent, declined, unloadable, or simply not eligible. Retrieval is identical to the
 * model-backed path; what differs is that nothing rewords the result.
 *
 * Mirrors that path's scope filters (L0 deny-list, L1 allow-list in Strict mode) and skips the
 * layers that exist to police generated text — the L3 sentinels and the L4 validator — because
 * there is no generated text to police. [ServeGate] still applies, so an unrelated
 * top-scoring card is refused rather than served.
 */
internal suspend fun ChatViewModel.handleRetrievalOnlyMessage(trimmed: String) {
    val isBangla = sdk.language == Language.BANGLA
    val scopeClassifier = sdk.chatScopeClassifier.value

    // L0 — hard deny-list. Cheap; runs on the original (untranslated) text.
    if (scopeClassifier.isOutOfScope(trimmed)) {
        Log.d(ChatViewModel.TAG, "Retrieval-only L0 deny-list match — refusing without retrieval")
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
            "BN→EN translate (retrieval-only): translated=${result.translated} " +
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
        dense = denseCandidates(trimmed),
        rrfK = config.chatTuning.serve.rrfK,
        denseAdmitFloor = config.chatTuning.serve.cosFloor,
    )
    val grounding = selection.hits
    Log.i(ChatViewModel.TRACE_TAG, "BM25 retrieval-only[$searchLang] hits=${grounding.size} chosen=${selection.chosenLabel}")
    Log.i(ChatViewModel.TRACE_TAG, GroundingSelector.describeFused(selection.fused))
    grounding.forEachIndexed { i, h -> Log.i(ChatViewModel.TRACE_TAG, traceChunk("  hit", i, h)) }
    // The same call the LLM path makes, so both paths answer a given question from
    // the same card: served only with topical evidence, otherwise refused.
    val decision = ServeGate.decide(
        query = guardQuery,
        hits = grounding,
        clinicalTerms = scopeClassifier.scopeTerms(),
        tuning = config.chatTuning.serve,
        isBanglaTurn = isBangla,
    )
    ServeGate.describeRank(decision)?.let { Log.i(ChatViewModel.TRACE_TAG, it) }
    Log.i(ChatViewModel.TRACE_TAG, ServeGate.describeGate(decision))
    val top = when (decision) {
        is ServeGate.Decision.Refuse -> {
            serveRefusal(
                ChatRefusal.NoGround,
                groundedFrom = grounding.map { it.chunkId },
                topScore = grounding.firstOrNull()?.score,
                validatorReason = decision.reason.name.lowercase(),
            )
            return
        }
        is ServeGate.Decision.Serve -> decision.hit
    }
    Log.d(ChatViewModel.TAG, "Serving retrieval-only — chunkId=${top.chunkId} score=${top.score}")
    val attribution = resolveSourceAttribution(listOf(top))
    // Clinician-authored text in the SDK language, served as written. The same
    // [ChatViewModel.servedCardText] the model-backed path grounds on, so a CHW reading
    // either answer is reading the same card.
    val explanation = resolveExplanation(top, isBangla)
    serveFallback(
        bodyBn = servedCardText(top, isBangla),
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

/**
 * Dense retrieval candidates for the TYPED question — the multilingual encoder
 * needs no MLKit translation, so the dense channel sees the CHW's own words in
 * both UI languages. Empty whenever any piece is missing (flag off, no synced
 * vectors, no encoder, un-embeddable text): the turn then runs BM25-only, and the
 * one DENSE trace line says which way it went for audit drivers.
 */
internal suspend fun ChatViewModel.denseCandidates(typedText: String): List<Pair<GroundingChunk, Float>> {
    if (!config.enableDenseRetrieval) return emptyList()
    val index = sdk.chatDenseIndex.value
    val embedder = sdk.queryEmbedder
    if (index == null || embedder == null) {
        Log.i(ChatViewModel.TRACE_TAG, "DENSE off (index=${index != null} embedder=${embedder != null})")
        return emptyList()
    }
    val vec = embedder.embed(typedText)
    if (vec == null) {
        Log.i(ChatViewModel.TRACE_TAG, "DENSE off (query not embeddable)")
        return emptyList()
    }
    val hits = index.search(vec, config.chatTuning.serve.denseTopK)
    val top = hits.firstOrNull()?.let { "%s@%.2f".format(it.first.chunkId, it.second) } ?: "-"
    Log.i(ChatViewModel.TRACE_TAG, "DENSE hits=${hits.size} top=$top")
    return hits
}
