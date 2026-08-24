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
import com.medtroniclabs.microcoaching.ai.model.LocalModelChoice
import com.medtroniclabs.microcoaching.ai.model.ModelState
import com.medtroniclabs.microcoaching.ai.model.isTransferInFlight
import com.medtroniclabs.microcoaching.ai.inference.SharedInferenceRouter
import com.medtroniclabs.microcoaching.domain.decision.AnswerMode
import com.medtroniclabs.microcoaching.domain.decision.AnswerModeInputs
import com.medtroniclabs.microcoaching.domain.decision.resolveAnswerMode
import com.medtroniclabs.microcoaching.ui.screens.components.toAiDownloadItemState
import com.medtroniclabs.microcoaching.ai.retrieval.ChatRefusal
import com.medtroniclabs.microcoaching.ai.retrieval.GroundingChunk
import com.medtroniclabs.microcoaching.ai.retrieval.GroundingSelector
import com.medtroniclabs.microcoaching.ai.retrieval.ModuleKnowledgeIndex
import com.medtroniclabs.microcoaching.ai.retrieval.OffTopicGuard
import com.medtroniclabs.microcoaching.ai.retrieval.ScopeClassifier
import com.medtroniclabs.microcoaching.ai.voice.CoachingTtsHelper
import com.medtroniclabs.microcoaching.ai.voice.TtsState
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
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
    // sheet can be alive simultaneously, and per-VM routers meant two engines on the
    // same model file — double the memory, and a native crash. Paired with
    // SharedInferenceRouter.release() in onCleared.
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

    /**
     * Chunks whose Bengali side has already been translated for the model, keyed by chunkId.
     * Cards recur heavily across a session — the same module answers many questions — and each
     * miss costs an on-device translate of up to a full card body.
     */
    private val readableGroundingCache = LinkedHashMap<String, GroundingChunk>()

    fun setPreferOnline(value: Boolean) {
        Log.i(TRACE_TAG, "mode preference → ${if (value) "online" else "on-device"}")
        chatModePrefs.setPreferOnline(value)
    }

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
        observeAnswerModeInputs()
    }

    /**
     * Recomputes the published answer mode whenever anything it derives from changes.
     *
     * [ChatUiState.Ready.answerMode] is derived state, and every input to it moves
     * independently of the model's own lifecycle: the user flips the mode preference,
     * connectivity comes and goes, consent is granted or withdrawn. Without this the state is
     * only refreshed when [ModelState] emits — so choosing a mode would write the preference
     * and leave the bar and the sheet's selection showing the old one, which looks like the tap
     * did nothing at all. (Routing itself reads the preference live at send time, so only the
     * display was ever stale.)
     *
     * [ModelState] is deliberately not in this combine — [observeModelState] owns it, because
     * it also has to load the engine, which this must not do.
     */
    private fun observeAnswerModeInputs() {
        viewModelScope.launch {
            combine(
                chatModePrefs.preferOnline,
                sdk.networkAvailable,
                sdk.localModelPrefs.choice,
            ) { preferOnline, connected, choice -> Triple(preferOnline, connected, choice) }
                // The first emission repeats what chat open already published; dropping it
                // avoids overwriting a freshly built state with an identical one.
                .drop(1)
                .collect { (preferOnline, connected, choice) ->
                    Log.i(
                        TRACE_TAG,
                        "answer-mode inputs changed — pref=${if (preferOnline) "online" else "on-device"} " +
                            "net=$connected consent=$choice eligible=${!sdk.isLowEndDevice} " +
                            "→ ${resolveCurrentAnswerMode()}",
                    )
                    refreshModelUi()
                }
        }
    }

    /**
     * Mirrors [ModelManager.state] into the chat state so the mode bar reflects the model's
     * lifecycle without the user refreshing anything.
     *
     * Only ever updates a [ChatUiState.Ready]: chat no longer waits on the model, so there is
     * no separate screen to drive and no state where a download transition needs to move the
     * user somewhere. The one side effect is loading the engine when a download completes
     * mid-session, which is what lets an opt-in take effect without reopening chat.
     */
    internal fun observeModelState() {
        viewModelScope.launch {
            sdk.modelManager.state.collect { modelState ->
                // Loading a model that just landed is deliberate and guarded: the router is
                // idempotent, and answering keys on `engineLoaded`, so until this succeeds
                // messages keep being served from retrieval rather than failing.
                if (modelState is ModelState.Ready && sdk.localModelEnabled) {
                    inferenceRouter.initializeIfModelPresent()
                }
                if (modelState is ModelState.Corrupt) {
                    Log.e(
                        TAG,
                        "Model unusable: ${modelState.reason} " +
                            "(${modelState.onDiskBytes} of ${modelState.expectedBytes} bytes, " +
                            "canRetry=${modelState.canRetry})",
                    )
                }
                _uiState.update { current ->
                    (current as? ChatUiState.Ready)?.copy(
                        answerMode = resolveCurrentAnswerMode(),
                        modelDownload = modelState.toAiDownloadItemState(
                            damagedReason = damagedReasonFor(modelState),
                        ),
                        modelSizeBytes = expectedModelSizeBytes(modelState),
                        modelOnDiskBytes = sdk.modelManager.localModelSizeBytes(),
                        modelEligible = !sdk.isLowEndDevice,
                        modelEnabled = sdk.localModelPrefs.choice.value == LocalModelChoice.ENABLED,
                        // A model in any state at all answers the offer's question, so the
                        // card stops competing with the progress it would sit next to.
                        showModelOffer = shouldOfferModel(),
                    ) ?: current
                }
            }
        }
    }

    /**
     * Localized explanation for the states where the file exists but cannot be used. Empty for
     * every other state, which needs no prose.
     */
    private fun damagedReasonFor(state: ModelState): String = when {
        state is ModelState.Corrupt && state.canRetry ->
            localizedString(R.string.chat_model_file_damaged)
        // Past the re-download budget the card hides its action, so the wording must stop
        // pointing at a button that is no longer there.
        state is ModelState.Corrupt ->
            localizedString(R.string.chat_model_file_damaged_no_retry)
        state is ModelState.LoadFailed ->
            localizedString(R.string.chat_model_load_failed_transient)
        else -> ""
    }

    /**
     * What a complete model should weigh. [ModelState.Corrupt] carries the figure the manager
     * compared against, which is preferred over the background-resolved one so the card's two
     * numbers describe the same comparison.
     */
    private fun expectedModelSizeBytes(state: ModelState): Long? =
        (state as? ModelState.Corrupt)?.expectedBytes ?: aiSizeBytes.value

    /** Whether the dismissible model offer may be shown right now. */
    internal fun shouldOfferModel(): Boolean =
        sdk.localModelPrefs.shouldOfferModel(
            deviceEligible = !sdk.isLowEndDevice,
            networkAvailable = sdk.isNetworkAvailable(),
        )

    /**
     * Resolves how the next message would be answered, from the state as it stands now.
     *
     * Read at each point the answer could change — sending a message, the model landing, a
     * mode toggle — rather than cached, because connectivity and the engine both change
     * without notice and a stale mode would promise a pipeline that is no longer there.
     */
    internal fun resolveCurrentAnswerMode(): AnswerMode = resolveAnswerMode(
        AnswerModeInputs(
            preferOnline = preferOnline.value,
            networkAvailable = sdk.isNetworkAvailable(),
            deviceEligible = !sdk.isLowEndDevice,
            choice = sdk.localModelPrefs.choice.value,
            modelReady = sdk.modelManager.state.value is ModelState.Ready,
            engineLoaded = inferenceRouter.isModelAvailable,
        ),
    )

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
     * Open chat, and start the small on-device language packs alongside it.
     *
     * Unconditional: retrieval over the local index answers on any device with no network and
     * no model, so there is nothing here worth waiting for. The on-device model and the voice
     * pack both continue in the background and report themselves from inside chat — the model
     * through the mode bar, the voice pack through its own banner above the input.
     */
    internal suspend fun initializeModel() {
        _uiState.value = ChatUiState.Loading
        // Idempotent, and started before chat opens so a pack that is already close to done
        // can land during the first few seconds of the session.
        autoStartOnDevicePacks()
        refreshAiSizeLabel()
        loadReadyChat()
    }

    /**
     * Resolve the selected model's real download size in the background and patch it into a
     * live state when it lands. Fire-and-forget — the size is a label, so a failed lookup
     * leaves the catalog's approximate constant in place and nothing else changes.
     */
    private fun refreshAiSizeLabel() {
        // Nothing to label on hardware that will never be offered the model.
        if (sdk.isLowEndDevice) return
        viewModelScope.launch {
            val resolved = runCatching { sdk.modelManager.resolveModelSizeBytes() }.getOrNull()
                ?: return@launch
            if (aiSizeBytes.value == resolved) return@launch
            aiSizeBytes.value = resolved
            _uiState.update { current ->
                (current as? ChatUiState.Ready)?.copy(modelSizeBytes = resolved) ?: current
            }
        }
    }

    /**
     * Open chat: load history, and load the inference engine when the model is both consented
     * to and present.
     *
     * A failed engine load is not a failed chat. Retrieval answers either way, so the failure
     * is recorded, reported to the manager (which decides whether the file is worth keeping),
     * and the session opens in [AnswerMode.ON_DEVICE_DIRECT] — the CHW can still ask
     * questions. Only a history load that throws leaves chat genuinely unusable.
     */
    internal suspend fun loadReadyChat() {
        try {
            loadReadyChatInternal()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Anything escaping here would strand _uiState on Loading, where every later tap
            // is a silent no-op behind a permanent spinner. The exception text is a developer
            // artefact, so it stays in logcat.
            Log.e(TAG, "loadReadyChat failed unexpectedly: ${e.message}", e)
            _uiState.value = ChatUiState.Error(
                localizedString(R.string.chat_model_load_failed_transient),
            )
        }
    }

    private suspend fun loadReadyChatInternal() {
        _uiState.value = ChatUiState.Loading

        // Attempted only when the user has opted in and the file has passed validation. A
        // download in flight is deliberately excluded: the worker is writing into the very
        // file a load would map, and mid-transfer it is incomplete by definition.
        if (sdk.localModelEnabled && sdk.modelManager.state.value is ModelState.Ready) {
            loadInferenceEngine()
        }

        // History is keyed on CHW, not session — sessionId is fresh per VM (it drives
        // EventRecorder.sessionId for telemetry bucketing) and would always return an empty
        // list. See [ChatRepositoryImpl.getRecentHistory].
        val history = chatRepo.getRecentHistory(
            chwId = sdk.currentCHWId.orEmpty(),
            limit = HISTORY_LIMIT,
        )
        val mode = resolveCurrentAnswerMode()
        Log.i(TAG, "Chat open — answerMode=$mode lowEnd=${sdk.isLowEndDevice}")
        _uiState.value = ChatUiState.Ready(
            messages = history,
            answerMode = mode,
            modelDownload = sdk.modelManager.state.value.toAiDownloadItemState(
                damagedReason = damagedReasonFor(sdk.modelManager.state.value),
            ),
            modelSizeBytes = aiSizeBytes.value,
            modelOnDiskBytes = sdk.modelManager.localModelSizeBytes(),
            modelEligible = !sdk.isLowEndDevice,
            modelEnabled = sdk.localModelPrefs.choice.value == LocalModelChoice.ENABLED,
            showModelOffer = shouldOfferModel(),
            suggestedQuestions = loadSuggestions(),
        )
        backfillFaqTranslationsThenRefresh()
    }

    /**
     * Load the engine, retrying briefly. A model that has just finished downloading can
     * transiently fail to load while the file is still flushing, and a short backoff clears
     * that case before it is treated as a real failure.
     *
     * Failure is reported to the manager, which runs the structural check and decides between
     * keeping the file for a retry and deleting wrong bytes. Nothing here changes the UI: the
     * mode resolves to retrieval-only on its own once the engine is absent.
     */
    private suspend fun loadInferenceEngine() {
        var service = inferenceRouter.initializeIfModelPresent()
        var attempt = 1
        while (service == null && attempt < MODEL_LOAD_MAX_ATTEMPTS && sdk.modelManager.isModelPresent()) {
            Log.w(
                TAG,
                "Engine load returned null (attempt $attempt/$MODEL_LOAD_MAX_ATTEMPTS) — " +
                    "retrying in ${MODEL_LOAD_RETRY_DELAY_MS}ms",
            )
            delay(MODEL_LOAD_RETRY_DELAY_MS)
            service = inferenceRouter.initializeIfModelPresent()
            attempt++
        }
        if (service != null) return

        Log.e(
            TAG,
            "Engine failed to load after $attempt attempt(s), native cause: " +
                "${inferenceRouter.lastLoadError} — answering from retrieval only",
        )
        // A present file that no bundled engine can ever load is a configuration problem, not
        // a transient one; handing it to the manager would spend a corrupt-retry on bytes that
        // are fine. Retrieval-only is the outcome either way.
        if (sdk.modelManager.isModelPresent() && inferenceRouter.canRunResolvedModel()) {
            sdk.modelManager.onModelLoadFailed()
        }
    }

    /**
     * Retry opening chat after [ChatUiState.Error].
     *
     * Guards against re-entry: setting [ChatUiState.Loading] synchronously closes the window
     * where a second call could launch a concurrent engine load of the same model file,
     * which crashes the native engine.
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
            // Resolved per turn rather than read from the UI: connectivity and the engine can
            // both change between opening chat and pressing send.
            val mode = resolveCurrentAnswerMode()
            val connected = sdk.isNetworkAvailable()
            // The one line that tells you which pipeline actually answered. The mode bar shows
            // the user's *choice*; `mode` here is what that choice resolved to.
            Log.i(
                TRACE_TAG,
                "──── turn ──── mode=$mode pref=${if (preferOnline.value) "online" else "on-device"} " +
                    "net=$connected eligible=${!sdk.isLowEndDevice} " +
                    "consent=${sdk.localModelPrefs.choice.value} " +
                    "lang=${sdk.language} strictness=${config.chatScopeStrictness} " +
                    "modelLoaded=${inferenceRouter.isModelAvailable} " +
                    "moduleFamilyId=${moduleFamilyId ?: "∅"} q=\"${tracePreview(trimmed)}\"",
            )
            if (mode == AnswerMode.ONLINE) {
                val handled = handleBackendRagMessage(trimmed)
                if (handled) return@launch
                // Online, but the backend RAG call failed for an infrastructure reason
                // (network drop, non-2xx, timeout). Don't dead-end with "no response
                // available" — fall through to the on-device pipeline and answer from the
                // local index. (A 2xx blank answer is final inside handleBackendRagMessage
                // and never reaches here.)
                Log.i(TRACE_TAG, "backend-rag failed → on-device fallback")
            }
            // Re-resolved for the fallback: the online attempt above may have failed
            // precisely because connectivity dropped, and the on-device choice between
            // rewording and serving the card as written is unaffected by that.
            if (resolveOnDeviceMode() == AnswerMode.ON_DEVICE_ASSISTED) {
                handleLocalGemmaMessage(trimmed, moduleFamilyId, currentState)
            } else {
                handleRetrievalOnlyMessage(trimmed)
            }
        }
    }

    /**
     * Snapshot of `ConnectivityManager` state at telemetry-emission time. The
     * `"online"` / `"offline"` pair is the vocabulary every event family writes
     * to `network_state`, so the dashboard can compare across them.
     */
    internal fun currentNetworkState(): String =
        if (sdk.isNetworkAvailable()) "online" else "offline"

    /**
     * Which on-device pipeline would answer, ignoring connectivity and the online preference.
     *
     * Used for the fallback after a failed online attempt: [resolveCurrentAnswerMode] would
     * still report [AnswerMode.ONLINE] there, since the preference and the network are both
     * unchanged by the backend having failed.
     */
    internal fun resolveOnDeviceMode(): AnswerMode = resolveAnswerMode(
        AnswerModeInputs(
            preferOnline = false,
            networkAvailable = false,
            deviceEligible = !sdk.isLowEndDevice,
            choice = sdk.localModelPrefs.choice.value,
            modelReady = sdk.modelManager.state.value is ModelState.Ready,
            engineLoaded = inferenceRouter.isModelAvailable,
        ),
    )
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
    /**
     * Returns [chunks] with an English side, translating the Bengali one where it is missing.
     *
     * The model is always prompted in English — in Bangla mode the question is translated
     * first — while the corpus is Bengali-authored, so without this the reference block hands a
     * 270M model text it cannot read. It then ignores the references and answers from
     * pre-training, and [OutputValidator.groundednessScore] compares English output against
     * Bengali references, which is zero overlap by arithmetic rather than by quality. The floor
     * discards every answer and the served text is always the card verbatim.
     *
     * Translating here fixes both consumers at once, which is why the result must be passed to
     * the prompt builder *and* the groundedness check: translating only for the prompt leaves
     * the gate still comparing across languages, and nothing changes.
     *
     * A failed or unavailable translation returns the chunk untouched rather than storing the
     * passthrough, so a missing language pack behaves exactly as before instead of filling the
     * English fields with Bengali.
     */
    internal suspend fun readableGrounding(chunks: List<GroundingChunk>): List<GroundingChunk> {
        var freshlyTranslated = 0
        var cached = 0
        val result = chunks.map { chunk ->
            if (!chunk.bodyEn.isNullOrBlank() || chunk.bodyBn.isNullOrBlank()) return@map chunk
            readableGroundingCache[chunk.chunkId]?.let { cached++; return@map it }

            val body = sdk.translator.translateBnToEnResult(chunk.bodyBn)
            if (!body.translated) return@map chunk
            // Titles carry the topic words a rephrase is most likely to reuse, so they are
            // worth translating too; a failure there is not worth discarding the body for.
            val title = chunk.titleBn
                ?.let { sdk.translator.translateBnToEnResult(it) }
                ?.takeIf { it.translated }
                ?.text
            val readable = chunk.copy(titleEn = title ?: chunk.titleEn, bodyEn = body.text)
            cacheReadableGrounding(readable)
            freshlyTranslated++
            readable
        }
        // Reports how many chunks the model can actually READ, which is the number that
        // matters when judging an answer. Counting only fresh translations understated it:
        // cards recur across turns, so most chunks arrive from the cache already readable,
        // and a low count looked like a translation failure rather than a cache hit.
        val readable = result.count { !it.bodyEn.isNullOrBlank() }
        Log.i(
            TRACE_TAG,
            "grounding readable by the model: $readable of ${chunks.size} " +
                "(translated now=$freshlyTranslated, from cache=$cached)",
        )
        return result
    }

    /**
     * Remembers a translated chunk so recurring cards are not re-translated every turn, with a
     * bound so a long session cannot accumulate the whole corpus in memory.
     */
    private fun cacheReadableGrounding(chunk: GroundingChunk) {
        if (readableGroundingCache.size >= READABLE_GROUNDING_CACHE_MAX) {
            readableGroundingCache.keys.firstOrNull()?.let(readableGroundingCache::remove)
        }
        readableGroundingCache[chunk.chunkId] = chunk
    }

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
     * The clinician-authored text this chunk answers with: the linked quiz explanation when
     * it has one, else the card body clipped to a whole sentence.
     *
     * The single definition of "what this card says", so the retrieval-only path serves
     * exactly the text the model-backed path grounds on. Two definitions drifted apart —
     * one truncated mid-card and title-prefixed, the other whole — which meant the model
     * was rewording something the fallback would never have shown.
     */
    internal suspend fun servedCardText(chunk: GroundingChunk, isBangla: Boolean): String =
        resolveExplanation(chunk, isBangla) ?: clipToCompleteSentence(resolveCardBody(chunk, isBangla))

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
     * Tap-handler for a source-document chip. Launches
     * [com.medtroniclabs.microcoaching.ui.document.DocumentPreviewActivity] with
     * everything the cited ref knows, and that screen owns resolving the document
     * to a file and reporting its own unavailable / offline states.
     *
     * @param citedPage 1-indexed PDF page the citation points to — sourced from the
     *   BM25-matched card's `source_pages`. The viewer opens the WHOLE document and
     *   scrolls to this page: a citation is a starting point, and the CHW routinely
     *   needs the surrounding pages to act on it. Null falls back to page 1; ignored
     *   entirely for image / external formats.
     */
    fun openSourceDocument(sourceDocumentId: String, fallbackTitle: String, citedPage: Int? = null) {
        if (sourceDocumentId.isBlank()) return
        // The cited ref carries the URL and storage path the preview needs for a
        // document that is in no synced catalogue — a document linked to no
        // published module and assigned to nobody reaches neither, so without
        // these the preview has nothing to download.
        val ref = (_uiState.value as? ChatUiState.Ready)?.messages
            ?.flatMap { it.sourceDocuments }
            ?.firstOrNull { it.id == sourceDocumentId }
        viewModelScope.launch {
            DocumentPreviewActivity.start(
                context = getApplication<android.app.Application>(),
                sourceDocumentId = sourceDocumentId,
                title = fallbackTitle,
                originalFilename = ref?.originalFilename,
                startPage = citedPage,
                presignedUrl = ref?.presignedUrl,
                storagePath = ref?.storagePath,
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

    /**
     * Id of the message whose read-aloud was last started, or null. Paired with
     * [CoachingTtsHelper.state] rather than trusted alone: the engine reports
     * completion and failure through its own state, so a lingering id here is
     * harmless once that state leaves [TtsState.Speaking].
     */
    private val _speakingMessageId = MutableStateFlow<Long?>(null)
    val speakingMessageId: StateFlow<Long?> = _speakingMessageId.asStateFlow()

    /** True when [messageId] is the message currently being read aloud. */
    fun isSpeaking(messageId: Long): Boolean =
        tts.state.value is TtsState.Speaking && _speakingMessageId.value == messageId

    /**
     * Read [messageId] aloud, or stop it if it is already playing — the speaker
     * button is the same control for both, so a long answer can be cut short
     * without waiting it out. Tapping a different message switches to it, since
     * [CoachingTtsHelper.speak] flushes the queue.
     */
    fun toggleSpeak(messageId: Long, text: String) {
        if (isSpeaking(messageId)) {
            stopSpeaking()
            return
        }
        _speakingMessageId.value = messageId
        tts.speak(text) {
            // Guard against clearing a newer utterance's id if this one finishes late.
            if (_speakingMessageId.value == messageId) _speakingMessageId.value = null
        }
    }

    fun stopSpeaking() {
        _speakingMessageId.value = null
        tts.stop()
    }

    /**
     * Opt into the on-device model and start fetching it.
     *
     * Records consent before scheduling, so a download that survives process death is still
     * backed by a stored choice when it lands.
     */
    fun enableLocalModel() {
        if (sdk.isLowEndDevice) {
            Log.i(TAG, "enableLocalModel ignored — device is not eligible for the on-device model")
            return
        }
        sdk.localModelPrefs.setChoice(LocalModelChoice.ENABLED)

        // A preserved file needs no download — but it does need the engine loaded, and that
        // cannot be left to [observeModelState]. Opting out unloads the engine without
        // touching ModelState, so the model is still Ready here; re-assigning Ready is a
        // no-op emission (MutableStateFlow conflates equal values), the observer never runs,
        // and answering silently stays retrieval-only. Loading explicitly is what makes
        // re-opting-in work without deleting and refetching 304 MB.
        if (sdk.modelManager.state.value is ModelState.Ready) {
            Log.i(TRACE_TAG, "enableLocalModel: model already on disk — loading engine directly")
            viewModelScope.launch {
                loadInferenceEngine()
                refreshModelUi()
            }
            return
        }
        requestModelDownload()
    }

    /**
     * Opt out of the on-device model, optionally reclaiming its file.
     *
     * Order matters and is not interchangeable: consent is withdrawn first so no concurrent
     * path can re-load the model, then the engine is unloaded, and only then is the file
     * eligible for deletion. Deleting while the engine holds the file mapped is a native
     * crash, and unloading before withdrawing consent leaves a window where an in-flight
     * message reloads it.
     *
     * @param deleteFile true to remove the download and reclaim the space. When false the file
     *   is kept, so opting back in costs nothing and needs no network.
     */
    fun disableLocalModel(deleteFile: Boolean) {
        Log.i(TAG, "disableLocalModel(deleteFile=$deleteFile)")
        sdk.localModelPrefs.setChoice(LocalModelChoice.DISABLED)
        SharedInferenceRouter.forceUnload()
        if (deleteFile) {
            deleteLocalModel()
        } else {
            refreshModelUi()
        }
    }

    /**
     * Delete the model file and reclaim its space, leaving the stored choice untouched.
     *
     * Cancels an in-flight transfer first: without that, the worker would keep writing and
     * recreate the file moments after it was removed.
     */
    fun deleteLocalModel() {
        SharedInferenceRouter.forceUnload()
        if (sdk.modelManager.state.value.isTransferInFlight()) {
            sdk.modelManager.cancelDownload()
        }
        sdk.modelManager.deleteModelForUserOptOut()
        refreshModelUi()
    }

    /** Dismiss the model offer without deciding, leaving it available in the answering sheet. */
    fun dismissModelOffer() {
        sdk.localModelPrefs.recordOfferDismissed()
        refreshModelUi()
    }

    /**
     * Re-project the model's state into the UI after a change the manager's own StateFlow does
     * not announce — consent and offer dismissals are stored separately from [ModelState].
     */
    private fun refreshModelUi() {
        val modelState = sdk.modelManager.state.value
        _uiState.update { current ->
            (current as? ChatUiState.Ready)?.copy(
                answerMode = resolveCurrentAnswerMode(),
                modelDownload = modelState.toAiDownloadItemState(damagedReasonFor(modelState)),
                modelOnDiskBytes = sdk.modelManager.localModelSizeBytes(),
                modelEnabled = sdk.localModelPrefs.choice.value == LocalModelChoice.ENABLED,
                showModelOffer = shouldOfferModel(),
            ) ?: current
        }
    }

    /**
     * Start or retry the download for a user who has already opted in.
     *
     * The manager can decline — most notably once the corrupt-file re-download budget is spent
     * — so the UI is refreshed from its state rather than optimistically showing progress for
     * work nobody is doing.
     */
    fun requestModelDownload() {
        if (sdk.isLowEndDevice) {
            Log.i(TAG, "requestModelDownload ignored — device is not eligible for the on-device model")
            return
        }
        sdk.modelManager.triggerDownload()
        if (!sdk.modelManager.state.value.isTransferInFlight()) {
            Log.i(TAG, "requestModelDownload: manager did not start a download — leaving UI as-is")
        }
        refreshModelUi()
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
         * Cap on [readableGroundingCache]. Three chunks per turn means this holds roughly the
         * last twenty turns' worth of cards, well past the point where a session stops seeing
         * new ones.
         */
        private const val READABLE_GROUNDING_CACHE_MAX = 64

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
         * Escape sequences the model sometimes emits as literal characters rather than as the
         * whitespace they denote — a backslash followed by `n`, `r` or `t`, which arrives
         * looking like `\n` in the middle of a sentence.
         */
        private val LITERAL_ESCAPE = Regex("""\\[nrt]""")

        /** A complete reasoning block, including a multi-line body. */
        private val THINK_SPAN = Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE)

        /** Opening tag of a reasoning block generation never closed. */
        private const val THINK_OPEN = "<think>"

        /**
         * A leading clause that describes the prompt rather than answering the question,
         * in either of the two shapes a model reaches for: an "according to X," preface,
         * or "the X states that". Anchored at the start; nothing mid-answer is touched.
         */
        private val SOURCE_PREAMBLE = Regex(
            """^(?:(?:based on|according to|as per|as stated in|from|per)\s+(?:the\s+)?""" +
                """(?:context|information|text|reference|references|passage|card|above)[^,.:;]{0,40}[,:;]\s*""" +
                """|(?:the\s+)?(?:context|information|text|reference|passage|card)\s+""" +
                """(?:above\s+)?(?:mentions|states|says|provides|indicates|shows)\s+that\s+)""",
            RegexOption.IGNORE_CASE,
        )

        /** Below this, what is left after stripping is not an answer — keep the original. */
        private const val MIN_ANSWER_AFTER_STRIP = 20

        /**
         * Turn escape sequences the model wrote as text into real whitespace, then tidy it.
         *
         * The model has seen plenty of JSON and escaped source in pre-training and sometimes
         * reproduces `\n` as two characters. Trimming cannot remove that — a backslash is not
         * whitespace — so it reaches the CHW as visible punctuation at the start of an answer.
         *
         * Runs before the sentence-completeness trim, since a leading literal escape would
         * otherwise be counted as content.
         */
        internal fun normalizeModelWhitespace(text: String): String =
            LITERAL_ESCAPE.replace(text, " ")
                // Collapse the runs the substitution can leave behind, and any the model
                // produced itself, without joining separate paragraphs into one line.
                .replace(Regex("[ \\t]{2,}"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .lines().joinToString("\n") { it.trim() }
                .trim()

        /**
         * Remove a reasoning model's `<think>…</think>` spans, and an unclosed `<think>`
         * along with everything after it.
         *
         * Thinking is disabled at the engine ([LiteRtLmService.enableThinking]), so this
         * should find nothing; it exists because a bundle whose chat template ignores that
         * would otherwise show the CHW the model deliberating as if it were clinical advice.
         * An unclosed span means generation stopped inside the reasoning block and there is
         * no answer to salvage — dropping the remainder routes the turn to the
         * empty-response path rather than serving half a thought.
         */
        internal fun stripThinkSpans(text: String): String =
            THINK_SPAN.replace(text, "")
                .substringBefore(THINK_OPEN)
                .trim()

        /**
         * Drop a leading clause that talks *about* the prompt instead of answering — "The
         * context mentions that…", "Based on the information provided,…".
         *
         * The prompt asks for a direct answer and gives the card text no label to quote, so
         * this should rarely fire; a small model reaches for the phrasing anyway, and a CHW
         * being told what "the context" says is both jarring and useless — the source is
         * shown as an attribution chip beside the answer. Conservative by construction: it
         * strips only a recognised opener, only at the start, and only when a substantial
         * answer remains, so a sentence that genuinely reports what the card does *not*
         * cover survives intact.
         */
        internal fun stripSourcePreamble(text: String): String {
            val stripped = SOURCE_PREAMBLE.replace(text.trimStart(), "")
            if (stripped.length < MIN_ANSWER_AFTER_STRIP || stripped == text.trimStart()) return text.trim()
            return stripped.replaceFirstChar { it.uppercaseChar() }.trim()
        }

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
