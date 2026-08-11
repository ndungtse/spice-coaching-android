package com.medtroniclabs.microcoaching.ui.learn

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.domain.telemetry.triggerTypeFor
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Quiz-session behaviour for [LearnViewModel] — the quiz → result portion of the
 * module flow. Extracted as same-package `internal` extensions so the ViewModel
 * itself stays focused on list / lesson / knowledge-doc state.
 *
 * The shared mutable session state these functions drive (`activeModule`,
 * `activeQuestions`, the quiz counters, `_uiState`, `telemetry`) is *not* moved —
 * it stays owned by [LearnViewModel] (the lesson methods that remain there read
 * the same fields) and is widened to `internal` so these extensions can reach it.
 * Every function below is a byte-for-byte relocation of the former member; call
 * sites (`QuizNavGraph`, the quiz screens, the refresher sheets) are unchanged
 * apart from an added import.
 */

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
internal fun LearnViewModel.canRetryActiveQuiz(): Boolean {
    val module = activeModule ?: return false
    return canTakeQuiz(module)
}

/**
 * Whether the CHW may (re)take [module]'s quiz right now — the [QuizRetryGate]
 * reattempt window is still open for it. Drives the enabled state of the
 * "Do a Quiz" button on [ModuleDetailScreen] and the last-card quiz path in
 * [LessonPlayerScreen] (via its `readOnly` flag). A never-/partly-attempted
 * module is always takeable (the guaranteed first attempt); a fully-attempted
 * module is takeable only within the reattempt window.
 */
internal fun LearnViewModel.canTakeQuiz(module: LearnModule): Boolean {
    val windowDays = MicroCoachingSDK.getInstance().quizReattemptValidityDays.value
    return !QuizRetryGate.isRetryWindowClosed(module, windowDays = windowDays)
}

/**
 * Restarts the lesson-player course after a failed quiz ("Try Again").
 * Resets quiz counters and restores [LearnUiState.LessonContent] so
 * [LessonPlayerScreen] has a module to render (QuizResult would show blank).
 */
internal fun LearnViewModel.retryCourse() {
    val module = activeModule ?: return
    _quizCorrectCount = 0
    _quizTotalCount = 0
    startedViaCourse = true
    _uiState.value = LearnUiState.LessonContent(module)
}

internal fun LearnViewModel.startQuiz() {
    val module = activeModule ?: return
    _quizCorrectCount = 0
    _quizTotalCount = 0
    viewModelScope.launch {
        // Hydrate the quiz blob before showing the quiz. Usually a no-op — the
        // module was already hydrated on lesson entry — but covers the
        // (rare) direct-to-quiz path with a slim module.
        val full = hydrate(module)
        activeModule = full
        // Fresh question + option order for every attempt (incl. course "Try Again",
        // which re-routes back through here). Materialised once into activeQuestions,
        // so the order is stable for the whole attempt and read-by-index downstream.
        activeQuestions = (full.inlineQuestions ?: emptyList()).shuffledForAttempt()
        _uiState.value = LearnUiState.QuizInProgress(questions = activeQuestions)
        telemetry.recordCoachingEvent(
            eventType = "module_quiz_viewed",
            clinicalDomain = full.clinicalDomain,
            cardType = "quiz",
            moduleFamilyId = full.moduleFamilyId,
            moduleId = full.moduleId,
            moduleVersion = full.moduleVersion,
        )
    }
}

/**
 * Refresher shortcut: jump straight from the module list to the quiz,
 * skipping the lesson-content state. Used by the v0.3.2 RefresherQuiz
 * bottom sheet. Telemetry path: `module_delivered` → `module_quiz_viewed` →
 * `module_quiz_attempted` (×N, per question) → `module_completed`.
 */
