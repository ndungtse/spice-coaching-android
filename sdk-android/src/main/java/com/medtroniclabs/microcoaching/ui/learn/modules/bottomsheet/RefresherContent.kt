package com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.learn.finishQuiz
import com.medtroniclabs.microcoaching.ui.learn.modules.QuickLearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.parseInlineQuiz
import com.medtroniclabs.microcoaching.ui.learn.parseLessonCards
import com.medtroniclabs.microcoaching.ui.theme.QuizOptionSurface

/**
 * Full refresher experience inside [RefresherBottomSheet].
 *
 * Two-phase state machine driven by [entryMode]:
 *
 * **QUESTION_FIRST** (from [QuizRefresherCard] or home screen):
 *   Phase 1 = 1 quiz question (first wrong question, or first question on first attempt)
 *   Phase 2 = lesson cards → Done
 *
 * **CARDS_FIRST** (from [MorningCard]):
 *   Phase 1 = lesson cards → Phase 2 = 1 quiz question → Done
 *
 * [targetModuleFamilyId] — when set (RefresherList tile flow), the content for
 * that specific module is shown instead of the first morning module.
 *
 * When [fromHomeScreen] is true, Done dismisses the morning card banner via
 * [MicroCoachingSDK.dismissMorningRefresher].
 */
