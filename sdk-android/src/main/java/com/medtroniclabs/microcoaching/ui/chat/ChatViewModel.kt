package com.medtroniclabs.microcoaching.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.chat.ChatMessage
import com.medtroniclabs.microcoaching.ui.chat.ChatRole
import com.medtroniclabs.microcoaching.ui.chat.MessageSource
import com.medtroniclabs.microcoaching.data.repository.ChatRepositoryImpl
import com.medtroniclabs.microcoaching.ai.model.ModelState
import com.medtroniclabs.microcoaching.ai.inference.InferenceRouter
import com.medtroniclabs.microcoaching.ai.translation.TranslationModelState
import com.medtroniclabs.microcoaching.ai.voice.BanglaTtsHelper
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the AI coaching chat.
 *
 * Responsibilities:
 *   - Initializing [InferenceRouter] on first use
 *   - Sending user messages and streaming LLM responses
 *   - Persisting messages to MicroCoachingDatabase via [ChatRepositoryImpl]
 *   - Emitting OTel spans via TelemetryManager
 *   - Exposing [ChatUiState] to [ChatScreen]
 *
 * Created via [Factory] — no Hilt required (SDK is DI-framework-agnostic).
 * SPICE can inject [MicroCoachingSDK.getInstance().dataRepository] via its own Hilt AppModule.
 */