internal fun LearnViewModel.selectModuleForQuiz(module: LearnModule) {
    activeModule = module
    MicroCoachingSDK.getInstance().coachingModuleStore
        .setInSessionStatus(module.moduleFamilyId, "in_progress")
    startedViaRefresher = true
    _quizCorrectCount = 0
    _quizTotalCount = 0
    viewModelScope.launch {
        // Refresher callers build full modules from the DB, so hydrate is a
        // no-op here — kept as defense-in-depth if a slim module is ever passed.
        val full = hydrate(module)
        activeModule = full
        val questions = full.inlineQuestions.orEmpty()
        Log.d(
            LearnViewModel.TAG,
            "selectModuleForQuiz: family=${full.moduleFamilyId} moduleId=${full.moduleId} " +
                "type=${full.moduleType} questionCount=${questions.size}",
        )
        telemetry.recordCoachingEvent(
            eventType = "module_delivered",
            clinicalDomain = full.clinicalDomain,
            cardType = "info",
            moduleFamilyId = full.moduleFamilyId,
            moduleId = full.moduleId,
            moduleVersion = full.moduleVersion,
            cardFamilyId = full.cardFamilyId,
        )
        activeQuestions = questions
        _uiState.value = LearnUiState.QuizInProgress(questions = activeQuestions)
        telemetry.recordCoachingEvent(
            eventType = "module_quiz_viewed",
            clinicalDomain = full.clinicalDomain,
            cardType = "quiz",
            moduleFamilyId = full.moduleFamilyId,
            moduleId = full.moduleId,
            moduleVersion = full.moduleVersion,
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
internal fun LearnViewModel.recordQuickLearnAnswer(
    module: LearnModule,
    question: QuizQuestion,
    answerIndex: Int,
) {
    val isCorrect = answerIndex == question.correctIndex
    viewModelScope.launch {
        telemetry.recordCoachingEvent(
            eventType = "module_quiz_attempted",
            clinicalDomain = module.clinicalDomain,
            cardType = "quiz",
            quizQuestionId = question.id,
            selectedOption = question.canonicalOptionIndex(answerIndex),
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

internal fun LearnViewModel.selectAnswer(questionIndex: Int, answerIndex: Int) {
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
        telemetry.recordCoachingEvent(
            eventType = "module_quiz_attempted",
            clinicalDomain = activeModule?.clinicalDomain,
            cardType = "quiz",
            quizQuestionId = question.id,
            selectedOption = question.canonicalOptionIndex(answerIndex),
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

internal fun LearnViewModel.hasQuestion(index: Int): Boolean = index < activeQuestions.size

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
internal fun LearnViewModel.finishQuiz(deferSync: Boolean = false) {
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

    MicroCoachingSDK.getInstance().coachingModuleStore
        .setInSessionStatus(module.moduleFamilyId, if (passed) "completed" else "in_progress")

    // XP from the shared learning-points config: every attempted question
    // earns the base, each correct answer the multiplier, plus a flat
    // completion reward (reaching this screen ⇒ all questions attempted).
    val earnedXp = sdk.learningPoints.value.moduleQuizXp(
        questionsAttempted = state.questions.size,
        correctAnswers = correctCount,
    )

    _uiState.value = LearnUiState.QuizResult(
        scorePercent = scorePercent,
        correctCount = correctCount,
        totalCount = state.questions.size,
        badgeLabel = badge,
        completedModuleFamilyId = module.moduleFamilyId,
        questions = state.questions,
        answers = state.answers,
        earnedXp = earnedXp,
    )

    viewModelScope.launch {
        // Gap state is no longer mutated here: the per-question
        // `module_quiz_attempted` events emitted by `selectAnswer` are the
        // single input, and `OnDeviceGapStateEngine` derives gap state from
        // them (replayed over the synced baseline). Keeping the baseline
        // table backend-authored is what makes the merge correct.

        // Persist module completion + emit quiz_completed telemetry.
        sdk.onModuleQuizCompleted(
            moduleFamilyId = module.moduleFamilyId,
            moduleId = module.moduleId,
            scoreFraction = scorePercent / 100f,
            passed = passed,
        )
        telemetry.recordCoachingEvent(
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
            telemetry.recordCoachingEvent(
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
