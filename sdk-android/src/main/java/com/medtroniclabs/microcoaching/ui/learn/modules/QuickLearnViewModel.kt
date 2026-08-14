package com.medtroniclabs.microcoaching.ui.learn.modules

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.ui.common.translatedText
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.progress.refresherDrillQuestionIds
import com.medtroniclabs.microcoaching.progress.toReinforceQuestionIds
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.LearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.recordQuickLearnAnswer
import com.medtroniclabs.microcoaching.ui.learn.selectModuleForQuiz
import com.medtroniclabs.microcoaching.ui.learn.QuizQuestion
import com.medtroniclabs.microcoaching.ui.learn.parseInlineQuiz
import com.medtroniclabs.microcoaching.ui.learn.shuffledForAttempt
import com.medtroniclabs.microcoaching.ui.learn.withShuffledOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State holder for the Quick learn banner + morning refresher bottom sheet.
 *
 * Observes [MicroCoachingSDK.morningModules] and exposes a preview question from the
 * top-priority module. When no module is surfaced or none has inline questions,
 * [quickQuestion] is null and the banner hides.
 *
 * Telemetry: routes single-answer events through [LearnViewModel.recordQuickLearnAnswer].
 * For the full refresher quiz, call [primeRefresherQuiz] first, then render
 * [filteredQuestionsForRefresher] with [learnViewModel] via `SharedQuizInProgressContent`.
 */