class ChatViewModel(
    application: Application,
    private val patientId: String,
    private val systemContext: String,
) : AndroidViewModel(application) {

    private val sdk = MicroCoachingSDK.getInstance()
    private val config = sdk.config
    private val telemetry = sdk.telemetry
    private val db = sdk.database
    private val chatRepo = ChatRepositoryImpl(db.chatMessageDao())
    private val inferenceRouter = InferenceRouter(config)
    private val tts = BanglaTtsHelper(application.applicationContext)

    /**
     * Resolves a string resource through the SDK-configured locale rather than
     * the host's device locale, so error states surface in Bangla regardless
     * of where this VM is instantiated.
     */
    private fun localizedString(@androidx.annotation.StringRes resId: Int): String {
        val ctx = com.medtroniclabs.microcoaching.ui.SdkLocaleHelper.wrap(
            getApplication<android.app.Application>(),
            sdk.language,
        )
        return ctx.getString(resId)
    }

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val session = ChatSession(
        systemContext = systemContext,
    )

    private val sessionSpan = telemetry.startChatSession(session.sessionId)
    private var inferenceJob: Job? = null

    init {
        viewModelScope.launch { initializeModel() }
        observeModelState()
        observeMorningCards()
    }

    /**
     * Observes [ModelManager.state] so the UI reacts to download progress, failure, and success
     * without the user having to manually refresh.
     *
     * - [ModelState.Downloading]     → update progress bar while download is in flight
     * - [ModelState.DownloadFailed]  → clear the spinner so the user can retry
     * - [ModelState.Ready]           → model just landed on device; auto-init the inference engine
     */
    private fun observeModelState() {
        viewModelScope.launch {
            sdk.modelManager.state.collect { modelState ->
                when (modelState) {
                    is ModelState.Downloading -> {
                        _uiState.update {
                            when (it) {
                                is ChatUiState.ModelNotReady -> it.copy(isDownloading = true, downloadProgress = modelState.progressPercent)
                                is ChatUiState.Ready -> it.copy(isModelDownloading = true, modelDownloadProgress = modelState.progressPercent)
                                else -> it
                            }
                        }
                    }
                    is ModelState.DownloadFailed -> {
                        _uiState.update {
                            when (it) {
                                is ChatUiState.ModelNotReady -> it.copy(isDownloading = false, downloadProgress = -1)
                                is ChatUiState.Ready -> it.copy(isModelDownloading = false, modelDownloadProgress = -1)
                                else -> it
                            }
                        }
                    }
                    is ModelState.Ready -> {
                        val currentState = _uiState.value
                        if (currentState is ChatUiState.Ready) {
                            // Model just finished downloading while chat is already open.
                            // Must call initializeIfModelPresent() to actually load the inference
                            // engine — just setting modelPresent = true leaves activeService null,
                            // causing "No response available" on the next message send.
                            inferenceRouter.initializeIfModelPresent()
                            _uiState.update {
                                (it as? ChatUiState.Ready)?.copy(
                                    modelPresent = inferenceRouter.isModelAvailable,
                                    isModelDownloading = false,
                                    modelDownloadProgress = -1,
                                ) ?: it
                            }
                        } else {
                            initializeModel()
                        }
                    }
                    is ModelState.LoadFailed -> {
                        // Model file was corrupt and has been deleted; prompt re-download.
                        _uiState.value = ChatUiState.ModelNotReady()
                    }
                    else -> { /* Idle — no action needed */ }
                }
            }
        }
    }

    private fun observeMorningCards() {
        viewModelScope.launch {
            sdk.morningModules.collect { modules ->
                val seeded = modules.take(3).mapNotNull { entity ->
                    val title = entity.titleBn ?: entity.titleEn ?: return@mapNotNull null
                    SuggestedQuestion(
                        question = title,
                        banglaQuestion = title,
                        scenarioId = entity.moduleFamilyId,
                    )
                }
                Log.d(TAG, "[Suggestions] morningModules emitted ${modules.size} modules, sdk.language=${sdk.language}")
                if (sdk.language == Language.BANGLA) {
                    // Bangla: show chips immediately, no translation needed
                    _uiState.update { state ->
                        (state as? ChatUiState.Ready)?.copy(suggestedQuestions = seeded) ?: state
                    }
                } else {
                    // English: don't show chips until translation pack is ready and chips are translated.
                    // Chips stay empty while the pack downloads — no Bangla flash.
                    translateSuggestionsToEnglish(seeded)
                }
            }
        }
    }

    /**
     * Waits for the MLKit BN→EN pack to be ready, then translates all chip titles
     * and patches state in one shot. Chips remain hidden (empty list in state) until
     * this coroutine completes, so the user never sees an untranslated Bangla chip.
     *
     * If the pack fails to download, Bangla titles are shown as a fallback so the
     * CHW still has actionable chips rather than nothing.
     */
    private fun translateSuggestionsToEnglish(items: List<SuggestedQuestion>) {
        viewModelScope.launch {
            Log.d(TAG, "[Suggestions] English mode — triggering translation pack download and waiting for Ready")
            // Kick off the download if it hasn't started yet (in English mode the pack
            // isn't triggered automatically by setLanguage, so we start it here).
            sdk.translator.ensureModelReady()

            val packState = sdk.translationModelState
                .first { it is TranslationModelState.Ready || it is TranslationModelState.Failed }

            if (packState is TranslationModelState.Failed) {
                Log.w(TAG, "[Suggestions] Translation pack failed — falling back to Bangla chip titles")
                _uiState.update { state ->
                    (state as? ChatUiState.Ready)?.copy(suggestedQuestions = items) ?: state
                }
                return@launch
            }

            val translated = items.map { sq ->
                if (sq.banglaQuestion.isBlank()) sq
                else {
                    val en = sdk.translator.translateBnToEn(sq.banglaQuestion)
                    Log.d(TAG, "[Suggestions] BN='${sq.banglaQuestion}' → EN='$en'")
                    sq.copy(question = en)
                }
            }
            Log.d(TAG, "[Suggestions] Pack ready — showing ${translated.size} translated chips")
            _uiState.update { state ->
                (state as? ChatUiState.Ready)?.copy(suggestedQuestions = translated) ?: state
            }
        }
    }

    private suspend fun initializeModel() {
        _uiState.value = ChatUiState.Loading

        val service = inferenceRouter.initializeIfModelPresent()

        if (service == null && sdk.modelManager.isModelPresent()) {
            // A model file exists but failed to load — it is corrupt. Delete it and reset
            // state so the UI shows the download prompt rather than an unusable chat screen.
            sdk.modelManager.onModelLoadFailed()
            _uiState.value = ChatUiState.ModelNotReady()
            return
        }

        // Allow chat via backend RAG even when local model is absent
        if (service == null && config.backendUrl.isEmpty()) {
            _uiState.value = ChatUiState.ModelNotReady()
            return
        }

        val history = chatRepo.getHistory(session.sessionId)
        val morningModules = sdk.morningModules.value.take(3)
        val seededQuestions = morningModules.mapNotNull { entity ->
            val title = entity.titleBn ?: entity.titleEn ?: return@mapNotNull null
            SuggestedQuestion(
                question = title,
                banglaQuestion = title,
                scenarioId = entity.moduleFamilyId,
            )
        }
        // In English mode chips start hidden — translateSuggestionsToEnglish will populate
        // them once the translation pack is ready, avoiding any Bangla flash.
        _uiState.value = ChatUiState.Ready(
            messages = history,
            modelPresent = service != null,
            suggestedQuestions = if (sdk.language == Language.BANGLA) seededQuestions else emptyList(),
        )
        if (sdk.language == Language.ENGLISH) {
            translateSuggestionsToEnglish(seededQuestions)
        }
    }

    /**
     * Send a user message and stream the LLM response.
     * Cancels any in-progress generation before starting a new one.
     *
     * @param text User message.
     * @param scenarioId Optional anchored scenario — set when the user tapped a suggested
     *   question. When null, the SDK runs a BM25 retrieval over `scenario_cache` to find a
     *   match. Either way, the resolved scenario (or null) is injected into the prompt by
     *   [ChatSession.buildPrompt].
     */
    fun sendMessage(text: String, scenarioId: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (_uiState.value !is ChatUiState.Ready) return

        inferenceJob?.cancel()
        inferenceJob = viewModelScope.launch {
            val currentState = _uiState.value as? ChatUiState.Ready ?: return@launch

            // Persist user message — capture the DB-assigned ID so LazyColumn keys are unique.
            val userMsg = ChatMessage(
                sessionId = session.sessionId,
                role = ChatRole.USER,
                text = trimmed,
            ).let { it.copy(id = chatRepo.saveMessage(it)) }
            val updatedMessages = currentState.messages + userMsg
            _uiState.update {
                (it as? ChatUiState.Ready)?.copy(
                    messages = updatedMessages,
                    isGenerating = true,
                    streamingText = "",
                    error = null,
                ) ?: it
            }

            // Online RAG path was removed in Phase 2.4 (backend `/rag/answer` not implemented).
            // Chat now goes straight to on-device Gemma. If a server-side RAG endpoint
            // becomes available later, restore a try-online-first branch here.

            // On-device Gemma
            val llm = inferenceRouter.activeService ?: run {
                _uiState.update {
                    (it as? ChatUiState.Ready)?.copy(
                        isGenerating = false,
                        error = localizedString(com.medtroniclabs.microcoaching.R.string.chat_error_no_response_available),
                    ) ?: it
                }
                return@launch
            }

            // BN→EN→AI→EN→BN round-trip: Gemma 3 1B is English-dominant, so when
            // the SDK is configured for Bangla we pre-translate the user's question
            // to English before prompting (the response is post-translated below).
            // This keeps the UI bubble showing the original Bangla input but lets
            // the model actually understand the question.
            val englishCurrent = if (sdk.language == Language.BANGLA) {
                val en = sdk.translator.translateBnToEn(trimmed)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Pre-translated BN→EN: <${en.length} chars>")
                }
                en
            } else trimmed

            // Build prompt — pass previous messages only; currentMessage is appended by buildPrompt.
            // Force the English system-prompt variant when round-tripping so all model-facing
            // instructions are in the language the model handles best.
            val promptLanguage = if (sdk.language == Language.BANGLA) "en-US" else sdk.language.bcp47
            val prompt = session.buildPrompt(
                currentMessage = englishCurrent,
                history = currentState.messages,
                language = promptLanguage,
            )
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Prompt: <${prompt.length} chars>")
            }

            // OTel span
            val modelName = config.modelPath.substringAfterLast("/").ifBlank { "gemma.task" }
            val engineName = if (modelName.endsWith(".litertlm")) "litertlm" else "mediapipe"
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
                    _uiState.update {
                        (it as? ChatUiState.Ready)?.copy(isGenerating = false, error = errMsg) ?: it
                    }
                }
                .collect { token ->
                    responseBuilder.append(token)
                    // In Bangla mode we hold off streaming English tokens to the UI —
                    // StreamingBubble shows "●●●" while we collect, then we typewrite
                    // the translated Bangla text after the stream ends.
                    if (!isBangla) {
                        _uiState.update {
                            (it as? ChatUiState.Ready)?.copy(streamingText = responseBuilder.toString()) ?: it
                        }
                    }
                }

            val latencyMs = System.currentTimeMillis() - startMs
            // Strip <end_of_turn> and anything after it — Gemma appends it after its response.
            val rawResponse = responseBuilder.toString()
                .substringBefore("<end_of_turn>")
                .trim()
            val responseText = if (isBangla && rawResponse.isNotBlank()) {
                val bn = sdk.translator.translateEnToBn(rawResponse).ifBlank { rawResponse }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Post-translated EN→BN (${rawResponse.length} → ${bn.length} chars)")
                }
                bn
            } else rawResponse

            // Typewrite the Bangla response so the CHW sees text appearing naturally
            // rather than a sudden jump from "●●●" to the full translated block.
            if (isBangla && responseText.isNotBlank()) {
                val sb = StringBuilder()
                for (i in responseText.indices step 4) {
                    sb.append(responseText.substring(i, minOf(i + 4, responseText.length)))
                    _uiState.update {
                        (it as? ChatUiState.Ready)?.copy(streamingText = sb.toString()) ?: it
                    }
                    delay(18L)
                }
            }

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
                val assistantMsg = ChatMessage(
                    sessionId = session.sessionId,
                    role = ChatRole.ASSISTANT,
                    text = responseText,
                    traceId = inferenceSpan.spanContext.traceId,
                    source = MessageSource.LOCAL_MODEL,
                ).let { it.copy(id = chatRepo.saveMessage(it)) }
                _uiState.update {
                    (it as? ChatUiState.Ready)?.copy(
                        messages = (it as ChatUiState.Ready).messages + assistantMsg,
                        isGenerating = false,
                        streamingText = "",
                    ) ?: it
                }
            } else {
                _uiState.update {
                    (it as? ChatUiState.Ready)?.copy(
                        isGenerating = false,
                        streamingText = "",
                        error = localizedString(com.medtroniclabs.microcoaching.R.string.chat_error_no_response_generated),
                    ) ?: it
                }
            }
        }
    }

    fun sendQuickAnswer(question: String, answer: String) {
        if (_uiState.value !is ChatUiState.Ready) return
        viewModelScope.launch {
            val userMsg = ChatMessage(
                sessionId = session.sessionId,
                role = ChatRole.USER,
                text = question,
            ).let { it.copy(id = chatRepo.saveMessage(it)) }
            val assistantMsg = ChatMessage(
                sessionId = session.sessionId,
                role = ChatRole.ASSISTANT,
                text = answer,
            ).let { it.copy(id = chatRepo.saveMessage(it)) }
            _uiState.update {
                (it as? ChatUiState.Ready)?.copy(messages = it.messages + userMsg + assistantMsg) ?: it
            }
        }
    }

    fun speakText(text: String) = tts.speak(text)

    fun stopSpeaking() = tts.stop()

    fun requestModelDownload() {
        sdk.modelManager.triggerDownload()
        _uiState.update {
            when (it) {
                is ChatUiState.ModelNotReady -> it.copy(isDownloading = true)
                is ChatUiState.Ready -> it.copy(isModelDownloading = true, modelDownloadProgress = 0)
                else -> it
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        telemetry.endChatSession(sessionSpan)
        inferenceRouter.release()
        tts.release()
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }

    /** Factory for creating [ChatViewModel] without Hilt — SDK is DI-framework-agnostic. */
    class Factory(
        private val application: Application,
        private val patientId: String = "",
        private val systemContext: String = "",
    ) : ViewModelProvider.AndroidViewModelFactory(application) {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                return ChatViewModel(application, patientId, systemContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
