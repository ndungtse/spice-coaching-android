package com.medtroniclabs.microcoaching.ui.learn

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ai.voice.CoachingTtsHelper
import com.medtroniclabs.microcoaching.ai.voice.localeForSpokenText
import com.medtroniclabs.microcoaching.ai.voice.ttsLocaleFor
import com.medtroniclabs.microcoaching.data.repository.ModuleRepository
import com.medtroniclabs.microcoaching.data.repository.ModuleRepositoryImpl
import com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import com.medtroniclabs.microcoaching.sync.SyncDomain
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.medtroniclabs.microcoaching.ui.common.SectionState

/**
 * ViewModel for the v3 module → lesson → quiz → result flow.
 *
 * Module-only. The scenario-cache fallback was removed in 0.3.0; all rendering
 * now comes from `module_cache` rows synced via `/sync/modules`. Per-CHW
 * completion state is held in memory for the session and also persisted:
 * `finishQuiz` calls `sdk.onModuleQuizCompleted`, which upserts a
 * [com.medtroniclabs.microcoaching.data.db.entity.ChwModuleCompletionEntity] row.
 *
 * @param chwId The CHW currently using the app. Defaults to "unknown_chw" so
 *   the flow never crashes when SPICE hasn't supplied the ID yet.
 */
class LearnViewModel(
    private val context: Context,
    private val chwId: String,
    private val moduleRepo: ModuleRepository,
    internal val telemetry: EventRecorder,
) : ViewModel() {

    internal val _uiState = MutableStateFlow<LearnUiState>(LearnUiState.Loading)
    val uiState: StateFlow<LearnUiState> = _uiState.asStateFlow()

    /**
     * Knowledge section (document list + download/preview). Extracted to
     * [KnowledgeDocController]; the four flows below are passthroughs so existing
     * collectors (CoachingNavGraph, the modules screens) keep reading
     * `learnVm.knowledgeDocuments` / `cachedDocIds` / `docEvents` /
     * `downloadProgress` unchanged. Uses [viewModelScope] so an in-flight
     * download cancels with the screen.
     */
    private val knowledgeDocs = KnowledgeDocController(viewModelScope, context)
    val knowledgeDocuments: StateFlow<List<KnowledgeDocument>> get() = knowledgeDocs.knowledgeDocuments

    /** Knowledge docs + the outcome of the pull that fills them — see [KnowledgeDocController.documentsState]. */
    val knowledgeState: StateFlow<SectionState<List<KnowledgeDocument>>> get() = knowledgeDocs.documentsState

    val cachedDocIds: StateFlow<Set<String>> get() = knowledgeDocs.cachedDocIds
    val docEvents: SharedFlow<DocEvent> get() = knowledgeDocs.docEvents
    val downloadProgress: StateFlow<DownloadProgress?> get() = knowledgeDocs.downloadProgress

    /**
     * Categorised module lists + the shared featured pick, projected from the
     * SDK-owned [com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore]
     * so the modules screen and the home card consume the same source of truth.
     */
    val refresherModules: StateFlow<List<LearnModule>>
        get() = MicroCoachingSDK.getInstance().coachingModuleStore.refresherModules
    val trainingModules: StateFlow<List<LearnModule>>
        get() = MicroCoachingSDK.getInstance().coachingModuleStore.trainingModules
    val selectedMorningCard: StateFlow<LearnModule?>
        get() = MicroCoachingSDK.getInstance().coachingModuleStore.selectedMorningCard

    /** Questions for the currently active module — loaded when the quiz starts. */
    internal var activeQuestions: List<QuizQuestion> = emptyList()

    /** The module the CHW is currently working through. */
    internal var activeModule: LearnModule? = null

    /**
     * Last-known mapped module list from [observeModules]. Used by [popToModuleList] to
     * restore the [LearnUiState.ModuleList] state without re-running [initialise] and
     * showing a Loading spinner (back-navigation should feel instant).
     */
    private var lastKnownModules: List<LearnModule> = emptyList()

    /** True when the CHW entered the quiz via [startCourse] (lesson-player path). Used by
     *  [CoachingNavGraph] to decide whether "Try Again" should restart the full course. */
    var startedViaCourse: Boolean = false
        internal set

    /** True when the CHW entered the quiz via [selectModuleForQuiz] from the refresher list.
     *  Used by [QuizResultScreen] to show "Back to Refreshers" instead of "More Modules". */
    var startedViaRefresher: Boolean = false
        internal set

    internal var _quizCorrectCount = 0
    internal var _quizTotalCount = 0

    // ── Listen-aloud (TTS) ────────────────────────────────────────────────────

    /** Speaks lesson card bodies; [speakAloud] picks the voice per utterance. */
    private val tts: CoachingTtsHelper = CoachingTtsHelper(context, defaultTtsLocale())

    private val _autoSpeakEnabled = MutableStateFlow(false)
    /**
     * When `true`, [com.medtroniclabs.microcoaching.ui.learn.LessonPlayerScreen]
     * auto-plays each card and auto-advances on TTS completion. Toggled by the
     * "Listen" button on [com.medtroniclabs.microcoaching.ui.learn.ModuleDetailScreen]
     * and the speaker icon in the lesson player header.
     */
    val autoSpeakEnabled: StateFlow<Boolean> = _autoSpeakEnabled.asStateFlow()

    fun toggleAutoSpeak() {
        val next = !_autoSpeakEnabled.value
        _autoSpeakEnabled.value = next
        if (!next) tts.stop()
    }

    /**
     * Speak [text] aloud in the voice matching the script it is written in, so a card
     * served in the other language (see `LocalizedText.forLang`) is still read by the
     * right voice. [onDone] fires when the utterance completes.
     */
    fun speakAloud(text: String, onDone: () -> Unit = {}) {
        tts.speak(text, localeForSpokenText(text, defaultTtsLocale()), onDone)
    }

    /** Stop any in-flight TTS utterance. */
    fun stopSpeaking() {
        tts.stop()
    }

    init {
        viewModelScope.launch { initialise() }
    }

    override fun onCleared() {
        tts.release()
        super.onCleared()
    }

    /**
     * Voice used when a string's own script can't decide (digits, punctuation).
     * Reads `sdk.language` rather than `config.language` so a runtime
     * [MicroCoachingSDK.setLanguage] is honoured, matching how `SdkLocalizedTheme`
     * resolves strings.
     */
    private fun defaultTtsLocale(): Locale =
        ttsLocaleFor(MicroCoachingSDK.getInstance().language)

    // ── Init ──────────────────────────────────────────────────────────────────

    private suspend fun initialise() {
        try {
            val sdk = MicroCoachingSDK.getInstance()
            val moduleCount = moduleRepo.countActive()
            if (moduleCount == 0 && sdk.config.backendUrl.isNotBlank()) {
                Log.i(TAG, "module_cache empty on Learn open — triggering inbound sync.")
                sdk.syncCoordinator.triggerNow()
            }
            observeModules(sdk)
        } catch (e: Exception) {
            Log.e(TAG, "Initialisation failed: ${e.message}", e)
            _uiState.value = LearnUiState.Error(localized(R.string.learn_error_failed_load))
        }
    }

    private suspend fun observeModules(sdk: MicroCoachingSDK) {
        // Project the mapped list from the SDK-owned store (the single source of
        // truth, shared with the home card). The store already reacts to module-
        // cache changes, coaching_event inserts, and morning-card refreshes.
        // Combine with the raw module-cache flow so the empty/error decision stays
        // cache-first: cached rows are always shown; the empty-state message fires
        // only when the cache is genuinely empty. Knowledge docs are owned by
        // [KnowledgeDocController], which observes the module cache itself.
        combine(
            sdk.coachingModuleStore.allModules,
            moduleRepo.getAllActive(),
        ) { mapped, rawRows -> mapped to rawRows }
            .collectLatest { (mapped, rawRows) ->
                // Cache the latest mapped list for fast pop-back (avoid Loading flash).
                lastKnownModules = mapped

                // Only push state if we're currently in a list-viewing state. If the
                // CHW is deep in the flow (LessonContent, QuizInProgress, QuizResult),
                // a Flow emission here would otherwise override their state and cause
                // blank / spinner screens on ModuleDetailScreen, LessonPlayerScreen.
                val current = _uiState.value
                val isListState = current is LearnUiState.Loading ||
                    current is LearnUiState.ModuleList ||
                    current is LearnUiState.Error
                if (isListState) {
                    if (rawRows.isNotEmpty() && mapped.isEmpty()) {
                        Log.w(
                            TAG,
                            "module_cache has ${rawRows.size} row(s) but the store mapped 0 — " +
                                "data issue, not connectivity.",
                        )
                    }
                    // An empty catalogue is NOT an error — it is an empty list. Emitting
                    // Error here made CoachingTab blank Training/Knowledge too, which read
                    // entirely different tables. Emptiness is now a per-section concern.
                    _uiState.value = LearnUiState.ModuleList(mapped)
                }
            }
    }

    /**
     * Re-triggers inbound sync from the Learn screen. Flips the state to
     * [LearnUiState.Loading] so the CHW sees immediate feedback; the running
     * `observeModules` collector will replace it with [ModuleList] the moment new
     * modules land in Room. If the sync fails silently (no Room change, no flow
     * re-emit), the watchdog releases the spinner after a few seconds so the CHW
     * isn't stuck on it — an empty result lands as an empty list, not an error.
     */
    fun retrySync() {
        val sdk = MicroCoachingSDK.getInstance()
        if (sdk.config.backendUrl.isBlank()) return // no backend → retry is a no-op
        _uiState.value = LearnUiState.Loading
        sdk.syncCoordinator.triggerNow()
        viewModelScope.launch {
            delay(5_000)
            if (_uiState.value is LearnUiState.Loading) {
                _uiState.value = LearnUiState.ModuleList(lastKnownModules)
            }
        }
    }

    internal fun localized(@androidx.annotation.StringRes resId: Int): String {
        val sdkLanguage = MicroCoachingSDK.getInstance().language
        val ctx = com.medtroniclabs.microcoaching.ui.SdkLocaleHelper.wrap(context, sdkLanguage)
        return ctx.getString(resId)
    }

    // ── Knowledge documents (delegated to KnowledgeDocController) ───────────────

    /** Download/stream + preview a Knowledge document — see [KnowledgeDocController]. */
    fun openKnowledgeDocument(doc: KnowledgeDocument) = knowledgeDocs.openKnowledgeDocument(doc)

    // ── Navigation transitions ────────────────────────────────────────────────

    /** SDK language as the "en"/"bn" code [parseInlineQuiz] expects. */
    private fun langCode(): String =
        if (MicroCoachingSDK.getInstance().config.language == Language.ENGLISH) "en" else "bn"

    /**
     * The [LearnModule]s that back the list come from [CoachingModuleStore] SLIM —
     * `cardsJson == "[]"`, `inlineQuestions == null` — so the eagerly-held catalogue
     * doesn't retain every module's heavy blobs (audit MEM-08). Detail / lesson /
     * quiz need the blobs, so re-read the entity by id on tap and re-attach them.
     * Cheap: one indexed lookup (by `module_id`, else latest by family) + a parse,
     * off the main thread. Returns [module] unchanged when it's already hydrated
     * (e.g. the refresher path, which builds full modules from the DB) or when the
     * entity is gone (pruned) — detail then renders from counts without crashing.
     */
    internal suspend fun hydrate(module: LearnModule): LearnModule {
        val alreadyFull = module.cardsJson != "[]" || module.inlineQuestions != null
        if (alreadyFull) return module
        val entity = module.moduleId?.let { moduleRepo.getById(it) }
            ?: moduleRepo.getByFamilyId(module.moduleFamilyId)
            ?: return module
        val questions = parseInlineQuiz(entity.quizJson, langCode())
        return module.copy(
            cardsJson = entity.cardsJson,
            inlineQuestions = questions.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * The module that follows [current] in the same list it belongs to — training
     * first, then refresher — or null when it's the last one. Returned in SLIM
     * (list) form; the caller re-runs the normal [selectModule] + [startLesson]
     * hydration path, exactly as a tap from the modules list would. Drives the
     * "Continue" action on the cards-completion screen.
     */
    fun nextModuleAfter(current: LearnModule): LearnModule? {
        fun nextIn(list: List<LearnModule>): LearnModule? {
            val idx = list.indexOfFirst { it.moduleFamilyId == current.moduleFamilyId }
            return if (idx >= 0 && idx + 1 < list.size) list[idx + 1] else null
        }
        return nextIn(trainingModules.value) ?: nextIn(refresherModules.value)
    }

    fun selectModule(module: LearnModule) {
        activeModule = module
        // Don't downgrade a completed module to "in_progress" just because the
        // CHW opened it again (revisit-from-KnowledgeRow). The persisted DB
        // status from `chw_module_completion.latestAttemptPassed = true` would
        // be overridden by this in-memory map and the module would jump back
        // to the Training row.
        if (module.status != "completed") {
            MicroCoachingSDK.getInstance().coachingModuleStore
                .setInSessionStatus(module.moduleFamilyId, "in_progress")
        }
        _uiState.value = LearnUiState.ModuleReady(module)
        viewModelScope.launch {
            // Surfacing a module to the CHW maps to backend `module_delivered`.
            telemetry.recordCoachingEvent(
                eventType = "module_delivered",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                cardFamilyId = module.cardFamilyId,
            )
        }
    }

    /**
     * Restores state to [LearnUiState.LessonContent] without emitting telemetry.
     * Called when the CHW presses back from the quiz to return to module detail.
     */
    fun restoreModuleDetail() {
        val module = activeModule ?: return
        _uiState.value = LearnUiState.LessonContent(module)
    }

    /**
     * Lightweight back-navigation reset: restores [LearnUiState.ModuleList] using the
     * last-known mapped list cached by [observeModules]. Used everywhere the user
     * is returning to the module list — both popping back from [ModuleDetailScreen]
     * and finishing a quiz via "Next Module" on [QuizResultScreen].
     *
     * When the cache is empty (e.g. back before first observeModules emission)
     * falls back to [LearnUiState.Loading] + [initialise] so the screen shows a
     * spinner instead of going blank.
     */
    fun popToModuleList() {
        activeModule = null
        activeQuestions = emptyList()
        startedViaCourse = false
        startedViaRefresher = false
        val cached = lastKnownModules
        _uiState.value = if (cached.isNotEmpty()) {
            LearnUiState.ModuleList(cached)
        } else {
            // No cache yet (e.g., back tapped before first observeModules emission) —
            // fall back to the full re-init path which shows a centered spinner.
            LearnUiState.Loading
        }
        if (cached.isEmpty()) {
            viewModelScope.launch { initialise() }
        }
    }

    /**
     * Force a fresh DB read + remap and transition the UI back to
     * [LearnUiState.ModuleList] regardless of current state. Called from the
     * refresher bottom-sheet dismiss path — the previous "re-emit cache only
     * when state is already ModuleList" implementation silently dropped the
     * refresh when the state was `QuizResult` (the post-quiz state when the
     * sheet dismisses), leaving stale refresher / banner / morning-card UI.
     *
     * Reads the latest mapped list straight from the SDK store (which already
     * reflects the freshly-synced progress, since the dismiss path triggers a
     * coaching_event / refilter that the store reacts to) so the result matches
     * what the live [observeModules] Flow would produce on its next tick.
     */
    fun refreshModuleCounts() {
        viewModelScope.launch {
            val sdk = MicroCoachingSDK.getInstance()
            // Nudge the store to re-read morning_card_cache + progress, then take
            // its current snapshot for an instant restore (no Loading flash).
            sdk.coachingModuleStore.invalidate()
            val mapped = sdk.coachingModuleStore.allModules.value
            lastKnownModules = mapped
            _uiState.value = LearnUiState.ModuleList(mapped)
        }
    }

    /**
     * Marks the active module as in-progress when the CHW taps "Start Course".
     * Navigation to [LessonPlayerScreen] is handled by [CoachingNavGraph].
     *
     * Skips the in-progress flip for completed modules — the "Read course"
     * CTA on a completed module goes through this same lesson-player surface
     * but must not downgrade the persisted pass state in the in-memory map.
     */
    fun startCourse() {
        val module = activeModule ?: return
        if (module.status != "completed") {
            MicroCoachingSDK.getInstance().coachingModuleStore
                .setInSessionStatus(module.moduleFamilyId, "in_progress")
        }
        startedViaCourse = true
    }

    /**
     * Parses the active module's `cardsJson` into a typed [LessonCard] list.
     * Used by [CoachingNavGraph] to supply cards to [LessonPlayerScreen].
     */
    fun getCurrentCards(): List<LessonCard> =
        parseLessonCards(activeModule?.cardsJson ?: "[]")

    /**
     * Emits a `module_card_viewed` telemetry event when the CHW views a card
     * in [LessonPlayerScreen]. Called via `LaunchedEffect(currentIndex)`.
     *
     * The card's own id rides along, which is what makes "how much of this module
     * has been read" answerable: without it every card in a module is an
     * indistinguishable row, so re-reading one card looks the same as reading
     * several. A card with no id is still recorded — it just can't be counted.
     */
    fun recordCardShown(cardIndex: Int) {
        val module = activeModule ?: return
        val cardId = getCurrentCards().getOrNull(cardIndex)?.cardFamilyId
        viewModelScope.launch {
            telemetry.recordCoachingEvent(
                eventType = "module_card_viewed",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                cardFamilyId = cardId,
            )
        }
    }

    /**
     * Complete a module that has no quiz, having reached the end of its cards.
     *
     * Only quiz-less modules go through here — one with questions must still be
     * answered, and completing it on cards alone would let a CHW skip the
     * assessment. Records the same `module_completed` event the quiz path emits so
     * the server converges on the same state the device just wrote.
     */
    fun onLessonCardsFinished() {
        val module = activeModule ?: return
        if (module.questionCount > 0) return
        viewModelScope.launch {
            val sdk = MicroCoachingSDK.getInstance()
            sdk.onModuleCardsCompleted(module.moduleFamilyId, module.moduleId)
            telemetry.recordCoachingEvent(
                eventType = "module_completed",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
            )
            // Finishing a module is a milestone worth reporting now rather than at
            // the next periodic tick
            sdk.flushTelemetryNow()
        }
    }

    fun startLesson() {
        val module = activeModule ?: return
        // Emit immediately so the detail header (title/thumbnail/CTAs, all present
        // on the slim model) renders without delay; the card list fills in once
        // the blobs are hydrated a moment later.
        _uiState.value = LearnUiState.LessonContent(module)
        viewModelScope.launch {
            val full = hydrate(module)
            activeModule = full
            // Upgrade the state to the hydrated module only if the CHW is still on
            // this module's lesson content (they may have navigated away).
            (_uiState.value as? LearnUiState.LessonContent)
                ?.takeIf { it.module.moduleFamilyId == module.moduleFamilyId }
                ?.let { _uiState.value = LearnUiState.LessonContent(full) }
            // Recording the first card view as the CHW enters the lesson body.
            telemetry.recordCoachingEvent(
                eventType = "module_card_viewed",
                clinicalDomain = full.clinicalDomain,
                cardType = "info",
                moduleFamilyId = full.moduleFamilyId,
                moduleId = full.moduleId,
                moduleVersion = full.moduleVersion,
                cardFamilyId = full.cardFamilyId,
            )
        }
    }

    // ── Companion / Factory ───────────────────────────────────────────────────

    companion object {
        internal const val TAG = "LearnViewModel"

        fun factory(context: Context, chwId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val sdk = MicroCoachingSDK.getInstance()
                    val db = sdk.database
                    val appCtx = context.applicationContext
                    // Preserve the exact value recordEvent historically wrote to
                    // the `sdk_version` column: the SPICE host app's versionName.
                    val appVersion = runCatching {
                        appCtx.packageManager
                            .getPackageInfo(appCtx.packageName, 0)
                            .versionName
                    }.getOrNull() ?: "0.0"
                    return LearnViewModel(
                        context = appCtx,
                        chwId = chwId,
                        moduleRepo = ModuleRepositoryImpl(db.moduleDao(), db.behaviouralGapDao()),
                        telemetry = EventRecorder(
                            dao = db.coachingEventDao(),
                            sessionId = sdk.coachingSessionId,
                            chwId = chwId,
                            appVersionName = appVersion,
                        ),
                    ) as T
                }
            }
    }
}
