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
import com.medtroniclabs.microcoaching.progress.toReinforceQuestionIds
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.LearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.QuizQuestion
import com.medtroniclabs.microcoaching.ui.learn.parseInlineQuiz
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
 * Observes [MicroCoachingSDK.morningModules] and exposes the first wrong
 * quiz question of the top-priority module. When no module is surfaced or
 * no inline questions exist, [quickQuestion] is null and the banner hides.
 *
 * Telemetry: routes single-answer events through [LearnViewModel.recordQuickLearnAnswer].
 * For the full refresher quiz (all wrong questions), call [primeRefresherQuiz] first,
 * then use [filteredQuestionsForRefresher] with [learnViewModel] via SharedQuizInProgressContent.
 */
class QuickLearnViewModel(
    internal val learnViewModel: LearnViewModel,
    internal val morningModulesSource: StateFlow<List<ModuleEntity>>,
    private val morningCardsSource: StateFlow<List<MorningCardCacheEntity>>,
) : ViewModel() {

    private val _answerState = MutableStateFlow<AnswerOutcome?>(null)
    val answerState: StateFlow<AnswerOutcome?> = _answerState.asStateFlow()

    /**
     * Filtered list of questions the CHW has previously answered incorrectly for
     * the top morning module. Populated by [primeRefresherQuiz]; empty until called.
     * Falls back to all questions when the CHW has no wrong answers yet.
     */
    val filteredQuestionsForRefresher = MutableStateFlow<List<QuizQuestion>>(emptyList())

    /**
     * Count of questions the CHW has answered incorrectly for the top morning module.
     * Populated by [primeRefresherQuiz]. 0 while unpopulated.
     * Drives the question-count label on MorningCard / LearnCard.
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
    ) { modules, _ -> modules }
        .map { modules ->
            var picked: QuickQuestion? = null
            for (entity in modules) {
                val q = firstWrongQuestionOf(entity)
                if (q != null) { picked = q; break }
            }
            picked
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Look up the morning-card cache for the top module's source / gap id. */
    private fun morningCardFor(moduleId: String?): MorningCardCacheEntity? =
        if (moduleId == null) null
        else morningCardsSource.value.firstOrNull { it.moduleId == moduleId }

    private fun sdkLang(): String {
        val sdk = MicroCoachingSDK.getInstance()
        return if (sdk.config.language == Language.ENGLISH) "en" else "bn"
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
     * Computes the wrong-question count for the top morning module and updates
     * [wrongQuestionCount] for the home-screen card label. Does NOT prime
     * [learnViewModel] — call this from the banner composable via LaunchedEffect.
     */
    suspend fun computeWrongQuestionCount() {
        val entity = morningModulesSource.value.firstOrNull() ?: return
        val sdk = MicroCoachingSDK.getInstance()
        val chwId = sdk.currentCHWId ?: return
        val lang = sdkLang()
        val allQ = parseInlineQuiz(entity.quizJson, lang)
        if (allQ.isEmpty()) { _wrongQuestionCount.value = 0; return }
        val allIds = allQ.map { it.id }.toSet()
        val toReinforce = toReinforceQuestionIds(
            db = sdk.database,
            chwId = chwId,
            moduleFamilyId = entity.moduleFamilyId,
            allQuestionIds = allIds,
        )
        _wrongQuestionCount.value = toReinforce.size
        android.util.Log.d(TAG,
            "computeWrongQuestionCount: allQ=${allQ.size} toReinforce=${toReinforce.size}")
    }

    /**
     * Loads the wrong-question set for the top morning module and primes
     * [learnViewModel] via [LearnViewModel.selectModuleForQuiz] so that
     * [RefresherContent] can render the full quiz via SharedQuizInProgressContent.
     *
     * Falls back to ALL questions when the CHW has no wrong answers yet (first attempt).
     * Also updates [wrongQuestionCount] for the home-screen card label.
     */
    suspend fun primeRefresherQuiz(targetModuleFamilyId: String? = null) {
        val entity = if (targetModuleFamilyId != null) {
            morningModulesSource.value.firstOrNull { it.moduleFamilyId == targetModuleFamilyId }
                ?: morningModulesSource.value.firstOrNull()
        } else {
            morningModulesSource.value.firstOrNull()
        } ?: return
        val sdk = MicroCoachingSDK.getInstance()
        val chwId = sdk.currentCHWId ?: return
        val lang = sdkLang()
        val allQ = parseInlineQuiz(entity.quizJson, lang)
        if (allQ.isEmpty()) return

        // Merge server's authoritative incomplete set with local quiz history —
        // keeps cross-device recovery (Phone B sees Phone A's progress) while
        // letting offline-correct answers immediately drop questions out of the
        // refresher set without waiting for the next sync round-trip.
        val allIds = allQ.map { it.id }.toSet()
        val toReinforce = toReinforceQuestionIds(
            db = sdk.database,
            chwId = chwId,
            moduleFamilyId = entity.moduleFamilyId,
            allQuestionIds = allIds,
        )
        val filtered = allQ.filter { it.id in toReinforce }

        android.util.Log.i(TAG,
            "primeRefresherQuiz: module=${entity.moduleFamilyId} " +
            "allQ=${allQ.size} toReinforce=${toReinforce.size} filtered=${filtered.size}")

        _wrongQuestionCount.value = filtered.size
        filteredQuestionsForRefresher.value = filtered

        val card = morningCardFor(entity.moduleId)
        val module = entity.toMinimalLearnModule(
            behaviouralGapId = card?.behaviouralGapId,
            source = card?.source,
        ).copy(inlineQuestions = filtered)
        learnViewModel.selectModuleForQuiz(module)
    }

    /**
     * Selects the first wrong question (or first question if no wrong history)
     * for the [QuizRefresherCard] banner preview.
     */
    private suspend fun firstWrongQuestionOf(entity: ModuleEntity): QuickQuestion? {
        val lang = sdkLang()
        val parsed = parseInlineQuiz(entity.quizJson, lang)
        if (parsed.isEmpty()) {
            android.util.Log.d(TAG,
                "morning module '${entity.titleBn}' has no inline questions — banner hidden.")
            return null
        }
        val sdk = MicroCoachingSDK.getInstance()
        val chwId = sdk.currentCHWId
        val toReinforce = if (chwId != null) {
            // Merge server partial-completion set with local quiz history.
            // Re-evaluates on every coaching_event change because the parent
            // flow combines getEventCountFlow(); also picks up new partial-
            // completion rows on the next observeModules tick.
            val allIds = parsed.map { it.id }.toSet()
            val ids = toReinforceQuestionIds(
                db = sdk.database,
                chwId = chwId,
                moduleFamilyId = entity.moduleFamilyId,
                allQuestionIds = allIds,
            )
            parsed.filter { it.id in ids }
        } else {
            parsed
        }
        if (toReinforce.isEmpty()) {
            android.util.Log.d(TAG,
                "morning module '${entity.titleBn}' all questions mastered — banner hidden.")
            return null
        }
        val question = toReinforce.first()
        android.util.Log.i(TAG,
            "QuickLearn selected: lang=$lang module='${entity.titleBn}' " +
            "wrongCount=${toReinforce.size} questionId=${question.id} text='${question.questionText.take(60)}'")
        val card = morningCardFor(entity.moduleId)
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
    moduleId = moduleId,
    moduleVersion = version,
    moduleType = moduleType,
    behaviouralGapId = behaviouralGapId,
    source = source,
    cardsJson = cardsJson,
)