@Composable
fun RefresherContent(
    viewModel: QuickLearnViewModel,
    fromHomeScreen: Boolean = false,
    entryMode: RefresherBottomSheet.EntryMode = RefresherBottomSheet.EntryMode.CARDS_FIRST,
    targetModuleFamilyId: String? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    queueFamilyIds: List<String> = emptyList(),
) {
    // Resolve the target module ONCE at first composition and hold onto it
    // for the lifetime of the sheet. The underlying `morningModulesSource`
    // (sdk._morningModules) is reactive — every coaching_event insert triggers
    // `refilterMorningModules`, which may drop this module from the list the
    // moment its last open question is answered correctly. Without locking,
    // PHASE_2 (lesson cards) would blank out mid-flow because targetEntity
    // becomes null. Once the CHW has opened the sheet, the lesson lives on
    // its own timeline; background refilters shouldn't yank it away.
    // The sheet's queue is the SHARED source of truth with the modules screen:
    // [queueFamilyIds] is exactly what the CHW saw (RefresherList + banner), and
    // the sheet resolves those modules straight from the DB — so "Next refresher"
    // chains only through them, never leaking extra modules from the broader
    // morning set. The home-screen flow passes no ids and uses the morning set.
    val useResolvedQueue = !fromHomeScreen && queueFamilyIds.isNotEmpty()
    LaunchedEffect(queueFamilyIds, useResolvedQueue) {
        if (useResolvedQueue) viewModel.loadRefresherQueue(queueFamilyIds)
    }
    val resolvedQueue by viewModel.refresherQueue.collectAsState()
    val morningQueue = remember { viewModel.morningModulesSource.value }
    val queue = if (useResolvedQueue) resolvedQueue else morningQueue
    // Resolving (DB read) or genuinely empty — render nothing until the queue lands.
    if (queue.isEmpty()) return

    val initialTarget = remember(queue, targetModuleFamilyId) {
        if (targetModuleFamilyId != null) {
            queue.firstOrNull { it.moduleFamilyId == targetModuleFamilyId }
                ?: queue.firstOrNull()
        } else {
            queue.firstOrNull()
        }
    } ?: return

    // The module currently being drilled. Advances when the CHW taps
    // "Next refresher" on the completion screen.
    var currentFamilyId by rememberSaveable { mutableStateOf(initialTarget.moduleFamilyId) }
    val targetEntity = remember(currentFamilyId) {
        queue.firstOrNull { it.moduleFamilyId == currentFamilyId }
    } ?: initialTarget

    // Next module in the locked queue after the current one (null when last).
    val nextEntity = remember(currentFamilyId) {
        val idx = queue.indexOfFirst { it.moduleFamilyId == currentFamilyId }
        if (idx in 0 until queue.lastIndex) queue[idx + 1] else null
    }

    val cards = remember(targetEntity.cardsJson) { parseLessonCards(targetEntity.cardsJson) }
    val hasCards = cards.isNotEmpty()

    // Whether the module *ships* any quiz at all — read straight from the entity
    // (lang-independent presence check), so the flow can tell a genuinely
    // quiz-less "Learning card" refresher from one that's merely still priming.
    // [hasQuiz] below (the primed/filtered set) gates the quiz UI; this gates
    // whether a quiz phase exists at all.
    val moduleHasQuiz = remember(targetEntity.quizJson) {
        parseInlineQuiz(targetEntity.quizJson).isNotEmpty()
    }

    // Prime the LearnViewModel with the wrong-answer-filtered question set.
    // Triggers exactly once per module per sheet open.
    LaunchedEffect(targetEntity.moduleFamilyId) {
        viewModel.primeRefresherQuiz(targetModuleFamilyId = targetEntity.moduleFamilyId)
    }

    val filteredQuestions by viewModel.filteredQuestionsForRefresher.collectAsState()
    val hasQuiz = filteredQuestions.isNotEmpty()

    var phase by remember { mutableStateOf(RefresherPhase.PHASE_1) }
    var cardIndex by rememberSaveable { mutableIntStateOf(0) }

    val phase1IsQuiz = entryMode == RefresherBottomSheet.EntryMode.QUESTION_FIRST

    val autoSpeak by viewModel.autoSpeakEnabled.collectAsState()

    // Terminal action set surfaced on the LAST lesson card of the modules-screen
    // flow (instead of a separate completion screen). Null on the home-screen
    // card flow, which keeps its plain dismiss-on-done behaviour.
    val refresherActions = if (fromHomeScreen) {
        null
    } else {
        RefresherActions(
            hasNext = nextEntity != null,
            onNextRefresher = {
                // Completing this module clears it from the skipped-badge set.
                MicroCoachingSDK.getInstance().clearRefresherSkipped(currentFamilyId)
                val next = nextEntity
                if (next != null) {
                    // Switch target → LaunchedEffect(targetEntity.moduleFamilyId)
                    // re-primes the quiz for the next module.
                    currentFamilyId = next.moduleFamilyId
                    cardIndex = 0
                    phase = RefresherPhase.PHASE_1
                } else {
                    onDismiss()
                }
            },
            // "I'll do it later" (next queued) and "Done" (no next) both just
            // close the sheet — the refresher stays available in the list.
            onDismiss = onDismiss,
        )
    }

    // End-of-flow fallback: reached only when there are no lesson cards to host
    // the terminal actions (and by the home-screen flow). Clears the just-
    // completed module from the skipped-badge set, then dismisses.
    val endFlow = {
        MicroCoachingSDK.getInstance().clearRefresherSkipped(currentFamilyId)
        phase = RefresherPhase.DONE
    }

    when (phase) {
        RefresherPhase.DONE -> {
            if (fromHomeScreen) {
                MicroCoachingSDK.getInstance().dismissMorningRefresher()
            }
            onDismiss()
        }

        RefresherPhase.PHASE_1 -> {
            if (phase1IsQuiz) {
                // Quiz-less ("Learning card") module opened question-first — there
                // is no quiz phase, so jump straight to the lesson cards.
                if (!moduleHasQuiz) {
                    phase = RefresherPhase.PHASE_2
                    return
                }
                // Quiz phase: wait until primed.
                if (!hasQuiz) return
                SharedQuizInProgressContent(
                    questions = filteredQuestions,
                    viewModel = viewModel.learnViewModel,
                    onAllAnswered = {
                        // deferSync = true: the sheet still has lesson cards
                        // ahead. The dismiss handler will flush+sync once the
                        // CHW finishes the full experience — without this,
                        // refilterMorningModules races the recomposition and
                        // can blank PHASE_2 out.
                        viewModel.learnViewModel.finishQuiz(deferSync = true)
                        if (hasCards) {
                            phase = RefresherPhase.PHASE_2
                            cardIndex = 0
                        } else {
                            endFlow()
                        }
                    },
                    modifier = modifier,
                    onClose = onDismiss,
                    optionContainerColor = QuizOptionSurface,
                )
            } else {
                // CARDS_FIRST: lesson cards in phase 1.
                if (!hasCards) {
                    phase = RefresherPhase.PHASE_2
                    return
                }
                RefresherCardSlide(
                    cards = cards,
                    cardIndex = cardIndex,
                    onNext = {
                        if (cardIndex < cards.size - 1) cardIndex++
                        else { phase = RefresherPhase.PHASE_2; cardIndex = 0 }
                    },
                    modifier = modifier,
                    autoSpeakEnabled = autoSpeak,
                    onToggleAutoSpeak = viewModel::toggleAutoSpeak,
                    onSpeak = { text, onDone -> viewModel.speakAloud(text, onDone) },
                    onStopSpeak = viewModel::stopSpeaking,
                )
            }
        }

        RefresherPhase.PHASE_2 -> {
            if (phase1IsQuiz) {
                // Cards phase — the LAST card carries the refresher completion
                // actions (Next refresher / I'll do it later / Done) in place of
                // the usual forward Next footer.
                if (!hasCards) { endFlow(); return }
                RefresherCardSlide(
                    cards = cards,
                    cardIndex = cardIndex,
                    onNext = {
                        if (cardIndex < cards.size - 1) cardIndex++
                        else endFlow()
                    },
                    modifier = modifier,
                    autoSpeakEnabled = autoSpeak,
                    onToggleAutoSpeak = viewModel::toggleAutoSpeak,
                    onSpeak = { text, onDone -> viewModel.speakAloud(text, onDone) },
                    onStopSpeak = viewModel::stopSpeaking,
                    refresherActions = refresherActions,
                )
            } else {
                // Quiz-less ("Learning card") module — no quiz tail, just finish.
                if (!moduleHasQuiz) { endFlow(); return }
                // Quiz phase (after cards) — wait until primed.
                if (!hasQuiz) return
                SharedQuizInProgressContent(
                    questions = filteredQuestions,
                    viewModel = viewModel.learnViewModel,
                    // Reached only on the home-card flow (no terminal footer):
                    // finish + dismiss. The modules-screen flow ends via the
                    // last-question footer below instead.
                    onAllAnswered = {
                        viewModel.learnViewModel.finishQuiz(deferSync = true)
                        endFlow()
                    },
                    modifier = modifier,
                    onClose = onDismiss,
                    optionContainerColor = QuizOptionSurface,
                    // Quiz is the last phase in CARDS_FIRST, so the completion
                    // actions ("Next refresher" / "I'll do it later" / "Done")
                    // ride on the last question's feedback instead of a separate
                    // screen. Modules-screen flow only — the home card has no
                    // queue (refresherActions == null) and keeps the plain finish.
                    lastQuestionFooter = refresherActions?.let { actions ->
                        {
                            val finishThisQuiz = {
                                viewModel.learnViewModel.finishQuiz(deferSync = true)
                                MicroCoachingSDK.getInstance().clearRefresherSkipped(currentFamilyId)
                            }
                            RefresherTerminalActions(
                                actions = RefresherActions(
                                    hasNext = actions.hasNext,
                                    onNextRefresher = { finishThisQuiz(); actions.onNextRefresher() },
                                    onDismiss = { finishThisQuiz(); actions.onDismiss() },
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * Terminal actions surfaced on the LAST lesson card of a modules-screen
 * refresher (in place of a standalone completion screen):
 *  - [hasNext] — whether another refresher is queued after this one. Drives the
 *    button layout: true → "Next refresher" + "I'll do it later"; false → "Done".
 *  - [onNextRefresher] — advance to the next module in the queue,
 *  - [onDismiss] — close the sheet ("I'll do it later" / "Done").
 */
internal data class RefresherActions(
    val hasNext: Boolean,
    val onNextRefresher: () -> Unit,
    val onDismiss: () -> Unit,
)

private enum class RefresherPhase { PHASE_1, PHASE_2, DONE }

