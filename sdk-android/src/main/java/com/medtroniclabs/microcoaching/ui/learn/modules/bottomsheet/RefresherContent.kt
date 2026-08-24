package com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.domain.refresher.RefresherKind
import com.medtroniclabs.microcoaching.ui.learn.finishQuiz
import com.medtroniclabs.microcoaching.ui.learn.modules.QuickLearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.parseInlineQuiz
import com.medtroniclabs.microcoaching.ui.learn.parseLessonCards
import com.medtroniclabs.microcoaching.ui.theme.CoachingTheme

/**
 * Full refresher experience inside [RefresherBottomSheet].
 *
 * The phases run from the refresher's own kind, so the sheet delivers exactly what the tile
 * the CHW tapped advertised:
 *
 *  - [RefresherKind.QUIZ] — the drill only, no card phase.
 *  - [RefresherKind.MICROCOACHING] — lesson cards, then the drill.
 *  - [RefresherKind.LEARNING] — lesson cards only; finishing them records a completion so
 *    the refresher retires (nothing else in this flow writes one).
 *
 * [targetModuleFamilyId] — when set (RefresherList tile flow), the content for
 * that specific module is shown instead of the first morning module.
 *
 * When [fromHomeScreen] is true, Done dismisses the morning card banner via
 * [MicroCoachingSDK.dismissMorningRefresher] and no terminal actions are offered.
 */