class QuickLearnViewModel(
    internal val learnViewModel: LearnViewModel,
    internal val morningModulesSource: StateFlow<List<ModuleEntity>>,
    private val morningCardsSource: StateFlow<List<MorningCardCacheEntity>>,
) : ViewModel() {

    private val _answerState = MutableStateFlow<AnswerOutcome?>(null)
    val answerState: StateFlow<AnswerOutcome?> = _answerState.asStateFlow()

    /**
     * The question set the refresher sheet renders for the primed module — its drill
     * set (see [reinforceSlice]), or the whole quiz when nothing is outstanding.
     * Populated by [primeRefresherQuiz]; empty until called.
     */
    val filteredQuestionsForRefresher = MutableStateFlow<List<QuizQuestion>>(emptyList())

    /**
     * The module entity most recently primed by [primeRefresherQuiz]. Held so the
     * "Try Again" action ([restartRefresherQuiz]) can re-arm the quiz even after the
     * module has dropped out of [morningModulesSource] (answering the last open
     * question correctly triggers refilterMorningModules mid-sheet).
     */
    private var lastPrimedEntity: ModuleEntity? = null

    /**
     * Size of the drill set for the featured morning module — the same number its tile
     * shows. Populated by [primeRefresherQuiz] / [computeWrongQuestionCount]; 0 while
     * unpopulated. Drives the question-count label on MorningCard / LearnCard.
     */
    private val _wrongQuestionCount = MutableStateFlow(0)
    val wrongQuestionCount: StateFlow<Int> = _wrongQuestionCount.asStateFlow()

    /**
     * Banner preview question. Combines [morningModulesSource] with the
     * `coaching_event` count flow so the banner re-evaluates after every
     * quiz answer. Walks the morning-modules list in backend priority order
     * (gap → fallback) and picks the first module that still has an
     * unmastered question — the [QuizRefresherCard] only hides when *every*
     * refresher-eligible module has been fully mastered, rather than
     * disappearing the moment the single top module is cleared.
     */
    val quickQuestion: StateFlow<QuickQuestion?> = combine(
        morningModulesSource,
        MicroCoachingSDK.getInstance().database.coachingEventDao().getEventCountFlow(),
        MicroCoachingSDK.getInstance().skippedRefresherFamilyIds,
    ) { modules, _, skipped ->
        // Drop refreshers the CHW swiped away this session so the banner advances
        // to the next queued module (or hides). They remain in the Refresher list.
        modules.filter { it.moduleFamilyId !in skipped }
    }
        .map { modules ->
            var picked: QuickQuestion? = null
            for (entity in modules) {
                val q = firstWrongQuestionOf(entity)
                if (q != null) { picked = q; break }
            }
            picked
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Family ids the CHW has skipped this app-open session (home MorningCard skip
     * or QuizRefresherCard swipe). App-session scoped in the SDK, so it persists
     * across home ⇄ modules ⇄ navigation. Drives the banner/list/home-card
     * "skipped → not re-featured, stays in list" behaviour and the badge count.
     */
    val skippedRefresherIds: StateFlow<Set<String>> =
        MicroCoachingSDK.getInstance().skippedRefresherFamilyIds

    /**
     * Categorised module lists + the shared featured pick, straight from the
     * SDK-owned [com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore].
     * The modules screen reads these instead of categorising locally, so its
     * banner/list and the home MorningCard always agree (same store, same pick).
     * [featuredCard] already excludes skipped families and requires a quiz, so it
     * advances on skip and is null only when every refresher is skipped.
     */
    val refresherModules: StateFlow<List<LearnModule>> =
        MicroCoachingSDK.getInstance().coachingModuleStore.refresherModules
    val trainingModules: StateFlow<List<LearnModule>> =
        MicroCoachingSDK.getInstance().coachingModuleStore.trainingModules
    /** False until the assigned-module set has loaded — gates the Training empty state. */
    val trainingAssignmentsLoaded: StateFlow<Boolean> =
        MicroCoachingSDK.getInstance().coachingModuleStore.trainingAssignmentsLoaded
    val featuredCard: StateFlow<LearnModule?> =
        MicroCoachingSDK.getInstance().coachingModuleStore.selectedMorningCard

    /**
     * The CHW swiped the [QuizRefresherCard] away. Marks the featured module as
     * skipped (persisted app-session → home "Coaching" tile badge + stays in the
     * RefresherList); the banner then advances to the next non-skipped refresher.
     */
    fun skipQuickRefresher(moduleFamilyId: String) {
        MicroCoachingSDK.getInstance().markRefresherSkipped(moduleFamilyId)
    }

    /**
     * Reconcile the skipped-refresher badge to the currently-active refresher
     * pool (passed by the modules screen) so the count reflects only unique,
     * still-pending skipped refreshers.
     */
    fun reconcileActiveSkipped(activeFamilyIds: List<String>) {
        MicroCoachingSDK.getInstance().retainActiveSkippedRefreshers(activeFamilyIds.toSet())
    }

    /**
     * The exact refresher modules to drive the bottom sheet, resolved straight
     * from the DB by the [familyIds] the modules screen passed (the RefresherList
     * + banner). This is the **shared source of truth**: the sheet's queue is
     * exactly what the CHW saw — no leakage from the broader morning set. Empty
     * for the home-screen flow (which falls back to [morningModulesSource]).
     */
    private val _refresherQueue = MutableStateFlow<List<ModuleEntity>>(emptyList())
    val refresherQueue: StateFlow<List<ModuleEntity>> = _refresherQueue.asStateFlow()

    /** Resolve [familyIds] (latest version of each) into [refresherQueue], preserving order. */
    suspend fun loadRefresherQueue(familyIds: List<String>) {
        if (familyIds.isEmpty()) {
            _refresherQueue.value = emptyList()
            return
        }
        val dao = MicroCoachingSDK.getInstance().database.moduleDao()
        _refresherQueue.value = familyIds.mapNotNull { dao.getByFamilyId(it) }
    }

    /**
     * First question to preview for [moduleFamilyId] on the [QuizRefresherCard] banner.
     * The first still-to-reinforce question (see [reinforceSlice]); if the module is
     * fully mastered (nothing outstanding), falls back to its first quiz question so the
     * banner still renders for the featured module — keeping it in lock-step with the
     * host's [com.medtroniclabs.microcoaching.ui.components.MorningCard] (both surface
     * the same featured refresher). Null only when the module has no quiz at all.
     */
    suspend fun firstReinforceQuestionFor(moduleFamilyId: String): QuizQuestion? {
        val sdk = MicroCoachingSDK.getInstance()
        val entity = sdk.database.moduleDao().getByFamilyId(moduleFamilyId) ?: return null
        return (reinforceSlice(entity).firstOrNull()
            ?: parseInlineQuiz(entity.quizJson, sdkLang()).firstOrNull())
            ?.withShuffledOptions()
    }

    /**
     * The morning-card row behind [entity], carrying its source / gap id / targeted
     * question.
     *
     * Falls back to a family match because `module_cache` is keyed by *version*: the
     * sheet resolves a family's newest version while the card may still name an older
     * one, and an exact-id-only lookup then silently dropped `source`,
     * `behaviouralGapId` and `quizId` — leaving the sheet drilling a different set from
     * the one the tile counted.
     */
    private fun morningCardFor(entity: ModuleEntity): MorningCardCacheEntity? {
        val cards = morningCardsSource.value
        return cards.firstOrNull { it.moduleId == entity.moduleId }
            ?: cards.firstOrNull { it.moduleFamilyId == entity.moduleFamilyId }
    }

    private fun sdkLang(): String {
        val sdk = MicroCoachingSDK.getInstance()
        return if (sdk.config.language == Language.ENGLISH) "en" else "bn"
    }

    /**
     * Today's refresher question set for [entity]: every quiz question still
     * unanswered-correctly (wrong + never-answered/server-incomplete), narrowed by the
     * morning card's targeted question and ordered wrong-first.
     *
     * This is the single source of truth for what a refresher presents. The tile count
     * ([com.medtroniclabs.microcoaching.domain.refresher.LearnModuleMapper]
     * `reinforceQuestionCount`) applies the same [refresherDrillQuestionIds] rule to
     * the same set, so tile and sheet agree; the membership filter
     * (`keepIfHasReinforceQuestions`) only asks whether the set is empty, which the
     * narrowing preserves.
     *
     * Empty when the module is fully mastered — it then drops from the morning list
     * upstream.
     */
    private suspend fun reinforceSlice(entity: ModuleEntity): List<QuizQuestion> {
        val allQ = parseInlineQuiz(entity.quizJson, sdkLang())
        if (allQ.isEmpty()) return emptyList()
        val sdk = MicroCoachingSDK.getInstance()
        val chwId = sdk.currentCHWId ?: return allQ // no CHW context → present the whole quiz
        val allIds = allQ.map { it.id }.toSet()
        val outstanding = toReinforceQuestionIds(sdk.database, chwId, entity.moduleFamilyId, allIds)
        if (outstanding.isEmpty()) return emptyList()
        // A backend or on-device "quiz"-source card drills the one question it names.
        // Applied here rather than at the point of use so every consumer — the sheet,
        // the home-card label, the banner preview — inherits the same decision.
        val toReinforce = refresherDrillQuestionIds(outstanding, morningCardFor(entity)?.quizId)
        val wrong = sdk.database.coachingEventDao()
            .getLatestWrongQuestionIds(chwId, entity.moduleFamilyId).toSet()
        // Wrong-first, then the remaining outstanding questions. The weak-spots-lead
        // tiering is preserved (pedagogy + it must match the tile count / membership,
        // which depend only on the SET, not the order), but each tier is shuffled so a
        // reattempt doesn't replay the same question sequence.
        val weak = allQ.filter { it.id in wrong && it.id in toReinforce }
        val rest = allQ.filter { it.id in toReinforce && it.id !in wrong }
        return weak.shuffled() + rest.shuffled()
    }

    fun submitAnswer(answerIndex: Int) {
        val current = quickQuestion.value ?: return
        if (_answerState.value != null) return
        learnViewModel.recordQuickLearnAnswer(
            module = current.module,
            question = current.question,
            answerIndex = answerIndex,
        )
        _answerState.value = AnswerOutcome(
            selectedIndex = answerIndex,
            isCorrect = answerIndex == current.question.correctIndex,
        )
    }

    fun reset() {
        _answerState.value = null
    }

    // ── Listen-aloud (proxies LearnViewModel) ────────────────────────────────

    /**
     * Mirrors [LearnViewModel.autoSpeakEnabled] so the refresher UI shares the
     * same toggle semantics as the main lesson player.
     */
    val autoSpeakEnabled: StateFlow<Boolean> get() = learnViewModel.autoSpeakEnabled

    fun toggleAutoSpeak() {
        learnViewModel.toggleAutoSpeak()
    }

    fun speakAloud(text: String, onDone: () -> Unit = {}) {
        learnViewModel.speakAloud(text, onDone)
    }

    fun stopSpeaking() {
        learnViewModel.stopSpeaking()
    }

    /**
     * Computes the wrong-question count for the **featured** module (the same one
     * the home card / modules banner show — the store's [selectedMorningCard]
     * mapped back to a [ModuleEntity] via `sdk.selectedMorningModule`) and updates
     * [wrongQuestionCount] for the home-screen card label. Targeting the featured
     * pick (not the raw morning-API top) keeps the label in sync with the title
     * shown. Does NOT prime [learnViewModel] — call from the banner composable via
     * LaunchedEffect.
     */
    suspend fun computeWrongQuestionCount() {
        val sdk = MicroCoachingSDK.getInstance()
        val entity = sdk.selectedMorningModule.value ?: run { _wrongQuestionCount.value = 0; return }
        // The banner count must equal what the refresher actually presents (see
        // [reinforceSlice]), so the label matches both the tile and the sheet.
        val count = reinforceSlice(entity).size
        _wrongQuestionCount.value = count
        android.util.Log.d(TAG, "computeWrongQuestionCount: module=${entity.moduleFamilyId} toReinforce=$count")
    }

    /**
     * Primes [learnViewModel] with the refresher question set for the top morning
     * module (see [reinforceSlice] for how that set is chosen). Answering it clears the
     * module so it stops re-surfacing. Also updates [wrongQuestionCount] (= the set
     * size) for the home-screen card label, keeping it in sync with the tile + sheet.
     */
    suspend fun primeRefresherQuiz(targetModuleFamilyId: String? = null) {
        // Resolve against the DB-loaded refresher queue (the exact list/banner the
        // CHW saw) and fall back to the morning set only for the home-screen flow.
        val pool = refresherQueue.value.ifEmpty { morningModulesSource.value }
        val entity = if (targetModuleFamilyId != null) {
            pool.firstOrNull { it.moduleFamilyId == targetModuleFamilyId }
                ?: pool.firstOrNull()
        } else {
            pool.firstOrNull()
        } ?: return
        lastPrimedEntity = entity

        // The drill set, already narrowed to the card's targeted question and ordered
        // weak-first by reinforceSlice. If the module is fully mastered (nothing
        // outstanding) — e.g. a skipped card the CHW already aced, still reachable from
        // the list — fall back to the WHOLE quiz (order randomised) so the tap opens a
        // re-takeable sheet matching the tile's fallback count, instead of a blank
        // screen. Either way, shuffle each question's options for this attempt.
        val ordered = reinforceSlice(entity)
            .ifEmpty { parseInlineQuiz(entity.quizJson, sdkLang()).shuffledForAttempt() }
        val selected = ordered.map { it.withShuffledOptions() }
        if (selected.isEmpty()) return // genuinely no quiz
        val card = morningCardFor(entity)
        // One line carrying everything the tile count depends on, so a count/drill
        // mismatch can be diagnosed from logcat instead of re-deriving the pipeline.
        android.util.Log.i(TAG,
            "primeRefresherQuiz: family=${entity.moduleFamilyId} module=${entity.moduleId} " +
                "v=${entity.version} card=${card?.moduleId ?: "none"} " +
                "targetQuiz=${card?.quizId ?: "none"} selected=${selected.size}")

        _wrongQuestionCount.value = selected.size
        filteredQuestionsForRefresher.value = selected

        val module = entity.toMinimalLearnModule(
            behaviouralGapId = card?.behaviouralGapId,
            source = card?.source,
        ).copy(inlineQuestions = selected)
        learnViewModel.selectModuleForQuiz(module)
    }

    /**
     * Re-arms the quiz with the SAME question set the CHW just attempted — backing the
     * "Try Again" action in the refresher sheet's terminal footer. Unlike
     * [primeRefresherQuiz] it does NOT re-filter against the to-reinforce set (which
     * shrinks as answers land), so it's a faithful redo of the questions just shown.
     * Reads [lastPrimedEntity] rather than [morningModulesSource] because the module
     * may have refiltered out of that flow mid-sheet.
     *
     * The sheet must re-key its quiz composable alongside this call — re-arming the
     * list alone leaves the previous attempt's answers on screen, locked.
     */
    fun restartRefresherQuiz() {
        val entity = lastPrimedEntity ?: return
        // Faithful redo of the SAME question set, but reshuffled (order + options) so the
        // reattempt isn't a replay. shuffledForAttempt composes the option mapping back to
        // authored order, so telemetry's canonical index stays correct after the reshuffle.
        val questions = filteredQuestionsForRefresher.value.shuffledForAttempt()
        if (questions.isEmpty()) return
        // Keep the displayed list (RefresherContent) and the scoring list (activeQuestions,
        // set by selectModuleForQuiz) pointing at the same shuffled instance.
        filteredQuestionsForRefresher.value = questions
        val card = morningCardFor(entity)
        val module = entity.toMinimalLearnModule(
            behaviouralGapId = card?.behaviouralGapId,
            source = card?.source,
        ).copy(inlineQuestions = questions)
        learnViewModel.selectModuleForQuiz(module)
    }

    /**
     * Selects the first wrong question (or first question if no wrong history)
     * for the [QuizRefresherCard] banner preview.
     */
    private suspend fun firstWrongQuestionOf(entity: ModuleEntity): QuickQuestion? {
        // Preview = first question of the refresher's to-reinforce set (weak-first), so
        // the banner matches the quiz it launches. If the module is fully mastered, fall
        // back to its first quiz question so the featured module still previews (in
        // lock-step with the host MorningCard). Null only when the module ships no quiz.
        val slice = reinforceSlice(entity)
        val question = (slice.firstOrNull()
            ?: parseInlineQuiz(entity.quizJson, sdkLang()).firstOrNull())
            ?.withShuffledOptions()
            ?: run {
                android.util.Log.d(TAG, "morning module '${entity.titleBn}' has no inline questions — banner hidden.")
                return null
            }
        android.util.Log.i(TAG,
            "QuickLearn selected: module='${entity.titleBn}' " +
            "toReinforce=${slice.size} questionId=${question.id} text='${question.questionText.take(60)}'")
        val card = morningCardFor(entity)
        return QuickQuestion(
            module = entity.toMinimalLearnModule(
                behaviouralGapId = card?.behaviouralGapId,
                source = card?.source,
            ),
            question = question,
        )
    }

    companion object {
        private const val TAG = "QuickLearnVM"

        fun factory(context: Context, chwId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val sdk = MicroCoachingSDK.getInstance()
                    val learnVm = LearnViewModel.factory(context, chwId).create(LearnViewModel::class.java)
                    return QuickLearnViewModel(
                        learnViewModel = learnVm,
                        morningModulesSource = sdk.morningModules,
                        morningCardsSource = sdk.morningCardsItems,
                    ) as T
                }
            }
    }
}

data class QuickQuestion(
    val module: LearnModule,
    val question: QuizQuestion,
)

data class AnswerOutcome(
    val selectedIndex: Int,
    val isCorrect: Boolean,
)

private fun ModuleEntity.toMinimalLearnModule(
    behaviouralGapId: String? = null,
    source: String? = null,
): LearnModule = LearnModule(
    moduleFamilyId = moduleFamilyId,
    title = translatedText(bn = titleBn, en = titleEn),
    body = translatedText(bn = descriptionBn ?: "", en = descriptionEn),
    clinicalDomain = domain,
    contentDomain = contentDomain,
    moduleId = moduleId,
    moduleVersion = version,
    moduleType = moduleType,
    behaviouralGapId = behaviouralGapId,
    source = source,
    cardsJson = cardsJson,
)
