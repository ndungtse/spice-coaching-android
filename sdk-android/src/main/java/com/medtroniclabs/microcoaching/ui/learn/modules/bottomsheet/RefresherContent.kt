package com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.LessonCard
import com.medtroniclabs.microcoaching.ui.learn.modules.QuickLearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.parseLessonCards
import com.medtroniclabs.microcoaching.ui.common.translatedText
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.content.richtext.bodyToSpokenText
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownDefaults
import com.medtroniclabs.microcoaching.ui.richtext.RichCardBody

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
    entryMode: RefresherBottomSheet.EntryMode = RefresherBottomSheet.EntryMode.QUESTION_FIRST,
    targetModuleFamilyId: String? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resolve the target module ONCE at first composition and hold onto it
    // for the lifetime of the sheet. The underlying `morningModulesSource`
    // (sdk._morningModules) is reactive — every coaching_event insert triggers
    // `refilterMorningModules`, which may drop this module from the list the
    // moment its last open question is answered correctly. Without locking,
    // PHASE_2 (lesson cards) would blank out mid-flow because targetEntity
    // becomes null. Once the CHW has opened the sheet, the lesson lives on
    // its own timeline; background refilters shouldn't yank it away.
    val initialModules = viewModel.morningModulesSource.value
    val targetEntity = remember(targetModuleFamilyId) {
        if (targetModuleFamilyId != null) {
            initialModules.firstOrNull { it.moduleFamilyId == targetModuleFamilyId }
                ?: initialModules.firstOrNull()
        } else {
            initialModules.firstOrNull()
        }
    } ?: return

    val cards = remember(targetEntity.cardsJson) { parseLessonCards(targetEntity.cardsJson) }
    val hasCards = cards.isNotEmpty()

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

    when (phase) {
        RefresherPhase.DONE -> {
            if (fromHomeScreen) {
                MicroCoachingSDK.getInstance().dismissMorningRefresher()
            }
            onDismiss()
        }

        RefresherPhase.PHASE_1 -> {
            if (phase1IsQuiz) {
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
                            phase = RefresherPhase.DONE
                        }
                    },
                    modifier = modifier,
                    onClose = onDismiss,
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
                    onPrevious = { if (cardIndex > 0) cardIndex-- },
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
                // Cards phase
                if (!hasCards) { phase = RefresherPhase.DONE; return }
                RefresherCardSlide(
                    cards = cards,
                    cardIndex = cardIndex,
                    onNext = {
                        if (cardIndex < cards.size - 1) cardIndex++
                        else phase = RefresherPhase.DONE
                    },
                    onPrevious = { if (cardIndex > 0) cardIndex-- },
                    modifier = modifier,
                    autoSpeakEnabled = autoSpeak,
                    onToggleAutoSpeak = viewModel::toggleAutoSpeak,
                    onSpeak = { text, onDone -> viewModel.speakAloud(text, onDone) },
                    onStopSpeak = viewModel::stopSpeaking,
                )
            } else {
                // Quiz phase (after cards) — wait until primed.
                if (!hasQuiz) return
                SharedQuizInProgressContent(
                    questions = filteredQuestions,
                    viewModel = viewModel.learnViewModel,
                    onAllAnswered = {
                        // CARDS_FIRST quiz tail — same deferral as
                        // QUESTION_FIRST so the dismiss handler owns the
                        // outbound + inbound sync chain.
                        viewModel.learnViewModel.finishQuiz(deferSync = true)
                        phase = RefresherPhase.DONE
                    },
                    modifier = modifier,
                    onClose = onDismiss,
                )
            }
        }
    }
}

// ── Cards phase ───────────────────────────────────────────────────────────────

@Composable
private fun RefresherCardSlide(
    cards: List<LessonCard>,
    cardIndex: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier,
    autoSpeakEnabled: Boolean = false,
    onToggleAutoSpeak: () -> Unit = {},
    onSpeak: (text: String, onDone: () -> Unit) -> Unit = { _, _ -> },
    onStopSpeak: () -> Unit = {},
) {
    if (cards.isEmpty()) return
    val safeIndex = cardIndex.coerceIn(0, cards.size - 1)
    val card = cards[safeIndex]
    val isLast = safeIndex == cards.size - 1
    val isFirst = safeIndex == 0
    val titleText = translatedText(bn = card.titleBn, en = card.titleEn)
    val bodyText = translatedText(bn = card.bodyBn, en = card.bodyEn)

    // Auto-speak the current card; advance via onNext() on completion (except last card).
    // Speaks "<title>. <body>" so the CHW hears the heading before the content.
    LaunchedEffect(safeIndex, autoSpeakEnabled) {
        if (autoSpeakEnabled) {
            val spokenBody = bodyToSpokenText(bodyText)
            val spoken =
                if (titleText.isBlank()) spokenBody
                else "$titleText. $spokenBody"
            if (spoken.isNotBlank()) {
                onSpeak(spoken) {
                    if (!isLast) onNext()
                }
            }
        } else {
            onStopSpeak()
        }
    }
    DisposableEffect(Unit) {
        onDispose { onStopSpeak() }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(safeIndex) {
        scrollState.scrollTo(0)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = translatedText(bn = card.titleBn, en = card.titleEn),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF101828),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleAutoSpeak) {
                Icon(
                    imageVector = if (autoSpeakEnabled) Icons.AutoMirrored.Filled.VolumeUp
                    else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = stringResource(
                        if (autoSpeakEnabled) R.string.lesson_player_auto_speak_on
                        else R.string.lesson_player_auto_speak_off,
                    ),
                    tint = SpiceBlue,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { (safeIndex + 1f) / cards.size },
            modifier = Modifier.fillMaxWidth(),
            color = SpiceBlue,
            trackColor = Color(0xFFE4E7EC),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${safeIndex + 1} / ${cards.size}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF667085),
            modifier = Modifier.align(Alignment.End),
        )
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(rememberNestedScrollInteropConnection())
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (bodyText.isNotBlank()) {
                RichCardBody(
                    raw = bodyText,
                    modifier = Modifier.fillMaxWidth(),
                    style = MarkdownDefaults.style(textColor = Color(0xFF344054)),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { if (!isFirst) onPrevious() },
                enabled = !isFirst,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                )
                Spacer(Modifier.padding(start = 4.dp))
                Text(
                    text = stringResource(R.string.lesson_player_previous),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpiceBlue),
            ) {
                Text(
                    text = if (isLast) stringResource(R.string.refresher_card_done)
                           else stringResource(R.string.lesson_player_next),
                    fontWeight = FontWeight.SemiBold,
                )
                if (!isLast) {
                    Spacer(Modifier.padding(start = 4.dp))
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

private enum class RefresherPhase { PHASE_1, PHASE_2, DONE }