@Composable
fun RefresherContent(
    viewModel: QuickLearnViewModel,
    fromHomeScreen: Boolean = false,
    targetModuleFamilyId: String? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    queueFamilyIds: List<String> = emptyList(),
) {
    // The queue is locked at first composition. `morningModulesSource` is reactive and
    // drops a module the moment its last question is answered, which would blank the
    // sheet mid-flow; once opened, the lesson lives on its own timeline.
    // [queueFamilyIds] is exactly the list the CHW saw, so "Next refresher" chains only
    // through those. The home-screen flow passes none and uses the morning set.
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

    // Prime the drill set and the kind for this module. Once per module per sheet open.
    LaunchedEffect(targetEntity.moduleFamilyId) {
        viewModel.primeRefresherQuiz(targetModuleFamilyId = targetEntity.moduleFamilyId)
    }

    val filteredQuestions by viewModel.filteredQuestionsForRefresher.collectAsState()
    val primedKind by viewModel.primedKind.collectAsState()

    // Fall back to the module's shape when the family isn't on the refresher list (the
    // home-screen flow reaches modules the classifier never saw).
    val moduleHasQuiz = remember(targetEntity.quizJson) {
        parseInlineQuiz(targetEntity.quizJson).isNotEmpty()
    }
    val kind = primedKind ?: when {
        moduleHasQuiz && hasCards -> RefresherKind.MICROCOACHING
        moduleHasQuiz -> RefresherKind.QUIZ
        else -> RefresherKind.LEARNING
    }
    val runsCards = kind != RefresherKind.QUIZ && hasCards
    val runsQuiz = kind != RefresherKind.LEARNING

    // The quiz phase can't render before priming lands; the card phase has its content
    // up front. Gating on the question set alone would hang a cards-only refresher,
    // whose set is empty by design.
    if (runsQuiz && filteredQuestions.isEmpty()) return

    var phase by remember { mutableStateOf(RefresherPhase.PHASE_1) }
    var cardIndex by rememberSaveable { mutableIntStateOf(0) }

    // Bumped on retry to re-key the quiz composable, which locks answered questions in
    // review mode — re-arming the list alone would redisplay the finished attempt.
    var attemptKey by remember { mutableIntStateOf(0) }

    val autoSpeak by viewModel.autoSpeakEnabled.collectAsState()

    // Re-drill the questions just attempted. restartRefresherQuiz replays the SAME set
    // (reshuffled) rather than re-filtering, which would shrink it as answers land. No
    // finishQuiz() here — per-answer telemetry has already been written, and the eventual
    // "Next refresher" / "Done" still finishes the module.
    val retryQuiz: (() -> Unit)? = if (runsQuiz) {
        {
            viewModel.restartRefresherQuiz()
            attemptKey++
            phase = if (runsCards) RefresherPhase.PHASE_2 else RefresherPhase.PHASE_1
        }
    } else {
        null
    }

    // Reading the cards is the only way a cards-only refresher can ever be finished, and
    // this sheet is the only place it happens — the lesson player's completion write is
    // unreachable from here. Without it the refresher would re-surface forever.
    val recordCardsRead = {
        if (kind == RefresherKind.LEARNING) {
            MicroCoachingSDK.getInstance()
                .onModuleCardsCompleted(targetEntity.moduleFamilyId, targetEntity.moduleId)
        }
    }

    // Terminal action set surfaced at the end of the modules-screen flow (instead of a
    // separate completion screen). Null on the home-screen card flow, which keeps its
    // plain dismiss-on-done behaviour.
    val refresherActions = if (fromHomeScreen) {
        null
    } else {
        RefresherActions(
            hasNext = nextEntity != null,
            onNextRefresher = {
                // Completing this module clears it from the skipped-badge set.
                recordCardsRead()
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
            onRetryQuiz = retryQuiz,
        )
    }

    val endFlow = {
        recordCardsRead()
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

        // Cards, when this kind runs them. They carry the terminal actions themselves for
        // a cards-only refresher, which has no quiz phase to host them.
        RefresherPhase.PHASE_1 -> {
            if (!runsCards) {
                phase = RefresherPhase.PHASE_2
                return
            }
            val cardsAreLast = !runsQuiz
            RefresherCardSlide(
                cards = cards,
                cardIndex = cardIndex,
                onNext = {
                    if (cardIndex < cards.size - 1) cardIndex++
                    else if (cardsAreLast) endFlow()
                    else { phase = RefresherPhase.PHASE_2; cardIndex = 0 }
                },
                modifier = modifier,
                autoSpeakEnabled = autoSpeak,
                onToggleAutoSpeak = viewModel::toggleAutoSpeak,
                onSpeak = { text, onDone -> viewModel.speakAloud(text, onDone) },
                onStopSpeak = viewModel::stopSpeaking,
                refresherActions = if (cardsAreLast) {
                    refresherActions?.copy(
                        onNextRefresher = { recordCardsRead(); refresherActions.onNextRefresher() },
                        onDismiss = { recordCardsRead(); refresherActions.onDismiss() },
                    )
                } else {
                    null
                },
            )
        }

        RefresherPhase.PHASE_2 -> {
            if (!runsQuiz) { endFlow(); return }
            key(attemptKey) {
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
                    optionContainerColor = CoachingTheme.colors.quizOptionSurface,
                    // The quiz is the last phase, so the completion actions ride on the
                    // last question's feedback rather than a separate screen. Null on the
                    // home-card flow, which has no queue and keeps the plain finish.
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
                                    // Retry is the one action that must NOT finish the
                                    // module — it starts another attempt on it.
                                    onRetryQuiz = actions.onRetryQuiz,
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
 * Terminal actions surfaced at the end of a modules-screen refresher (in place of a
 * standalone completion screen):
 *  - [hasNext] — whether another refresher is queued after this one. Drives the
 *    button layout: true → "Next refresher" + "I'll do it later"; false → "Done".
 *  - [onNextRefresher] — advance to the next module in the queue,
 *  - [onDismiss] — close the sheet ("I'll do it later" / "Done"),
 *  - [onRetryQuiz] — re-drill the questions just attempted; null on flows with no
 *    quiz to retry, which hides the button.
 */
internal data class RefresherActions(
    val hasNext: Boolean,
    val onNextRefresher: () -> Unit,
    val onDismiss: () -> Unit,
    val onRetryQuiz: (() -> Unit)? = null,
)

private enum class RefresherPhase { PHASE_1, PHASE_2, DONE }

