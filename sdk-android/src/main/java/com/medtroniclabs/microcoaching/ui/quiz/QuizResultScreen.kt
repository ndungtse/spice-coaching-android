package com.medtroniclabs.microcoaching.ui.quiz

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.LearnUiState
import com.medtroniclabs.microcoaching.ui.theme.CoachingTheme

// Palette (kept local to this screen to match the minimal result design).

/**
 * Quiz result screen — minimal redesign.
 *
 * Top to bottom: the score percentage, the badge label as a heading, a score
 * summary line, the XP-earned badge, a flat "Your answers" list (a ✓/✗ chip per
 * question, no explanations), and a pinned bottom bar with two pill buttons:
 * - **Try Again** (orange) — only when [canRetry] is `true` AND [onTryAgain] is
 *   non-null (the retry-window gate, see
 *   [com.medtroniclabs.microcoaching.ui.learn.QuizRetryGate]).
 * - **Done** (primary/blue) — always shown; navigates back to the module list.
 *
 * @param uiState Must be [LearnUiState.QuizResult] for content to render.
 * @param onNextModule Called when the CHW taps "Done" — navigate back to the module list.
 * @param onBackToSpice Retained for API compatibility; no longer surfaced as a button
 *   (the "Back to HOME" action was dropped from this screen).
 * @param onTryAgain Invoked when the CHW taps "Try Again". When null, no retry is wired.
 * @param canRetry Gate signal; when `false`, hides "Try Again" even if [onTryAgain] is set.
 * @param isRefresherQuiz Retained for API compatibility; the primary CTA is now always "Done".
 */
@Composable
fun QuizResultScreen(
    uiState: LearnUiState,
    onNextModule: () -> Unit,
    onBackToSpice: () -> Unit,
    onTryAgain: (() -> Unit)? = null,
    canRetry: Boolean = false,
    isRefresherQuiz: Boolean = false,
) {
    if (uiState !is LearnUiState.QuizResult) {
        CircularProgressIndicator()
        return
    }

    // Quiz just completed → refetch the morning refreshers: the backend re-runs its
    // gap algorithm and the on-device generator re-evaluates local progress, so a
    // finished/mastered module updates without leaving this screen. Keyed on the
    // completed module so it fires once per result, not on recompositions.
    LaunchedEffect(uiState.completedModuleFamilyId) {
        MicroCoachingSDK.getInstance().refreshRefreshers()
    }

    // Try-again is gated by [canRetry] (the retry-window gate). When the
    // gate is open and a retry callback is wired, we surface the button.
    val showTryAgain = canRetry && onTryAgain != null

    // Intercept system back — pressing it lands on ModuleReady with stale state otherwise,
    // showing a blank screen. Route system back through the same path as the "Done" CTA so
    // state resets cleanly.
    BackHandler(onBack = onNextModule)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { Spacer(Modifier.height(48.dp)) }

            // Score percentage — the hero number.
            item {
                Text(
                    text = "${uiState.scorePercent}%",
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = CoachingTheme.colors.warning,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { Spacer(Modifier.height(4.dp)) }

            // Badge label as the heading (e.g. "Keep Practising" / "Learner" / "Expert").
            item {
                Text(
                    text = uiState.badgeLabel,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = CoachingTheme.colors.textStrong,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            item {
                Text(
                    text = stringResource(
                        R.string.quiz_score_summary,
                        uiState.correctCount,
                        uiState.totalCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // XP badge — dynamic reward from the learning-points config.
            // Points/XP display temporarily disabled — UI only; [XpBadge] + earnedXp logic retained.
            // item { Spacer(Modifier.height(24.dp)) }
            // item { XpBadge(xp = uiState.earnedXp) }

            // ── Your answers ─────────────────────────────────────────────────
            if (uiState.questions.isNotEmpty()) {
                item { Spacer(Modifier.height(24.dp)) }

                item {
                    Text(
                        text = stringResource(R.string.quiz_your_answers),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item { Spacer(Modifier.height(4.dp)) }

                itemsIndexed(uiState.questions) { idx, question ->
                    val isCorrect = uiState.answers[idx] == question.correctIndex
                    AnswerRow(questionText = question.questionText, isCorrect = isCorrect)
                    if (idx < uiState.questions.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }

        // Pinned bottom action bar — Try Again (orange) over Done (primary).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .navigationBarsPadding(),
        ) {
            if (showTryAgain) {
                Button(
                    onClick = { onTryAgain?.invoke() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CoachingTheme.colors.warning,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.quiz_try_again),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Button(
                onClick = onNextModule,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = stringResource(R.string.quiz_done),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun XpBadge(xp: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, CoachingTheme.colors.rewardOutline), RoundedCornerShape(16.dp))
            .background(CoachingTheme.colors.rewardContainer, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = CoachingTheme.colors.warning,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = stringResource(R.string.quick_learn_xp_reward, xp),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = CoachingTheme.colors.warning,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = stringResource(R.string.quiz_xp_module_caption),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AnswerRow(
    questionText: String,
    isCorrect: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    color = if (isCorrect) CoachingTheme.colors.successContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isCorrect) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                tint = if (isCorrect) CoachingTheme.colors.success else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(
            text = questionText,
            style = MaterialTheme.typography.bodyMedium,
            color = CoachingTheme.colors.textStrong,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
