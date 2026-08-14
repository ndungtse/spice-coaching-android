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
import com.medtroniclabs.microcoaching.ai.voice.ttsLocaleFor
import com.medtroniclabs.microcoaching.ai.voice.stt.SttModelState
import com.medtroniclabs.microcoaching.network.RagQueryRequest
import com.medtroniclabs.microcoaching.network.RagQueryResponse
import com.medtroniclabs.microcoaching.network.SourceDocumentRef
import com.medtroniclabs.microcoaching.ui.document.DocumentPreviewActivity
import com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder
import com.medtroniclabs.microcoaching.domain.validation.OutputValidator
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper
import com.medtroniclabs.microcoaching.R
import java.util.Locale
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    internal val patientId: String,
    internal val systemContext: String,
) : AndroidViewModel(application) {

    internal val sdk = MicroCoachingSDK.getInstance()
    internal val config = sdk.config
    internal val telemetry = sdk.telemetry
    internal val db = sdk.database
    internal val chatRepo = ChatRepositoryImpl(db.chatMessageDao())

    // Shared, ref-counted: an embedded CoachingChatFragment and the chat bottom
    // sheet can be alive simultaneously — per-VM routers meant two engines on
    // the same .task (double model memory, native MediaPipe crash). Paired
    // with SharedInferenceRouter.release() in onCleared.
    internal val inferenceRouter = SharedInferenceRouter.acquire(config)
    // TTS locale tracks the SDK language rather than being hardcoded to Bangla: chat
    // message text is language-matched (an English app shows and speaks the EN
    // translation of a bn-only backend answer), and a Bangla voice reading English text
    // is unintelligible.
    internal val tts = CoachingTtsHelper(application.applicationContext, ttsLocaleForSdkLanguage())
    // Lazy because it depends on `session` which is declared below.
    internal val eventRecorder: EventRecorder by lazy {
        EventRecorder(
            dao = db.coachingEventDao(),
            sessionId = session.sessionId,
            chwId = sdk.currentCHWId.orEmpty(),
        )
    }
    internal val outputValidator = OutputValidator()
    internal val suggestionsRepository = ChatSuggestionsRepository(
        appContext = application.applicationContext,
        moduleDao = sdk.database.moduleDao(),
    )
    internal val chatFaqRepository = ChatFaqRepository(db.chatFaqDao())

    // Manual on-device/online mode preference. Defaults to on-device even when
    // connected; the UI chip toggles this. Routing in sendMessage combines it
    // with live connectivity: online = preferOnline && sdk.isNetworkAvailable().
    private val chatModePrefs = ChatModePrefs(application.applicationContext)

    /** Reactive on-device/online preference for the header chip. */
    val preferOnline: StateFlow<Boolean> = chatModePrefs.preferOnline

    /**
     * Real download size for the selected model. Seeded from cache so a repeat
     * visit is accurate on the first frame, then refreshed in the background.
     * Null → the card falls back to the catalog's approximate constant.
     */
    private val aiSizeBytes = MutableStateFlow(
        sdk.modelManager.cachedModelSizeBytes(),
    )

    fun setPreferOnline(value: Boolean) = chatModePrefs.setPreferOnline(value)

    /**
     * The TTS voice locale for the chat, derived from the SDK language so the spoken
     * voice matches the (language-matched) message text. Read once at VM construction;
     * a mid-session language switch recreates the chat surface.
     */
    internal fun ttsLocaleForSdkLanguage(): Locale = ttsLocaleFor(sdk.language)

    /**
     * Resolves a string resource through the SDK-configured locale rather than
     * the host's device locale, so error states surface in Bangla regardless
     * of where this VM is instantiated.
     */
    internal fun localizedString(@androidx.annotation.StringRes resId: Int): String {
        val ctx = SdkLocaleHelper.wrap(
            getApplication<android.app.Application>(),
            sdk.language,
        )
        return ctx.getString(resId)
    }

    /**
     * Truncated, single-line preview of user/model text for the [TRACE_TAG]
     * pipeline logs. Content previews can contain CHW-typed text — keep these
     * at a level you can strip from production builds, and never log full
     * untruncated message bodies. The metadata-only trace lines (scores, ids,
     * lengths, booleans) carry no such risk.
     */
    internal fun tracePreview(s: String?, max: Int = 120): String {
        if (s.isNullOrEmpty()) return "∅"
        val oneLine = s.replace('\n', '⏎').replace("\r", "")
        return if (oneLine.length <= max) oneLine else oneLine.take(max) + "…(${oneLine.length} chars)"
    }

    /** One-line [TRACE_TAG] description of a BM25 grounding candidate. */
    internal fun traceChunk(label: String, i: Int, c: GroundingChunk): String =
        "$label[$i] score=%.2f src=%s chunk=%s family=%s title=\"%s\"".format(
            Locale.US,
            c.score,
            c.source,
            c.chunkId,
            c.moduleFamilyId,
            tracePreview(c.titleEn ?: c.titleBn, 60),
        )

    internal val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    internal val session = ChatSession(
        systemContext = systemContext,
    )

    internal val sessionSpan = telemetry.startChatSession(session.sessionId)
    internal var inferenceJob: Job? = null

    /**
     * The CHW's raw question for the in-flight turn, stashed by [sendMessage] so
     * the deep serve* helpers and payload builders can put it under
     * `payload_json.question` (Events-Modelling 1.7) without threading it through
     * every signature. Chat is strictly single-turn (`isGenerating` guards
     * re-entry), so exactly one question is live at a time.
     */
    internal var currentQuestion: String? = null

    init {
        // Chat is opening → start building the BM25 knowledge index now (deferred
        // from SDK init). Idempotent; the first build finishes before the CHW can
        // type + send, so retrieval at query time sees a populated index.
        sdk.ensureChatKnowledgeIndex()
        viewModelScope.launch { initializeModel() }
        observeModelState()
        observeVoiceForAutoEnter()
    }

    /**
     * Observes [ModelManager.state] so the UI reacts to download progress, failure, and success
     * without the user having to manually refresh.
     *
     * - [ModelState.Downloading]     → update progress bar while download is in flight
     * - [ModelState.DownloadFailed]  → clear the spinner so the user can retry
     * - [ModelState.Ready]           → model landed; mark the AI card done and only
     *                                  enter chat once the voice pack is ready too
     *                                  (see [maybeAutoEnterChat]) — no more silent auto-jump.
     */
    internal fun observeModelState() {
        viewModelScope.launch {
            sdk.modelManager.state.collect { modelState ->
                when (modelState) {
                    is ModelState.Downloading -> {
                        _uiState.update {
                            when (it) {
                                is ChatUiState.SetupRequired -> it.copy(
                                    isDownloading = true,
                                    isPaused = false,
                                    downloadProgress = modelState.progressPercent,
                                    downloadBytesDownloaded = modelState.bytesDownloaded,
                                    downloadTotalBytes = modelState.totalBytes,
                                    // Bytes are moving, so any earlier verdict — damaged OR
                                    // ready — no longer describes anything. aiReady matters
                                    // here because it outranks isDownloading on the card: a
                                    // stale true renders "Downloaded" over a live download.
                                    aiReady = false,
                                    aiUnusable = false,
                                    aiOnDiskBytes = null,
                                    loadError = null,
                                )
                                is ChatUiState.Ready -> it.copy(
                                    isModelDownloading = true,
                                    modelDownloadProgress = modelState.progressPercent,
                                    modelDownloadBytesDownloaded = modelState.bytesDownloaded,
                                    modelDownloadTotalBytes = modelState.totalBytes,
                                )
                                else -> it
                            }
                        }
                    }
                    is ModelState.Paused -> {
                        _uiState.update {
                            when (it) {
                                is ChatUiState.SetupRequired -> it.copy(
                                    isDownloading = false,
                                    isPaused = true,
                                    downloadProgress = modelState.progressPercent,
                                    // A paused partial is present but not loadable.
                                    aiReady = false,
                                )
                                else -> it
                            }
                        }
                    }
                    is ModelState.DownloadFailed -> {
                        _uiState.update {
                            when (it) {
                                is ChatUiState.SetupRequired -> it.copy(
                                    isDownloading = false,
                                    isPaused = false,
                                    downloadProgress = -1,
                                    // Any partial kept for Range-resume is not a model yet.
                                    aiReady = false,
                                )
                                is ChatUiState.Ready -> it.copy(isModelDownloading = false, modelDownloadProgress = -1)
                                else -> it
                            }
                        }
                    }
                    is ModelState.Ready -> {
                        when (val currentState = _uiState.value) {
                            is ChatUiState.Ready -> {
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
                            }
                            is ChatUiState.Loading -> {
                                // initializeModel() is already in flight (it set
                                // _uiState to Loading on entry and the launched
                                // coroutine hasn't completed yet). Calling
                                // initializeModel() again here would race the
                                // first call and cause MediaPipe's LLM engine to
                                // load the same .task file twice on different
                                // threads — that's the native crash we hit in
                                // libllm_inference_engine_jni.so. Skip; the
                                // existing init will set Ready when it finishes.
                                @Suppress("UNUSED_VARIABLE")
                                val ignored = currentState
                            }
                            is ChatUiState.SetupRequired -> {
                                // Model is downloaded — mark the AI card done and
                                // enable the manual "Go to chat" button. Do NOT
                                // auto-jump into chat here: entering waits until the
                                // voice pack is ready too (maybeAutoEnterChat). The
                                // user can still tap "Go to chat" now — voice keeps
                                // downloading in the in-chat background banner.
                                _uiState.update {
                                    (it as? ChatUiState.SetupRequired)?.copy(
                                        aiReady = true,
                                        isDownloading = false,
                                        isPaused = false,
                                        // Ready is only reachable through the validation
                                        // gate, so an earlier damaged verdict is stale.
                                        aiUnusable = false,
                                        aiOnDiskBytes = modelState.modelFile.length(),
                                        loadError = null,
                                    ) ?: it
                                }
                                maybeAutoEnterChat()
                            }
                            else -> maybeAutoEnterChat()
                        }
                    }
                    is ModelState.LoadFailed -> {
                        // The file passed the structural check, so the bytes are fine and the
                        // failure is transient — a load retry is worth offering. Shown as ready
                        // so "Go to chat" retries, rather than a Download CTA that would wipe a
                        // good file. Wrong bytes become Corrupt instead.
                        val present = sdk.modelManager.isModelPresent()
                        _uiState.value = ChatUiState.SetupRequired(
                            aiRequired = true,
                            aiReady = present,
                            aiSizeBytes = aiSizeBytes.value,
                            aiOnDiskBytes = sdk.modelManager.localModelSizeBytes(),
                            loadError = localizedString(R.string.chat_model_load_failed_transient),
                        )
                    }
                    is ModelState.Corrupt -> {
                        // The file is gone and no retry can load it, so report both byte counts
                        // and the one action that resolves it. aiReady stays false to keep
                        // "Go to chat" disabled.
                        Log.e(
                            TAG,
                            "Model unusable: ${modelState.reason} " +
                                "(${modelState.onDiskBytes} of ${modelState.expectedBytes} bytes, " +
                                "canRetry=${modelState.canRetry})",
                        )
                        _uiState.value = ChatUiState.SetupRequired(
                            aiRequired = true,
                            aiReady = false,
                            aiUnusable = true,
                            aiSizeBytes = modelState.expectedBytes,
                            aiOnDiskBytes = modelState.onDiskBytes,
                            aiCanRetryDownload = modelState.canRetry,
                            // Past the budget the card hides its action, so the wording must
                            // stop pointing at a button that is no longer there.
                            loadError = localizedString(
                                if (modelState.canRetry) {
                                    R.string.chat_model_file_damaged
                                } else {
                                    R.string.chat_model_file_damaged_no_retry
                                },
                            ),
                        )
                    }
                    is ModelState.Idle -> {
                        // Hit by cancelDownload — partial file is gone, reset the UI
                        // back to the initial "Download AI Model" CTA (keep aiRequired).
                        _uiState.update {
                            when (it) {
                                is ChatUiState.SetupRequired ->
                                    ChatUiState.SetupRequired(
                                        aiRequired = it.aiRequired,
                                        aiSizeBytes = it.aiSizeBytes,
                                    )
                                else -> it
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Auto-enter chat only when everything the setup screen was waiting on is
     * ready: the AI model (skipped on low-end) AND the Bengali voice pack (only
     * relevant in BANGLA mode). If the model is ready but voice isn't, we stay on
     * the setup screen with the "Go to chat" button enabled so the user can enter
     * manually — voice then finishes in the in-chat background banner. TTS never
     * gates entry (it's optional read-aloud and platform-delegated).
     */
    internal fun maybeAutoEnterChat() {
        val s = _uiState.value as? ChatUiState.SetupRequired ?: return
        // The manager's Ready is required alongside the UI flag: entering is an engine
        // load, and only ModelState knows whether the file is actually loadable right
        // now. This also keeps LoadFailed from auto-entering — retrying a failed load
        // is the user's tap, not a surprise transition.
        val aiOk = !s.aiRequired ||
            (s.aiReady && sdk.modelManager.state.value is ModelState.Ready)
        val voiceOk = sdk.language != Language.BANGLA ||
            sdk.sttModelManager.state.value is SttModelState.Ready
        if (aiOk && voiceOk) enterChat()
    }

    /**
     * Observe the Bengali voice pack so that, once it lands while the user is
     * still on the setup screen, we re-check the both-ready gate and auto-enter.
     * The card's live progress is collected separately in [CoachingChatSurface];
     * this observer exists solely to trigger the auto-enter transition.
     */
    internal fun observeVoiceForAutoEnter() {
        if (sdk.language != Language.BANGLA) return
        viewModelScope.launch {
            sdk.sttModelManager.state.collect { sttState ->
                if (sttState is SttModelState.Ready &&
                    _uiState.value is ChatUiState.SetupRequired
                ) {
                    maybeAutoEnterChat()
                }
            }
        }
    }

    /**
     * Kick off the small on-device language packs the moment chat opens, so they
     * download in parallel with (or instead of) the AI model rather than only
     * after it. Idempotent: [SttModelManager.triggerBengaliDownload] no-ops when
     * the pack is already present or in flight. TTS reports its own state via the
     * [tts] helper's init — no explicit trigger needed here.
     */
    internal fun autoStartOnDevicePacks() {
        if (sdk.language == Language.BANGLA) {
            runCatching { sdk.sttModelManager.triggerBengaliDownload() }
                .onFailure { Log.w(TAG, "auto-start Bengali STT download failed: ${it.message}") }
        }
    }

    /** Open the system TTS-data installer for the missing read-aloud voice pack. */
    fun installTtsData() = tts.installLanguageData()

    /**
     * Tap handler for the seed-suggestion chips. Persists the suggestion as
     * "used" so it never re-offers, refreshes the chip row from the remaining
     * pool, then sends the message via the normal inference path.
     */
    fun sendSuggestion(suggestion: SuggestedQuestion) {
        suggestionsRepository.markUsed(suggestion)
        // Refresh the chip row asynchronously — nextBatch() now reads from
        // Room (cached modules + quiz JSON). Running on viewModelScope keeps
        // the tap responsive; the UI updates as soon as the new batch is
        // computed.
        viewModelScope.launch {
            val next = loadSuggestions()
            _uiState.update { state ->
                (state as? ChatUiState.Ready)?.copy(suggestedQuestions = next) ?: state
            }
        }
        val text = when (sdk.language) {
            Language.BANGLA ->
                suggestion.banglaQuestion.ifBlank { suggestion.question }
            Language.ENGLISH ->
                suggestion.question.ifBlank { suggestion.banglaQuestion }
        }
        sendMessage(text, moduleFamilyId = suggestion.moduleFamilyId)
    }

    /**
     * Decide, on chat open, whether to show the on-device setup screen or go
     * straight into chat — and kick off the small language packs either way.
     *
     * - Low-end devices never download the AI model. They show the setup screen
     *   only while a voice pack is still pending; otherwise they open straight
     *   into retrieval-only chat (as before).
     * - Capable devices without the Gemma model on disk show the setup screen
     *   (AI card behind a manual Download button + auto-downloading voice pack).
     * - Capable devices with the model present load the engine and open chat.
     *
     * The actual "enter chat" work (engine + history load) lives in
     * [loadReadyChat] so the setup screen's "Go to chat" button and the
     * both-ready auto-enter can reuse it via [enterChat].
     */
    internal suspend fun initializeModel() {
        _uiState.value = ChatUiState.Loading

        // Start the small on-device packs immediately (idempotent) so they
        // download while the user is on the setup screen — not only after the AI
        // model lands. Replaces the old SDK-init "wait for AI Ready, then STT" chain.
        autoStartOnDevicePacks()
        refreshAiSizeLabel()

        val voicePending = sdk.language == Language.BANGLA &&
            sdk.sttModelManager.state.value !is SttModelState.Ready

        if (sdk.isLowEndDevice) {
            if (voicePending) {
                Log.i(TAG, "Low-end device — voice pack pending, showing setup screen")
                _uiState.value = ChatUiState.SetupRequired(aiRequired = false)
                return
            }
            loadReadyChat()
            return
        }

        // Entry keys on ModelState, not on a file existing: the worker streams into the
        // final filename, so mid-download a presence check is true while the bytes are
        // still arriving. Only Ready (which is validation-gated) may reach the engine;
        // every other state renders its own setup card via currentSetupRequiredState.
        when (sdk.modelManager.state.value) {
            is ModelState.Ready -> loadReadyChat()
            else -> {
                Log.i(
                    TAG,
                    "Model not ready (${sdk.modelManager.state.value::class.simpleName}) — showing setup screen",
                )
                _uiState.value = currentSetupRequiredState(aiRequired = true)
            }
        }
    }

    /**
     * Resolve the selected model's real download size in the background and patch
     * it into a live [ChatUiState.SetupRequired] when it lands. Fire-and-forget:
     * the size is informational and must never gate the setup screen. Nothing to
     * patch once the user has entered chat.
     */
    private fun refreshAiSizeLabel() {
        if (sdk.isLowEndDevice) return   // No AI card on low-end devices.
        viewModelScope.launch {
            val resolved = runCatching { sdk.modelManager.resolveModelSizeBytes() }.getOrNull()
                ?: return@launch
            if (aiSizeBytes.value == resolved) return@launch
            aiSizeBytes.value = resolved
            (_uiState.value as? ChatUiState.SetupRequired)?.let {
                _uiState.value = it.copy(aiSizeBytes = resolved)
            }
        }
    }

    /**
     * Actually enter chat: load history and (on capable devices) the inference
     * engine, then emit [ChatUiState.Ready]. Low-end devices open in
     * retrieval-only mode (`modelPresent=false`). On a capable device whose
     * engine fails to load, fall back to the setup screen so the user can retry
     * the download rather than typing into a non-functional model.
     */
    internal suspend fun loadReadyChat() {
        try {
            loadReadyChatInternal()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Anything escaping here would strand _uiState on Loading, and
            // [enterChat] refuses to re-enter from Loading — every later tap
            // becomes a silent no-op behind a permanent spinner. Land on a state
            // the user can retry from instead.
            // The exception text is a developer artefact, so it stays in logcat.
            Log.e(TAG, "loadReadyChat failed unexpectedly: ${e.message}", e)
            _uiState.value = currentSetupRequiredState(aiRequired = !sdk.isLowEndDevice)
                .copy(loadError = localizedString(R.string.chat_model_load_failed_transient))
        }
    }

    private suspend fun loadReadyChatInternal() {
        _uiState.value = ChatUiState.Loading

        // Low-end devices skip the inference engine entirely. The chat opens
        // straight to Ready and every message routes through BM25 → bodyBn
        // (see sendMessage below). modelPresent=false signals downstream UI
        // surfaces that we're in retrieval-only mode.
        if (sdk.isLowEndDevice) {
            Log.i(TAG, "Low-end device — initialising chat in retrieval-only mode")
            val history = chatRepo.getRecentHistory(
                chwId = sdk.currentCHWId.orEmpty(),
                limit = HISTORY_LIMIT,
            )
            _uiState.value = ChatUiState.Ready(
                messages = history,
                modelPresent = false,
                suggestedQuestions = loadSuggestions(),
            )
            backfillFaqTranslationsThenRefresh()
            return
        }

        // A download in flight must never reach the engine: the worker is writing into
        // the very file a load would mmap, and the file is by definition incomplete.
        // Guarded here — not only at the entry decision — so every caller (Go to chat,
        // auto-enter) is covered.
        val stateAtEntry = sdk.modelManager.state.value
        if (stateAtEntry is ModelState.Downloading || stateAtEntry is ModelState.Paused) {
            Log.i(TAG, "Model download in flight (${stateAtEntry::class.simpleName}) — showing setup screen instead of loading")
            _uiState.value = currentSetupRequiredState(aiRequired = true)
            return
        }

        // Load the engine, retrying briefly on failure. A model that just finished
        // downloading can transiently fail to load (file still flushing / mmap race on
        // slower devices), and a short backoff clears that common case before the failure
        // path below has to decide whether the file is worth keeping.
        var service = inferenceRouter.initializeIfModelPresent()
        var attempt = 1
        while (service == null && attempt < MODEL_LOAD_MAX_ATTEMPTS && sdk.modelManager.isModelPresent()) {
            Log.w(TAG, "Engine load returned null (attempt $attempt/${MODEL_LOAD_MAX_ATTEMPTS}) — retrying in ${MODEL_LOAD_RETRY_DELAY_MS}ms")
            delay(MODEL_LOAD_RETRY_DELAY_MS)
            service = inferenceRouter.initializeIfModelPresent()
            attempt++
        }

        // No working local inference engine → fall back to the setup surface,
        // regardless of whether the host configured a backend URL. Opening the
        // chat surface here would render an input and suggestion chips over a
        // model that can't answer, so chat is only "ready" once the engine loads.
        if (service == null) {
            // A present file that no bundled engine can EVER load (non-runnable
            // selected variant, or modelPath pointing at a non-`.task` file such
            // as a leftover `.litertlm`) is not a transient failure — retrying just
            // bounces the user back to a re-enabled "Go to chat" forever. Surface an
            // honest error instead of the silent retry loop. (Transient native/mmap
            // failures fall through to the retry-friendly path below.)
            if (sdk.modelManager.isModelPresent() && !inferenceRouter.canRunResolvedModel()) {
                Log.e(TAG, "Present model cannot be loaded by any bundled engine — not retryable; surfacing error.")
                _uiState.value = ChatUiState.Error(localizedString(R.string.chat_model_unsupported))
                return
            }
            if (sdk.modelManager.isModelPresent()) {
                // File on disk but the engine couldn't load it. The manager runs the
                // structural check and decides: wipe + Corrupt when the bytes are wrong,
                // keep + LoadFailed when they aren't. The native cause is logged, not shown.
                Log.e(TAG, "Engine load failure, native cause: ${inferenceRouter.lastLoadError}")
                sdk.modelManager.onModelLoadFailed()
                // onModelLoadFailed may have emitted Corrupt, which observeModelState renders
                // as a damaged card; don't overwrite it with the retry state below.
                if (sdk.modelManager.state.value is ModelState.Corrupt) return
            }
            // currentSetupRequiredState derives aiReady from ModelState — a structurally
            // valid file that transiently failed is LoadFailed → aiReady=true, so
            // "Go to chat" re-attempts the load rather than a Download button wiping a
            // good file. Never asserted from raw file presence: mid-download the file
            // "exists" while the bytes are still arriving.
            //
            // A localized sentence, not `lastLoadError`: that field carries the engine's
            // native text, which is logged above and belongs in logcat only. Suppressed
            // when a download moved in under this attempt — the progress card describes
            // that state; an error would contradict it.
            Log.e(TAG, "Engine failed to load after $attempt attempt(s) — state=${sdk.modelManager.state.value::class.simpleName}")
            val inFlight = sdk.modelManager.state.value
                .let { it is ModelState.Downloading || it is ModelState.Paused }
            _uiState.value = currentSetupRequiredState(aiRequired = true).let {
                if (inFlight) it
                else it.copy(loadError = localizedString(R.string.chat_model_load_failed_transient))
            }
            return
        }

        // History is keyed on CHW, not session — sessionId is fresh per VM
        // (it drives EventRecorder.sessionId for telemetry bucketing) and would
        // always return an empty list. See [ChatRepositoryImpl.getRecentHistory].
        val history = chatRepo.getRecentHistory(
            chwId = sdk.currentCHWId.orEmpty(),
            limit = HISTORY_LIMIT,
        )
        val seededQuestions = loadSuggestions()
        _uiState.value = ChatUiState.Ready(
            messages = history,
            modelPresent = true,
            suggestedQuestions = seededQuestions,
        )
        backfillFaqTranslationsThenRefresh()
    }

    /**
     * Manual "Go to chat" from the setup screen (also the target of the both-ready
     * auto-enter). Guards against re-entry: setting [ChatUiState.Loading]
     * synchronously closes the window where a concurrent [maybeAutoEnterChat]
     * could launch a second engine load on the same .task file.
     */
    fun enterChat() {
        val current = _uiState.value
        if (current is ChatUiState.Loading || current is ChatUiState.Ready) return
        _uiState.value = ChatUiState.Loading
        viewModelScope.launch { loadReadyChat() }
    }

    /**
     * If any synced chat FAQ still lacks its English question, attempt on-device
     * translation now (the ML Kit pack may have become available since the last
     * sync) and refresh the suggestion chips when something changes. Fire-and-
     * forget; a no-op when nothing is pending, and a passthrough (pack still
     * unavailable) simply leaves the chips as-is for the next attempt.
     */
    internal fun backfillFaqTranslationsThenRefresh() {
        viewModelScope.launch {
            runCatching {
                if (!chatFaqRepository.hasPendingTranslation()) return@launch
                val updated = chatFaqRepository.translatePending(sdk.translator)
                if (updated > 0) {
                    val refreshed = loadSuggestions()
                    _uiState.update { state ->
                        (state as? ChatUiState.Ready)?.copy(suggestedQuestions = refreshed) ?: state
                    }
                }
            }
        }
    }

    /**
     * Send a user message and stream the LLM response.
     * Cancels any in-progress generation before starting a new one.
     *
     * @param text User message.
     * @param moduleFamilyId Optional anchored module family — set when the user tapped a
     *   suggested question. When null, no module is anchored. The resolved value (or null)
     *   is injected into the prompt by [ChatSession.buildPrompt].
     */
    fun sendMessage(text: String, moduleFamilyId: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val readyState = _uiState.value as? ChatUiState.Ready ?: return
        if (readyState.isGenerating) return

        inferenceJob?.cancel()
        // Stash the in-flight question so serve* helpers / payload builders can
        // echo it into `payload_json.question` (Events-Modelling 1.7).
        currentQuestion = trimmed
        inferenceJob = viewModelScope.launch {
            val currentState = _uiState.value as? ChatUiState.Ready ?: return@launch

            // Persist user message — capture the DB-assigned ID so LazyColumn keys are unique.
            val userMsg = ChatMessage(
                sessionId = session.sessionId,
                role = ChatRole.USER,
                text = trimmed,
            ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }
            val updatedMessages = currentState.messages + userMsg
            _uiState.update {
                (it as? ChatUiState.Ready)?.copy(
                    messages = updatedMessages,
                    isGenerating = true,
                    streamingText = "",
                    error = null,
                ) ?: it
            }

            // ── Routing ──────────────────────────────────────────────────────
            // Online (any device): backend RAG — higher quality, no local LLM needed.
            // Offline: low-end uses BM25-only; normal device uses on-device Gemma + BM25.
            //
            // The chat now defaults to on-device: online is taken ONLY when the user
            // has opted into it via the header mode chip AND connectivity is present.
            // `preferOnline=false` (the default) forces the on-device pipeline even on
            // a connected device.
            val prefersOnline = preferOnline.value
            val connected = sdk.isNetworkAvailable()
            val online = prefersOnline && connected
            val route = when {
                online -> "ONLINE → backend RAG (POST /coaching/rag-query) — no on-device BM25/translation"
                sdk.isLowEndDevice -> "OFFLINE low-end → BM25-only (no LLM)"
                else -> "ON-DEVICE → Gemma + BM25"
            }
            // The one line that tells you which pipeline actually answered. NOTE the
            // header chip reflects the *chosen* mode (prefer=... below); the route is
            // gated by real connectivity — `net` is the live signal.
            Log.i(
                TRACE_TAG,
                "──── turn ──── route=[$route] mode=${if (prefersOnline) "online(pref)" else "on-device(pref)"} " +
                    "net=$connected lowEnd=${sdk.isLowEndDevice} " +
                    "lang=${sdk.language} strictness=${config.chatScopeStrictness} " +
                    "modelLoaded=${inferenceRouter.isModelAvailable} " +
                    "moduleFamilyId=${moduleFamilyId ?: "∅"} q=\"${tracePreview(trimmed)}\"",
            )
            if (online) {
                val handled = handleBackendRagMessage(trimmed)
                if (handled) return@launch
                // Online, but the backend RAG call failed for an infrastructure
                // reason (network drop, non-2xx, timeout). Don't dead-end with
                // "no response available" — fall through to the on-device pipeline
                // and answer from the offline BM25 index. (A 2xx blank answer is
                // handled inside handleBackendRagMessage as final — never reaches here.)
                Log.i(TRACE_TAG, "backend-rag failed → on-device fallback (offline pipeline)")
            }
            if (sdk.isLowEndDevice) {
                handleLowEndMessage(trimmed)
                return@launch
            }
            handleLocalGemmaMessage(trimmed, moduleFamilyId, currentState)
        }
    }

    /**
     * Snapshot of `ConnectivityManager` state at telemetry-emission time. Mirrors
     * the canonical values used by [sync.SyncApi.recordSyncAttempt] so the
     * dashboard sees a consistent vocabulary across event families.
     */
    internal fun currentNetworkState(): String =
        if (sdk.isNetworkAvailable()) "online" else "offline"

    /**
     * Build a [ChatUiState.SetupRequired] seeded from the *current* [ModelState]
     * snapshot — closes the race where a chat fragment opens mid-download and
     * the StateFlow's first emission lands while `_uiState` is still [Loading],
     * leaving the UI showing a "Download" button while a worker is actually in
     * flight. Reading the state at the same moment we transition to SetupRequired
     * guarantees the very first frame reflects reality.
     */
    internal fun currentSetupRequiredState(aiRequired: Boolean): ChatUiState.SetupRequired {
        // Size is orthogonal to the download state machine, so it's applied once
        // here rather than threaded through every branch below.
        return baseSetupRequiredState(aiRequired).copy(aiSizeBytes = aiSizeBytes.value)
    }

    private fun baseSetupRequiredState(aiRequired: Boolean): ChatUiState.SetupRequired {
        return when (val s = sdk.modelManager.state.value) {
            is ModelState.Downloading -> ChatUiState.SetupRequired(
                isDownloading = true,
                isPaused = false,
                downloadProgress = s.progressPercent,
                downloadBytesDownloaded = s.bytesDownloaded,
                downloadTotalBytes = s.totalBytes,
                aiRequired = aiRequired,
            )
            is ModelState.Paused -> ChatUiState.SetupRequired(
                isDownloading = false,
                isPaused = true,
                downloadProgress = s.progressPercent,
                aiRequired = aiRequired,
            )
            is ModelState.DownloadFailed -> ChatUiState.SetupRequired(
                isDownloading = false,
                isPaused = false,
                downloadProgress = -1,
                aiRequired = aiRequired,
            )
            is ModelState.Ready -> ChatUiState.SetupRequired(
                aiRequired = aiRequired,
                aiReady = true,
                aiOnDiskBytes = s.modelFile.length(),
            )
            is ModelState.LoadFailed -> ChatUiState.SetupRequired(
                // A structurally valid but unloadable file is kept on disk; treat it as
                // downloaded so the UI offers a load retry, not a re-download.
                aiRequired = aiRequired,
                aiReady = sdk.modelManager.isModelPresent(),
                aiOnDiskBytes = sdk.modelManager.localModelSizeBytes(),
            )
            is ModelState.Corrupt -> ChatUiState.SetupRequired(
                // Present-but-wrong. Never aiReady: the file has already been deleted and
                // only a fresh download resolves it.
                aiRequired = aiRequired,
                aiReady = false,
                aiUnusable = true,
                aiOnDiskBytes = s.onDiskBytes,
                aiCanRetryDownload = s.canRetry,
            )
            else -> ChatUiState.SetupRequired(aiRequired = aiRequired)
        }
    }

    /**
     * Returns the suggestions to display above the chat input.
     *
     * Prefers the synced chat FAQs ([ChatFaqRepository], ranked, cached via
     * `/sync/chat-faqs`) when any are cached; otherwise falls back to the curated
     * static [ChatSuggestionDefaults.all] (EN + BN). Module-sourced dynamic
     * suggestions ([suggestionsRepository]) remain available but unused.
     */
    internal suspend fun loadSuggestions(): List<SuggestedQuestion> =
        chatFaqRepository.loadSuggestions().ifEmpty { ChatSuggestionDefaults.all }



    /**
     * Resolve which side of a CARD chunk to surface as a fallback message, in the
     * SDK language. Prefer the same-language body; when only the other language is
     * present, translate it (EN→BN in Bangla mode, BN→EN in English mode) so the
     * served text matches the SDK language — an English user never sees raw Bengali.
     * Empty when the chunk carries no body on either side.
     */
    internal suspend fun resolveCardBody(
        chunk: GroundingChunk,
        isBangla: Boolean,
    ): String {
        val primary = if (isBangla) chunk.bodyBn else chunk.bodyEn
        if (!primary.isNullOrBlank()) return primary
        val secondary = if (isBangla) chunk.bodyEn else chunk.bodyBn
        if (secondary.isNullOrBlank()) return ""
        return if (isBangla) {
            sdk.translator.translateEnToBn(secondary).ifBlank { secondary }
        } else {
            sdk.translator.translateBnToEn(secondary).ifBlank { secondary }
        }
    }

    /**
     * The linked quiz explanation in the SDK language, or null when the chunk has
     * none. Prefers the same-language side; when only the other language is present
     * it is translated (EN→BN in Bangla mode, BN→EN in English mode). Real content
     * ships both sides, so translation is the rare path.
     */
    internal suspend fun resolveExplanation(chunk: GroundingChunk, isBangla: Boolean): String? {
        val primary = (if (isBangla) chunk.explanationBn else chunk.explanationEn)?.takeIf { it.isNotBlank() }
        if (primary != null) return primary
        val secondary = (if (isBangla) chunk.explanationEn else chunk.explanationBn)?.takeIf { it.isNotBlank() }
            ?: return null
        return if (isBangla) {
            sdk.translator.translateEnToBn(secondary).ifBlank { secondary }
        } else {
            sdk.translator.translateBnToEn(secondary).ifBlank { secondary }
        }
    }

    /**
     * Clip a fallback body to its last complete sentence so a served card body never
     * ends mid-sentence (the "…in the tablet. Each" failure). Falls back to the
     * trimmed raw text when the body carries no sentence terminator at all.
     */
    internal fun clipToCompleteSentence(text: String): String =
        trimToCompleteSentence(text).ifBlank { text.trim() }

    /**
     * Pull the source-document attribution off the dominant (top-1) BM25 chunk
     * for this message. Returns the module's source-document refs, the
     * `moduleFamilyId` (so the chat UI can resolve a label at render time), the
     * resolved version `moduleId` (backend `module.id`, used for the
     * `digital_help_used` telemetry per Events-Modelling v1.2), and the PDF page
     * anchor (first entry of the top chunk's `sourcePages`, used by the in-app
     * document viewer to deep-link). All default to empty/null when the module
     * is no longer in cache or the grounding set is empty / the card has no
     * `source_pages`.
     */
    data class SourceAttribution(
        val docs: List<SourceDocumentRef>,
        val familyId: String?,
        val moduleId: String?,
        val startPage: Int?,
    ) {
        companion object {
            val EMPTY = SourceAttribution(docs = emptyList(), familyId = null, moduleId = null, startPage = null)
        }
    }

    internal suspend fun resolveSourceAttribution(
        grounding: List<GroundingChunk>,
    ): SourceAttribution {
        val top = grounding.firstOrNull() ?: return SourceAttribution.EMPTY
        val familyId = top.moduleFamilyId
        // A null here is a legitimate cache miss; a thrown read is a real failure we
        // must not swallow silently — log it so it's observable, then degrade to the
        // family-only attribution (F11).
        val module = try {
            sdk.database.moduleDao().getByFamilyId(familyId)
        } catch (e: Exception) {
            Log.w(TAG, "Source-attribution module read failed for family=$familyId: ${e.message}")
            null
        } ?: return SourceAttribution(
            docs = emptyList(), familyId = familyId, moduleId = null, startPage = top.firstPageNumber,
        )
        // Pick the EXACT document the card cites (by source_document_id) + its page;
        // module-level first doc when the card has no page anchor. See
        // [SourceAttributionResolver] — pure so it is unit-tested without Room.
        val resolved = SourceAttributionResolver.resolve(top, module.sourceDocuments)
        return SourceAttribution(
            docs = resolved.docs,
            familyId = familyId,
            moduleId = module.moduleId,
            startPage = resolved.startPage,
        )
    }

    /**
     * Per-message cache of resolved `moduleFamilyId → title` (locale-aware) so
     * the chat surface doesn't query Room on every recomposition. Populated
     * lazily by [moduleTitleFor]. Keyed by `moduleFamilyId` rather than
     * message id so all messages sharing a module reuse the same title.
     */
    internal val moduleTitleCache = mutableMapOf<String, String?>()

    /**
     * Look up the SDK-locale title for a grounding module family, or `null` if
     * the module has been removed from the cache. The lookup is cached for the
     * lifetime of the ViewModel — modules are versioned per sync and titles
     * are stable across versions of the same family.
     */
    suspend fun moduleTitleFor(familyId: String): String? {
        moduleTitleCache[familyId]?.let { return it }
        if (moduleTitleCache.containsKey(familyId)) return null
        val module = try {
            sdk.database.moduleDao().getByFamilyId(familyId)
        } catch (e: Exception) {
            Log.w(TAG, "Module-title read failed for family=$familyId: ${e.message}")
            null
        }
        val title = if (sdk.language == Language.BANGLA) {
            module?.titleBn?.takeIf { it.isNotBlank() } ?: module?.titleEn
        } else {
            module?.titleEn?.takeIf { it.isNotBlank() } ?: module?.titleBn
        }
        moduleTitleCache[familyId] = title
        return title
    }

    /**
     * Tap-handler for a source-document chip. Fetches the short-lived
     * presigned URL from the backend and launches
     * [com.medtroniclabs.microcoaching.ui.document.DocumentPreviewActivity]
     * to render it. Silently no-ops when network is unavailable — the chip is
     * already greyed-out in that state by
     * [com.medtroniclabs.microcoaching.MicroCoachingSDK.networkAvailable].
     *
     * @param citedPage 1-indexed PDF page the citation points to — sourced from the
     *   BM25-matched card's `source_pages`. The viewer opens the WHOLE document and
     *   scrolls to this page: a citation is a starting point, and the CHW routinely
     *   needs the surrounding pages to act on it. Null falls back to page 1; ignored
     *   entirely for image / external formats.
     */
    fun openSourceDocument(sourceDocumentId: String, fallbackTitle: String, citedPage: Int? = null) {
        if (sourceDocumentId.isBlank()) return
        val originalFilename = (_uiState.value as? ChatUiState.Ready)?.messages
            ?.flatMap { it.sourceDocuments }
            ?.firstOrNull { it.id == sourceDocumentId }
            ?.originalFilename
        viewModelScope.launch {
            DocumentPreviewActivity.start(
                context = getApplication<android.app.Application>(),
                sourceDocumentId = sourceDocumentId,
                title = fallbackTitle,
                originalFilename = originalFilename,
                startPage = citedPage,
            )
        }
    }

    /**
     * Compose the small JSON blob used for `payload_json` on chatbot events.
     * Keys mirror the Events Modelling intent: refusal_outcome, top_score, chunk_ids,
     * and an optional validator_reason for L4 rejects so tuning can debug per-class.
     *
     * [response] carries the served **response object as a JSON string** (Events
     * Modelling 1.4/1.5: the `digital` family's `payload_json.response` is the full
     * RAG response object, or the offline-constructed equivalent — see
     * [serializeChatResponse] / [offlineChatResponse]). Passed for every served /
     * refusal / fallback turn; omitted only on pre-response failures
     * (language-pack, empty-response, inference error).
     */
    internal fun buildRefusalPayload(
        outcome: String,
        topScore: Float?,
        chunkIds: List<String>,
        validatorReason: String?,
        translationPassthrough: Boolean? = null,
        response: String? = null,
    ): String = buildJsonObject {
        // The CHW's question for this turn (Events-Modelling 1.7 `digital_help_used`).
        currentQuestion?.takeIf { it.isNotBlank() }?.let { put("question", it) }
        put("refusal_outcome", outcome)
        // Keep the 3-decimal rounding the hand-rolled version emitted.
        if (topScore != null) put("top_score", String.format(Locale.US, "%.3f", topScore).toDouble())
        putJsonArray("chunk_ids") { chunkIds.forEach { add(it) } }
        if (validatorReason != null) put("validator_reason", validatorReason)
        // F5 instrumentation: record when the BN↔EN pivot fell back to passthrough,
        // so grounded-answer fidelity of the LLM path can be measured in the field
        // (untranslated input to the model, or untranslated output to the CHW).
        if (translationPassthrough != null) put("translation_passthrough", translationPassthrough)
        if (!response.isNullOrBlank()) put("response", response)
    }.toString()

    /**
     * Json for `payload_json.response`. `encodeDefaults = true` keeps empty lists
     * and null scalars in the output so the offline-constructed response object
     * matches the online RAG shape field-for-field.
     */
    private val chatResponseJson = kotlinx.serialization.json.Json { encodeDefaults = true }

    /**
     * Serialize a [RagQueryResponse] to the JSON string stored in
     * `payload_json.response` for chat telemetry. `encodeDefaults = true` so empty
     * lists and null scalars are still emitted — the offline-constructed object
     * (see [offlineChatResponse]) then has the exact same shape as a real online
     * RAG response, with the non-fillable fields present but empty.
     */
    internal fun serializeChatResponse(resp: RagQueryResponse): String =
        chatResponseJson.encodeToString(RagQueryResponse.serializer(), resp)

    /**
     * The canonical response object for an OFFLINE turn (on-device Gemma, BM25
     * fallback, refusal), matching the online RAG shape. Only [answer] and — when
     * known — the grounding [moduleId] are filled; retrieval/source/suggestion
     * fields stay empty so the object is structurally identical to an online one.
     */
    internal fun offlineChatResponse(answer: String, moduleId: String? = null): RagQueryResponse =
        RagQueryResponse(answer = answer, citedModuleIds = listOfNotNull(moduleId))

    fun sendQuickAnswer(question: String, answer: String) {
        if (_uiState.value !is ChatUiState.Ready) return
        viewModelScope.launch {
            val userMsg = ChatMessage(
                sessionId = session.sessionId,
                role = ChatRole.USER,
                text = question,
            ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }
            val assistantMsg = ChatMessage(
                sessionId = session.sessionId,
                role = ChatRole.ASSISTANT,
                text = answer,
            ).let { it.copy(id = chatRepo.saveMessage(it, chwId = sdk.currentCHWId.orEmpty())) }
            _uiState.update {
                (it as? ChatUiState.Ready)?.copy(messages = it.messages + userMsg + assistantMsg) ?: it
            }
        }
    }

    /**
     * Record CHW feedback on an assistant response (thumbs up/down).
     *
     * **One-shot:** the first tap is final — once a message is rated it cannot be
     * cleared or switched (the UI disables both thumbs). A rating emits one
     * `chat_feedback_*` event mirroring the rated turn's `digital_help_used`
     * context. Thumbs-UP emits immediately; thumbs-DOWN defers its event to
     * [commitNegativeFeedback] when the detail sheet closes, so the CHW's optional
     * note rides in the SAME event. The rating is held in
     * [ChatUiState.Ready.feedback] (in-memory only — see the field's doc).
     *
     * (Toggling may return later; for now a re-tap is a no-op.)
     *
     * @param messageId [ChatMessage.id] of the rated assistant message.
     * @param positive true for thumbs-up, false for thumbs-down.
     */
    fun submitFeedback(messageId: Long, positive: Boolean) {
        val ready = _uiState.value as? ChatUiState.Ready ?: return
        val message = ready.messages.firstOrNull { it.id == messageId } ?: return
        if (message.role != ChatRole.ASSISTANT) return

        // Already rated → no-op. One-shot for now (see kdoc).
        if (ready.feedback.containsKey(messageId)) return

        _uiState.update {
            (it as? ChatUiState.Ready)?.copy(feedback = it.feedback + (messageId to positive)) ?: it
        }

        if (positive) emitChatFeedback(message, positive = true, note = null)
    }

    /**
     * Commit thumbs-down feedback when the detail sheet closes (Submit, scrim, or
     * swipe), carrying the CHW's optional free-text [note] in the same
     * `chat_feedback_negative` event so the backend receives it in
     * `payload_json.feedback` (Events Modelling 1.5).
     *
     * The note is also mirrored into [ChatUiState.Ready.feedbackNotes] so the sheet
     * can re-show it. No-op if the message is no longer rated thumbs-down (e.g. the
     * CHW cleared it in the meantime).
     */
    fun commitNegativeFeedback(messageId: Long, note: String) {
        val ready = _uiState.value as? ChatUiState.Ready ?: return
        if (ready.feedback[messageId] != false) return
        val message = ready.messages.firstOrNull { it.id == messageId } ?: return
        val trimmed = note.trim()
        _uiState.update {
            val r = it as? ChatUiState.Ready ?: return@update it
            r.copy(
                feedbackNotes = if (trimmed.isBlank()) r.feedbackNotes - messageId
                else r.feedbackNotes + (messageId to trimmed),
            )
        }
        emitChatFeedback(message, positive = false, note = trimmed.ifBlank { null })
    }

    /**
     * Emit one `chat_feedback_*` telemetry event for [message], echoing the rated
     * turn's pipeline context from [ChatMessage.meta] (inferring inference mode
     * from [ChatMessage.source] for history-loaded messages that carry no meta),
     * then nudge the outbound sync. [note] is the thumbs-down free text (null on
     * thumbs-up / when none was given).
     */
    private fun emitChatFeedback(message: ChatMessage, positive: Boolean, note: String?) {
        val meta = message.meta
        val inferenceMode = meta?.inferenceMode
            ?: if (message.source == MessageSource.RAG_API) "online" else "edge"
        // The rated response object as a JSON string, captured on the message when
        // it was served. History-loaded messages carry no meta → reconstruct a
        // minimal object from the visible text so the shape is still consistent.
        val responseJson = meta?.responseJson
            ?: serializeChatResponse(offlineChatResponse(message.text, meta?.moduleId))
        viewModelScope.launch {
            eventRecorder.recordChatFeedback(
                positive = positive,
                responseJson = responseJson,
                feedbackText = note,
                question = meta?.question,
                moduleId = meta?.moduleId,
                inferenceMode = inferenceMode,
                validatorStatus = meta?.validatorStatus,
                fallbackUsed = meta?.fallbackUsed,
                networkState = meta?.networkState ?: currentNetworkState(),
            )
            // Analytics events ride the existing telemetry sync; nudge it now.
            runCatching { sdk.flushTelemetryNow() }
        }
    }

    fun speakText(text: String) = tts.speak(text)

    fun stopSpeaking() = tts.stop()

    fun requestModelDownload() {
        if (sdk.isLowEndDevice) {
            Log.i(TAG, "requestModelDownload ignored — low-end device runs in retrieval-only mode")
            return
        }
        sdk.modelManager.triggerDownload()

        // triggerDownload can decline, notably when the corrupt-file re-download budget is
        // spent, so the optimistic update is conditional on a download having started.
        // Otherwise the card shows a progress row for work nobody is doing.
        val started = sdk.modelManager.state.value is ModelState.Downloading
        if (!started) {
            Log.i(TAG, "requestModelDownload: manager did not start a download — leaving UI as-is")
            return
        }

        _uiState.update {
            when (it) {
                // Cleared here rather than left to the state observer: aiUnusable outranks
                // every other field in toAiDownloadItemState, so a stale true would keep the
                // damaged subtitle over a live progress bar.
                is ChatUiState.SetupRequired -> it.copy(
                    isDownloading = true,
                    aiUnusable = false,
                    aiOnDiskBytes = null,
                    loadError = null,
                )
                is ChatUiState.Ready -> it.copy(isModelDownloading = true, modelDownloadProgress = 0)
                else -> it
            }
        }
    }

    /** User pressed Pause on the in-flight model download. */
    fun pauseModelDownload() {
        sdk.modelManager.pauseDownload()
    }

    /** User pressed Resume on a previously-paused download. */
    fun resumeModelDownload() {
        sdk.modelManager.resumeDownload()
    }

    /**
     * User pressed Cancel on the download — wipes the partial file and resets
     * back to the initial CTA. Distinct from [pauseModelDownload] which keeps
     * the partial file so resume can pick up cheaply.
     */
    fun cancelModelDownload() {
        sdk.modelManager.cancelDownload()
    }

    /**
     * Wipe every persisted chat message for the current CHW and reset the
     * visible message list to empty. Backs the header's "Clear chat" action.
     * The DB call is hard delete; there is no undo (the user confirms via
     * AlertDialog in [com.medtroniclabs.microcoaching.ui.screens.ChatScreen]).
     */
    fun clearChatHistory() {
        viewModelScope.launch {
            chatRepo.clearChwHistory(sdk.currentCHWId.orEmpty())
            _uiState.update { current ->
                (current as? ChatUiState.Ready)?.copy(messages = emptyList()) ?: current
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        telemetry.endChatSession(sessionSpan)
        // Drops this VM's reference; the engine unloads only when the LAST
        // live chat surface clears (see SharedInferenceRouter).
        SharedInferenceRouter.release()
        tts.release()
    }

    companion object {
        internal const val TAG = "ChatViewModel"

        /**
         * Dedicated tag for the end-to-end chat pipeline trace. Filter the whole
         * pipeline for one device with:  `adb logcat -s ChatTrace:I`
         * (add `ChatViewModel:D ModuleKnowledgeIndex:I OnDeviceTranslator:D` for
         * the lower-level operational logs). Every turn opens with a `──── turn ────`
         * line that names the route actually taken, so it is unambiguous whether a
         * message hit the backend RAG endpoint or the on-device Gemma/BM25 pipeline.
         */
        internal const val TRACE_TAG = "ChatTrace"

        /**
         * Grounding chunks retrieved per query and injected as reference cards.
         * 3 (was 2): the verified "Low BP 90/60" failure had the correct card at
         * rank 3. Three ~300-char references fit the prompt budget comfortably
         * within the 1536-token session window.
         */
        internal const val GROUNDING_K = 3

        // The groundedness floor and the streamed-response cap are now tunable at
        // runtime via [com.medtroniclabs.microcoaching.ChatTuning] (groundednessFloor /
        // streamCapChars), set through MicroCoachingSDK.Builder.chatTuning(...). They
        // used to be the fixed constants GROUNDEDNESS_FLOOR=0.25 and STREAM_CAP_CHARS=700.

        /** Sentence terminators recognised by [trimToCompleteSentence] — EN + Bangla danda. */
        private val SENTENCE_TERMINATORS = charArrayOf('.', '!', '?', '।')

        /**
         * Cut a window-truncated response back to its last complete sentence.
         * Returns "" when no terminator exists (the whole output is one
         * unfinished sentence) — the L4 validator then routes the turn to the
         * clinician-authored card-body fallback instead of serving a fragment.
         */
        internal fun trimToCompleteSentence(text: String): String {
            val lastEnd = text.lastIndexOfAny(SENTENCE_TERMINATORS)
            return if (lastEnd < 0) "" else text.substring(0, lastEnd + 1).trim()
        }

        /**
         * Maximum number of restored messages on chat reopen. All messages
         * remain persisted; this just caps how many are pushed into the UI on
         * init so a long-lived install doesn't render thousands of bubbles.
         */
        private const val HISTORY_LIMIT = 50

        /**
         * Engine-load retry budget for [loadReadyChat]. A model that just finished
         * downloading can transiently fail to load (file still flushing / mmap race
         * on slower physical devices), so we back off briefly and retry a couple of
         * times before treating the load as failed — this prevents the "download
         * completes, reverts to Download, re-downloads forever" loop.
         */
        private const val MODEL_LOAD_MAX_ATTEMPTS = 3
        private const val MODEL_LOAD_RETRY_DELAY_MS = 500L

        /**
         * Static floor of clinical domains injected into the open-scope LLM
         * prompt. Always present so the model has a stable scope reference
         * even when the indexed module corpus is empty (fresh install,
         * pre-sync). Combined at call-time with [ScopeClassifier.scopeTerms]
         * to widen scope as new modules ship.
         */
        internal val CLINICAL_SCOPE_FLOOR = listOf(
            "hypertension",
            "diabetes",
            "non-communicable diseases",
            "pregnancy and maternal health",
            "eyecare",
            "child health",
            "family planning",
            "newborn care",
            "danger signs",
            "referrals",
        )
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
