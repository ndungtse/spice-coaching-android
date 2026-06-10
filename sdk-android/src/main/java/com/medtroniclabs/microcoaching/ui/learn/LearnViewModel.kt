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
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.mapper.parseIsoMillis
import com.medtroniclabs.microcoaching.data.repository.GapProfileRepository
import com.medtroniclabs.microcoaching.data.repository.GapProfileRepositoryImpl
import com.medtroniclabs.microcoaching.data.repository.ModuleRepository
import com.medtroniclabs.microcoaching.data.repository.ModuleRepositoryImpl
import com.medtroniclabs.microcoaching.domain.telemetry.eventFamilyFor
import com.medtroniclabs.microcoaching.progress.toReinforceQuestionIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.UUID

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
    private val gapRepo: GapProfileRepository,
    private val moduleRepo: ModuleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LearnUiState>(LearnUiState.Loading)
    val uiState: StateFlow<LearnUiState> = _uiState.asStateFlow()

    /** Questions for the currently active module — loaded when the quiz starts. */
    private var activeQuestions: List<QuizQuestion> = emptyList()

    /** The module the CHW is currently working through. */
    private var activeModule: LearnModule? = null

    /** In-memory module status map: moduleFamilyId → "assigned"|"in_progress"|"completed". */
    private val statusByModule: MutableMap<String, String> = mutableMapOf()

    /**
     * Last-known mapped module list from [observeModules]. Used by [popToModuleList] to
     * restore the [LearnUiState.ModuleList] state without re-running [initialise] and
     * showing a Loading spinner (back-navigation should feel instant).
     */
    private var lastKnownModules: List<LearnModule> = emptyList()

    /** True when the CHW entered the quiz via [startCourse] (lesson-player path). Used by
     *  [CoachingNavGraph] to decide whether "Try Again" should restart the full course. */
    var startedViaCourse: Boolean = false
        private set

    /** True when the CHW entered the quiz via [selectModuleForQuiz] from the refresher list.
     *  Used by [QuizResultScreen] to show "Back to Refreshers" instead of "More Modules". */
    var startedViaRefresher: Boolean = false
        private set

    private var _quizCorrectCount = 0
    private var _quizTotalCount = 0

    // ── Listen-aloud (TTS) ────────────────────────────────────────────────────

    /** Speaks lesson card bodies in the SDK's current language. */
    private val tts: CoachingTtsHelper = CoachingTtsHelper(context, ttsLocaleForSdkLanguage())

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

    /** Speak [text] aloud; [onDone] fires when the utterance completes. */
    fun speakAloud(text: String, onDone: () -> Unit = {}) {
        tts.speak(text, onDone)
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

    private fun ttsLocaleForSdkLanguage(): Locale = when (MicroCoachingSDK.getInstance().config.language) {
        Language.ENGLISH -> Locale.US
        Language.BANGLA -> Locale("bn", "BD")
    }

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
        // Combine modules + coaching_event count flows so observeModules re-emits
        // whenever a module_quiz_attempted row is written (refresher tile counts
        // update live).
        combine(
            moduleRepo.getAllActive(),
            sdk.database.coachingEventDao().getEventCountFlow(),
        ) { modules, _ -> modules }.collectLatest { modules ->
            val mapped = mapModules(modules, sdk)

            // Cache the latest mapped list for fast pop-back (avoid the Loading flash).
            lastKnownModules = mapped

            // Only push state if we're currently in a list-viewing state. If the CHW
            // is deep in the flow (LessonContent, QuizInProgress, QuizResult), a Flow
            // emission here would otherwise override their state and cause blank /
            // spinner screens on ModuleDetailScreen, LessonPlayerScreen, etc.
            val current = _uiState.value
            val isListState = current is LearnUiState.Loading ||
                current is LearnUiState.ModuleList ||
                current is LearnUiState.Error
            if (isListState) {
                // Cache-first: if `module_cache` has any rows, we show them — full
                // stop. Network errors, sync failures, and mapping drops never
                // override locally-cached content. The empty-state message only
                // fires when the cache is genuinely empty.
                _uiState.value = if (modules.isEmpty()) {
                    LearnUiState.Error(emptyMessage(sdk))
                } else {
                    if (mapped.isEmpty()) {
                        Log.w(
                            TAG,
                            "module_cache has ${modules.size} row(s) but mapModules returned 0 — " +
                                "data issue, not a connectivity issue. Showing empty ModuleList " +
                                "instead of flipping to Error.",
                        )
                    }
                    LearnUiState.ModuleList(mapped)
                }
            }
        }
    }

    /**
     * Pure mapping from active [ModuleEntity] rows to enriched [LearnModule]
     * tiles. Reads the latest gap profile, morning-card cache, full + partial
     * completion rows, and merges them into the per-module status / progress
     * fraction / "to reinforce" count.
     *
     * Used by both the live [observeModules] Flow collector and the one-shot
     * [refreshModuleCounts] called from the refresher bottom sheet dismiss
     * path — so the two stay in lock-step instead of diverging.
     */
    private suspend fun mapModules(
        modules: List<ModuleEntity>,
        sdk: MicroCoachingSDK,
    ): List<LearnModule> {
        val gapEntries = gapRepo.getAllForChw(chwId)
        val activeGapKeys = gapEntries.filter { it.gapActive }.map { it.behaviouralGapId }.toSet()

        // Morning-card enrichment (source / gap id for refresher list + telemetry).
        val morningCardsByModuleId = sdk.database.morningCardCacheDao()
            .getAllOrderedOnce()
            .associateBy { it.moduleId }

        // Seed progress from persisted chw_module_completion + partial-completion
        // rows. In-session statusByModule always takes priority (the CHW may have
        // just completed a module in this session); DB rows fill the gaps on
        // first open after a restart. Partials are server-authoritative for
        // cross-device recovery — without them, a fresh-device CHW sees no
        // history because the local `coaching_event` table is empty.
        val completions = sdk.database.chwModuleCompletionDao()
            .getAllForChw(chwId)
            .associateBy { it.moduleFamilyId }
        val partials = sdk.database.chwModulePartialCompletionDao()
            .getAllForChw(chwId)
            .associateBy { it.moduleFamilyId }

        val mapped = modules.mapNotNull { entity ->
            val completion = completions[entity.moduleFamilyId]
            val partial = partials[entity.moduleFamilyId]
            // "completed" is sticky: `completedAt` is only written on a passing
            // attempt and carried forward across later fails (see
            // TriggerEvaluator.buildModuleCompletion). A row in
            // chw_module_completion with `completedAt == null` means the CHW
            // has attempted but never passed — that's in_progress, not done.
            val persistedStatus = when {
                completion?.completedAt != null -> "completed"
                completion != null || partial != null -> "in_progress"
                else -> "assigned"
            }
            // Prefer in-session value; fall back to DB-derived status.
            val status = statusByModule[entity.moduleFamilyId] ?: persistedStatus
            // Sync in-memory map so future observeModules calls are consistent.
            if (!statusByModule.containsKey(entity.moduleFamilyId) && persistedStatus != "assigned") {
                statusByModule[entity.moduleFamilyId] = persistedStatus
            }

            val card = morningCardsByModuleId[entity.moduleId]

            // Build the module shell first so we can use its inlineQuestions to
            // compute both wrongQuestionCount and the merged quizScorePct.
            val shell = entity.toLearnModule(
                status = status,
                gapCode = null,
                behaviouralGapId = card?.behaviouralGapId,
                source = card?.source,
                quizScorePct = null,
            ) ?: return@mapNotNull null

            val totalQ = shell.inlineQuestions?.size ?: 0
            val questionIds = shell.inlineQuestions?.map { it.id }?.toSet().orEmpty()

            val toReinforce: Set<String> = if (totalQ == 0) {
                emptySet()
            } else {
                toReinforceQuestionIds(
                    db = sdk.database,
                    chwId = chwId,
                    moduleFamilyId = entity.moduleFamilyId,
                    allQuestionIds = questionIds,
                )
            }
            val wrongCount = toReinforce.size

            // Distinct quiz questions ever attempted (right OR wrong). Powers the
            // training-card progress bar (see [TrainingGrid.progressFractionFor]
            // and PM direction in DM.txt). Bounded by [questionIds] so stale rows
            // from deleted/renumbered questions don't inflate the count.
            //
            // Cache-first: when the backend-supplied completion record says the
            // CHW passed the module (`completedAt != null`, persisted in
            // `chw_module_completion` by SyncApi.pullGaps), trust it as "all
            // questions attempted at some point" — the local coaching_event log
            // might be empty on a fresh device or after a Room wipe, but the
            // cached completion is authoritative. Without this clamp, a
            // backfilled-from-backend completion would render 0/N in the UI.
            val attemptedCount: Int = when {
                totalQ == 0 -> 0
                completion?.completedAt != null -> totalQ
                else -> {
                    val dao = sdk.database.coachingEventDao()
                    val correctIds = dao.getLatestCorrectQuestionIds(chwId, entity.moduleFamilyId)
                    val wrongIds = dao.getLatestWrongQuestionIds(chwId, entity.moduleFamilyId)
                    (correctIds.toSet() + wrongIds.toSet()).intersect(questionIds).size
                }
            }

            // Progress prioritization:
            //   passed-completion > partial > failed-completion > assigned
            // Partial path uses the merged toReinforce so the bar reflects
            // cumulative ever-correct fraction (option B from plan); falls
            // back to `latestQuizScore` only when no partial has landed yet.
            val quizScore: Float? = when {
                completion?.completedAt != null -> 1f
                partial != null && totalQ > 0 ->
                    ((totalQ - wrongCount).coerceAtLeast(0)).toFloat() / totalQ
                completion != null -> completion.latestQuizScore
                else -> null
            }

            // Publication time — see [QuizRetryGate]. ISO 8601 from the
            // backend module sync, parsed via the existing helper. Drop this
            // assignment (and the field on LearnModule) when removing the
            // retry-window feature; nothing else in the data path depends on it.
            val publishedAtMs: Long? = parseIsoMillis(entity.publishedAtIso)

            shell.copy(
                quizScorePct = quizScore,
                wrongQuestionCount = wrongCount,
                attemptedQuestionCount = attemptedCount,
                publishedAtMs = publishedAtMs,
            )
        }.sortedWith(
            compareBy(
                { if (it.behaviouralGapId != null && activeGapKeys.contains(it.behaviouralGapId)) 0 else 1 },
                {
                    when (it.status) {
                        "in_progress" -> 0
                        "assigned" -> 1
                        "completed" -> 2
                        else -> 3
                    }
                },
            )
        )

        val refresherCount = mapped.count {
            it.moduleType == "refresher" && !it.inlineQuestions.isNullOrEmpty()
        }
        val refresherEmpty = mapped.count {
            it.moduleType == "refresher" && it.inlineQuestions.isNullOrEmpty()
        }
        val trainingCount = mapped.count { it.moduleType != "refresher" && it.moduleType != "content_update" }
        val knowledgeCount = mapped.count { it.moduleType == "content_update" }
        val sourceGap = mapped.count { it.source == "gap" }
        val sourceFallback = mapped.count { it.source == "fallback" }
        val sourceNull = mapped.count { it.source == null }
        Log.i(
            TAG,
            "mapModules: total=${mapped.size} refreshers=$refresherCount " +
                "(skipped_empty=$refresherEmpty) training=$trainingCount knowledge=$knowledgeCount | " +
                "source: gap=$sourceGap fallback=$sourceFallback null=$sourceNull",
        )

        return mapped
    }

    /**
     * Picks the right empty-state message based on what actually caused the
     * empty list:
     *  - backend not configured → static "no backend" message
     *  - device offline → "no internet, retry" (the Retry button will succeed
     *    once connectivity returns)
     *  - device online but the backend genuinely returned no modules →
     *    informational "nothing published yet". Not an error condition;
     *    distinguished from the offline case so the CHW isn't told to fix
     *    their connection when there's nothing wrong with it.
     */
    private fun emptyMessage(sdk: MicroCoachingSDK): String = when {
        sdk.config.backendUrl.isBlank() -> localized(R.string.learn_empty_no_backend)
        !sdk.isNetworkAvailable() -> localized(R.string.learn_empty_no_internet)
        else -> localized(R.string.learn_empty_no_modules)
    }

    /**
     * Re-triggers inbound sync from the empty/error Learn screen. Flips the
     * state to [LearnUiState.Loading] so the CHW sees immediate feedback; the
     * running `observeModules` collector will replace it with [ModuleList]
     * the moment new modules land in Room. If the sync fails silently (no
     * Room change, no flow re-emit), the watchdog restores the Error state
     * after a few seconds so the CHW can retry again instead of being stuck
     * on a spinner.
     */
    fun retrySync() {
        val sdk = MicroCoachingSDK.getInstance()
        if (sdk.config.backendUrl.isBlank()) return // no backend → retry is a no-op
        _uiState.value = LearnUiState.Loading
        sdk.syncCoordinator.triggerNow()
        viewModelScope.launch {
            delay(5_000)
            if (_uiState.value is LearnUiState.Loading) {
                // Cache-first: only fall back to Error if `module_cache` is genuinely
                // empty. If cached rows exist, prefer showing them over an error —
                // the sync may have failed but the CHW has content to work with.
                val cacheCount = moduleRepo.countActive()
                _uiState.value = if (cacheCount == 0) {
                    LearnUiState.Error(emptyMessage(sdk))
                } else {
                    LearnUiState.ModuleList(lastKnownModules)
                }
            }
        }
    }

    private fun localized(@androidx.annotation.StringRes resId: Int): String {
        val sdkLanguage = MicroCoachingSDK.getInstance().language
        val ctx = com.medtroniclabs.microcoaching.ui.SdkLocaleHelper.wrap(context, sdkLanguage)
        return ctx.getString(resId)
    }

    // ── Navigation transitions ────────────────────────────────────────────────

    fun selectModule(module: LearnModule) {
        activeModule = module
        // Don't downgrade a completed module to "in_progress" just because the
        // CHW opened it again (revisit-from-KnowledgeRow). The persisted DB
        // status from `chw_module_completion.latestAttemptPassed = true` would
        // be overridden by this in-memory map and the module would jump back
        // to the Training row.
        if (module.status != "completed") {
            statusByModule[module.moduleFamilyId] = "in_progress"
        }
        _uiState.value = LearnUiState.ModuleReady(module)
        viewModelScope.launch {
            // Surfacing a module to the CHW maps to backend `module_delivered`.
            recordEvent(
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
     * Reads from Room one-shot via [MicroCoachingSDK.database.moduleDao] +
     * [mapModules] so the result matches what the live [observeModules] Flow
     * would produce on its next tick — no race with the async event-count
     * Flow.
     */
    fun refreshModuleCounts() {
        viewModelScope.launch {
            val sdk = MicroCoachingSDK.getInstance()
            val modules = sdk.database.moduleDao().getAllOrderedOnce()
            val mapped = mapModules(modules, sdk)
            lastKnownModules = mapped
            // Cache-first: gate on raw `module_cache` row count, not mapped size.
            _uiState.value = if (modules.isEmpty()) {
                LearnUiState.Error(emptyMessage(sdk))
            } else {
                LearnUiState.ModuleList(mapped)
            }
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
            statusByModule[module.moduleFamilyId] = "in_progress"
        }
        startedViaCourse = true
    }

    /**
     * Whether the active module is currently re-quizzable. Encapsulates the
     * [QuizRetryGate] rule so the nav graph doesn't have to import the gate
     * directly — keeps the "Try Again" surface on [QuizResultScreen] in
     * lock-step with the LessonPlayer's `readOnly` decision.
     *
     * Returns `false` when there's no active module or the retry window has
     * closed; `true` while the window is still open.
     *
     * When removing the retry-window feature (see [QuizRetryGate] "How to
     * remove"), this helper can return `true` unconditionally (or be deleted
     * with the gate). The QuizResultScreen will then always surface the
     * "Try Again" button when `onTryAgain` is non-null.
     */
    fun canRetryActiveQuiz(): Boolean {
        val module = activeModule ?: return false
        return !QuizRetryGate.isRetryWindowClosed(module)
    }

    /**
     * Restarts the lesson-player course after a failed quiz ("Try Again").
     * Resets quiz counters and restores [LearnUiState.LessonContent] so
     * [LessonPlayerScreen] has a module to render (QuizResult would show blank).
     */
    fun retryCourse() {
        val module = activeModule ?: return
        _quizCorrectCount = 0
        _quizTotalCount = 0
        startedViaCourse = true
        _uiState.value = LearnUiState.LessonContent(module)
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
     */
    fun recordCardShown(cardIndex: Int) {
        val module = activeModule ?: return
        viewModelScope.launch {
            recordEvent(
                eventType = "module_card_viewed",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
            )
        }
    }

    fun startLesson() {
        val module = activeModule ?: return
        _uiState.value = LearnUiState.LessonContent(module)
        viewModelScope.launch {
            // Recording the first card view as the CHW enters the lesson body.
            recordEvent(
                eventType = "module_card_viewed",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                cardFamilyId = module.cardFamilyId,
            )
        }
    }

    fun startQuiz() {
        val module = activeModule ?: return
        _quizCorrectCount = 0
        _quizTotalCount = 0
        viewModelScope.launch {
            activeQuestions = module.inlineQuestions ?: emptyList()
            _uiState.value = LearnUiState.QuizInProgress(questions = activeQuestions)
            recordEvent(
                eventType = "quiz_started",
                clinicalDomain = module.clinicalDomain,
                cardType = "quiz",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
            )
        }
    }

    /**
     * Refresher shortcut: jump straight from the module list to the quiz,
     * skipping the lesson-content state. Used by the v0.3.2 RefresherQuiz
     * bottom sheet. Telemetry path: `module_delivered` → `quiz_started` →
     * `module_quiz_attempted` (×N, per question) → `module_completed`.
     */
    fun selectModuleForQuiz(module: LearnModule) {
        activeModule = module
        statusByModule[module.moduleFamilyId] = "in_progress"
        startedViaRefresher = true
        _quizCorrectCount = 0
        _quizTotalCount = 0
        val questions = module.inlineQuestions.orEmpty()
        Log.d(
            TAG,
            "selectModuleForQuiz: family=${module.moduleFamilyId} moduleId=${module.moduleId} " +
                "type=${module.moduleType} questionCount=${questions.size}",
        )
        viewModelScope.launch {
            recordEvent(
                eventType = "module_delivered",
                clinicalDomain = module.clinicalDomain,
                cardType = "info",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                cardFamilyId = module.cardFamilyId,
            )
            activeQuestions = questions
            _uiState.value = LearnUiState.QuizInProgress(questions = activeQuestions)
            recordEvent(
                eventType = "quiz_started",
                clinicalDomain = module.clinicalDomain,
                cardType = "quiz",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
            )
        }
    }

    /**
     * One-shot taste used by the Quick learn banner. Emits a single
     * `module_quiz_attempted` carrying both the per-question outcome and the
     * attempt-level score so a wrong banner answer forms a gap state on the
     * backend — enabling the refresher to surface on the next morning-cards
     * call.
     */
    fun recordQuickLearnAnswer(
        module: LearnModule,
        question: QuizQuestion,
        answerIndex: Int,
    ) {
        val isCorrect = answerIndex == question.correctIndex
        viewModelScope.launch {
            recordEvent(
                eventType = "module_quiz_attempted",
                clinicalDomain = module.clinicalDomain,
                cardType = "quiz",
                quizQuestionId = question.id,
                selectedOption = answerIndex,
                isCorrect = isCorrect,
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                quizFamilyId = question.id,
                quizScorePct = if (isCorrect) 1f else 0f,
                outcomeOverride = if (isCorrect) "correct" else "wrong",
                behaviouralGapId = module.behaviouralGapId,
                triggerType = triggerTypeFor(module.source),
            )
            MicroCoachingSDK.getInstance().flushTelemetryNow()
        }
    }

    fun selectAnswer(questionIndex: Int, answerIndex: Int) {
        _uiState.update { state ->
            if (state is LearnUiState.QuizInProgress) {
                state.copy(answers = state.answers + (questionIndex to answerIndex))
            } else state
        }
        val question = activeQuestions.getOrNull(questionIndex) ?: return
        val isCorrect = answerIndex == question.correctIndex
        _quizTotalCount++
        if (isCorrect) _quizCorrectCount++
        val scorePct = _quizCorrectCount.toFloat() / _quizTotalCount
        viewModelScope.launch {
            recordEvent(
                eventType = "module_quiz_attempted",
                clinicalDomain = activeModule?.clinicalDomain,
                cardType = "quiz",
                quizQuestionId = question.id,
                selectedOption = answerIndex,
                isCorrect = isCorrect,
                moduleFamilyId = activeModule?.moduleFamilyId,
                moduleId = activeModule?.moduleId,
                moduleVersion = activeModule?.moduleVersion,
                quizFamilyId = question.id,
                quizScorePct = scorePct,
                triggerType = triggerTypeFor(activeModule?.source),
            )
        }
    }

    fun hasQuestion(index: Int): Boolean = index < activeQuestions.size

    /**
     * Completes the quiz in [LearnUiState.QuizInProgress], persists the
     * completion row + telemetry events, and (by default) flushes outbound
     * telemetry plus chains an inbound sync.
     *
     * Pass [deferSync] = `true` from any caller that is mid-flow inside a
     * bottom sheet — the refresher sheet, where the CHW still has lesson
     * cards to read after the quiz portion. Triggering inbound sync there
     * causes `refilterMorningModules` to race against `RefresherContent`'s
     * recomposition and can blank out the sheet. The bottom sheet's
     * `onDismiss` does the flush + sync once the CHW has finished the whole
     * experience.
     */
    fun finishQuiz(deferSync: Boolean = false) {
        val state = _uiState.value as? LearnUiState.QuizInProgress ?: return
        val module = activeModule ?: return

        val sdk = MicroCoachingSDK.getInstance()
        val passThreshold = sdk.config.quizPassThreshold
        val correctCount = state.questions.indices.count { idx ->
            state.answers[idx] == state.questions[idx].correctIndex
        }
        val scorePercent = if (state.questions.isEmpty()) 0
        else (correctCount * 100) / state.questions.size
        val passed = scorePercent >= passThreshold

        val badge = when {
            scorePercent >= 80 -> localized(R.string.badge_expert)
            scorePercent >= passThreshold -> localized(R.string.badge_learner)
            else -> localized(R.string.badge_practice)
        }

        statusByModule[module.moduleFamilyId] = if (passed) "completed" else "in_progress"

        _uiState.value = LearnUiState.QuizResult(
            scorePercent = scorePercent,
            correctCount = correctCount,
            totalCount = state.questions.size,
            badgeLabel = badge,
            completedModuleFamilyId = module.moduleFamilyId,
            questions = state.questions,
            answers = state.answers,
        )

        viewModelScope.launch {
            state.questions.forEachIndexed { idx, question ->
                val isCorrect = state.answers[idx] == question.correctIndex
                gapRepo.recordQuizAnswer(
                    chwId = chwId,
                    behaviouralGapId = activeModule?.behaviouralGapId,
                    clinicalDomain = module.clinicalDomain,
                    isCorrect = isCorrect,
                )
            }
            // Persist module completion + emit quiz_completed telemetry.
            sdk.onModuleQuizCompleted(
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                scoreFraction = scorePercent / 100f,
                passed = passed,
            )
            recordEvent(
                eventType = "module_quiz_attempted",
                clinicalDomain = module.clinicalDomain,
                cardType = "quiz",
                moduleFamilyId = module.moduleFamilyId,
                moduleId = module.moduleId,
                moduleVersion = module.moduleVersion,
                quizScorePct = scorePercent / 100f,
                outcomeOverride = if (passed) "correct" else "wrong",
                behaviouralGapId = module.behaviouralGapId,
                triggerType = triggerTypeFor(module.source),
            )
            if (passed) {
                recordEvent(
                    eventType = "module_completed",
                    clinicalDomain = module.clinicalDomain,
                    cardType = "info",
                    moduleFamilyId = module.moduleFamilyId,
                    moduleId = module.moduleId,
                    moduleVersion = module.moduleVersion,
                )
            }

            // Quiz attempt is a meaningful milestone — flush the batch
            // immediately rather than waiting for the 15-min WorkManager tick.
            // The synced module_quiz_attempted events are how the backend
            // records each answer; the legacy /coaching/quiz-answer endpoint
            // was retired in v3. Then chain an inbound pull so the freshly-
            // computed `chw_module_partial_completion` row lands locally and
            // the refresher / banner / morning-card filter reflect server
            // truth without waiting for the next 15-min tick.
            //
            // Skipped when [deferSync] is true (refresher-sheet flow) — see
            // the kdoc; the sheet's `onDismiss` runs the same calls once the
            // CHW has finished the lesson cards.
            if (!deferSync) {
                sdk.flushTelemetryNow()
                sdk.syncCoordinator.triggerNow()
            }
        }
    }

    // ── Event recording ───────────────────────────────────────────────────────

    /**
     * Maps a [LearnModule.source] value to the wire `trigger_type` per v1.1
     * Events-Modelling spec:
     *  - `"gap"` (gap-driven refresher) → `"gap"`
     *  - `"fallback"` (server fallback recommendation) → `"fallback"`
     *  - null (regular training-row quiz, no morning surface) → `"workflow_event"`
     */
    private fun triggerTypeFor(source: String?): String = when (source) {
        "gap" -> "gap"
        "fallback" -> "fallback"
        else -> "workflow_event"
    }

    private suspend fun recordEvent(
        eventType: String,
        clinicalDomain: String? = null,
        cardType: String? = null,
        quizQuestionId: String? = null,
        selectedOption: Int? = null,
        isCorrect: Boolean? = null,
        moduleFamilyId: String? = null,
        moduleId: String? = null,
        moduleVersion: Int? = null,
        cardFamilyId: String? = null,
        quizFamilyId: String? = null,
        quizScorePct: Float? = null,
        outcomeOverride: String? = null,
        behaviouralGapId: String? = null,
        triggerType: String? = null,
        inferenceMode: String? = null,
        networkState: String? = null,
    ) {
        try {
            val sdk = MicroCoachingSDK.getInstance()
            val db = sdk.database
            val sdkVersion = try {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionName ?: "0.0"
            } catch (_: Exception) { "0.0" }

            // v1.1 Events-Modelling spec uses "correct" / "wrong" — keep the
            // per-question default in sync with the aggregate finishQuiz path.
            val outcome = outcomeOverride ?: when {
                eventType == "module_quiz_attempted" && isCorrect != null ->
                    if (isCorrect) "correct" else "wrong"
                else -> null
            }

            // Default network state from the SDK's ConnectivityManager snapshot
            // when the caller hasn't supplied one — mirrors the value the chat
            // layer's currentNetworkState() helper uses, so dashboards see one
            // vocabulary across event families.
            val resolvedNetworkState = networkState
                ?: if (sdk.isNetworkAvailable()) "online" else "offline"

            db.coachingEventDao().insert(
                CoachingEventEntity(
                    eventId = UUID.randomUUID().toString(),
                    sdkVersion = sdkVersion,
                    eventFamily = eventFamilyFor(eventType),
                    sessionId = sessionId,
                    chwId = chwId,
                    eventType = eventType,
                    clinicalDomain = clinicalDomain,
                    cardType = cardType,
                    triggerType = triggerType,
                    inferenceMode = inferenceMode,
                    quizQuestionId = quizQuestionId,
                    selectedOption = selectedOption,
                    isCorrect = isCorrect,
                    outcome = outcome,
                    moduleFamilyId = moduleFamilyId,
                    moduleId = moduleId,
                    moduleVersion = moduleVersion,
                    cardFamilyId = cardFamilyId,
                    quizFamilyId = quizFamilyId,
                    quizScorePct = quizScorePct,
                    behaviouralGapId = behaviouralGapId,
                    networkState = resolvedNetworkState,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record event '$eventType': ${e.message}")
        }
    }

    // ── Language ──────────────────────────────────────────────────────────────

    private val lang: String
        get() = if (MicroCoachingSDK.getInstance().config.language == Language.ENGLISH) "en" else "bn"

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private fun ModuleEntity.toLearnModule(
        status: String,
        gapCode: String?,
        behaviouralGapId: String? = null,
        source: String? = null,
        quizScorePct: Float? = null,
    ): LearnModule? {
        val l = lang
        val firstCard = try {
            Json.parseToJsonElement(cardsJson).jsonArray.firstOrNull()?.jsonObject
        } catch (_: Exception) { null }

        // Prefer the module-level title — the first-card title is only a fallback
        // for legacy modules that don't carry their own title. Training/Knowledge
        // cards and screen headers all want the module title (e.g. "Introduction
        // to NCDs"), not the first card title (e.g. "What are NCDs?").
        val moduleTitle = if (l == "en") (titleEn ?: titleBn) else (titleBn ?: titleEn)
        val title = moduleTitle
            ?: firstCard?.primitiveOrNull("title_$l")
            ?: firstCard?.primitiveOrNull("title_bn")
            ?: return null
        // `body_*` in the v3.5+ module schema is a JSON array of rich-content
        // blocks (paragraph / bullet_list / video / …), not a string. The
        // [LearnModule.body] surface here is just the tile-card preview, so
        // when the card body isn't a flat string we fall through to the
        // module-level description fields. Rich rendering of the array form
        // is handled by RichBody downstream once the lesson player opens.
        val body = firstCard?.primitiveOrNull("body_$l")
            ?: firstCard?.primitiveOrNull("body_bn")
            ?: (if (l == "en") descriptionEn else null) ?: descriptionBn ?: descriptionEn ?: ""
        val nextStep = firstCard?.primitiveOrNull("next_action_$l")
            ?: firstCard?.primitiveOrNull("next_action_bn") ?: ""

        val inlineQuestions = parseInlineQuiz(quizJson, l)
        val quizIds = inlineQuestions.map { it.id }

        val firstCardFamilyId = firstCard?.primitiveOrNull("card_family_id")

        // content_update fields — present only on cards whose parent module is
        // a content_update type. Pulled from the first card row so the
        // Knowledge preview screen can render the protocol-change framing.
        val previousPracticeBn = firstCard?.primitiveOrNull("previous_practice_bn")
        val currentPracticeBn = firstCard?.primitiveOrNull("current_practice_bn")
        val rationaleForChangeBn = firstCard?.primitiveOrNull("rationale_for_change_bn")
        val nextActionBn = firstCard?.primitiveOrNull("next_action_bn")

        return LearnModule(
            moduleFamilyId = moduleFamilyId,
            title = title,
            body = body,
            clinicalDomain = gapCode ?: domain ?: "general",
            warningSigns = emptyList(),
            nextStep = nextStep,
            referralDestination = null,
            quizIds = quizIds,
            status = status,
            inlineQuestions = inlineQuestions.takeIf { it.isNotEmpty() },
            moduleId = moduleId,
            moduleVersion = version,
            cardFamilyId = firstCardFamilyId,
            moduleType = moduleType,
            estimatedMinutes = estimatedMinutes,
            previousPracticeBn = previousPracticeBn,
            currentPracticeBn = currentPracticeBn,
            rationaleForChangeBn = rationaleForChangeBn,
            nextActionBn = nextActionBn,
            behaviouralGapId = behaviouralGapId,
            source = source,
            cardsJson = cardsJson,
            quizScorePct = quizScorePct,
            thumbnailUrl = thumbnailUrl,
        )
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is JsonNull) null else content

    /**
     * Safely read a string from [JsonObject] at [key], returning null when the
     * value is missing, JSON null, or anything other than a primitive (e.g.
     * a `JsonArray` of rich-content blocks under `body_bn` / `body_en` in the
     * post-2026-06 module sync schema). The previous code chained
     * `.jsonPrimitive` which throws `IllegalArgumentException` on non-primitive
     * elements; this accessor returns null instead so the caller's fallback
     * chain (`?: descriptionEn` etc.) can take over.
     */
    private fun JsonObject.primitiveOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNullSafe()

    // ── Companion / Factory ───────────────────────────────────────────────────

    companion object {
        private const val TAG = "LearnViewModel"

        private val sessionId: String = UUID.randomUUID().toString()

        fun factory(context: Context, chwId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val db = MicroCoachingSDK.getInstance().database
                    return LearnViewModel(
                        context = context.applicationContext,
                        chwId = chwId,
                        gapRepo = GapProfileRepositoryImpl(db.chwGapProfileDao()),
                        moduleRepo = ModuleRepositoryImpl(db.moduleDao(), db.behaviouralGapDao()),
                    ) as T
                }
            }
    }
}
