package com.medtroniclabs.microcoaching.ui.quiz

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.medtroniclabs.microcoaching.ui.common.CircularScoreArc
import com.medtroniclabs.microcoaching.ui.learn.LearnUiState
import com.medtroniclabs.microcoaching.ui.theme.SurfaceBackground

/**
 * Quiz result screen.
 *
 * Shows the CHW their score, badge, per-question review with explanations, and options to:
 * - **Try Again** (outlined) — only when [canRetry] is `true` AND [onTryAgain] is non-null.
 *   Re-enables the retry CTA per PM direction (DM, 2026-06): within the 7-day publication
 *   window the CHW can retake the quiz freely; after that window the gate
 *   ([com.medtroniclabs.microcoaching.ui.learn.QuizRetryGate]) closes and this button
 *   is hidden.
 * - **More Modules / Back to Refreshers** (primary) — always shown. Navigates back to
 *   the module list.
 * - **Back to HOME** (outlined) — always shown. Exits the SDK overlay.
 *
 * Previous policy ("one attempt per session, Try Again suppressed app-wide") has been
 * replaced by the retry-window gate. Callers supply both [onTryAgain] and [canRetry];
 * the button only renders when both are present/true. Removing the gate later (see
 * [com.medtroniclabs.microcoaching.ui.learn.QuizRetryGate]'s "How to remove") means
 * callers should pass `canRetry = false` everywhere to suppress the button again.
 *
 * @param uiState Must be [LearnUiState.QuizResult] for content to render.
 * @param onNextModule Called when the CHW taps "More Modules" — navigate back to module list.
 * @param onBackToSpice Called when the CHW taps "Back to HOME" — `activity.finish()`.
 * @param onTryAgain Invoked when the CHW taps "Try Again". When null, no retry is wired
 *   for this entry path and the button is hidden regardless of [canRetry].
 * @param canRetry Gate signal — derived in [com.medtroniclabs.microcoaching.ui.flow.CoachingNavGraph]
 *   from `!QuizRetryGate.isRetryWindowClosed(module)`. When `false`, hides the
 *   "Try Again" button even if [onTryAgain] is supplied.
 * @param isRefresherQuiz When true, the primary "More Modules" label changes to
 *   "Back to Refreshers" to better reflect the refresher context.
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

    // Try-again is gated by [canRetry] (the retry-window gate). When the
    // gate is open and a retry callback is wired, we surface the button.
    val showTryAgain = canRetry && onTryAgain != null

    // Intercept system back — pressing it lands on ModuleReady with stale state otherwise,
    // showing a blank screen. Route system back through the same path as the "More Modules" /
    // "Back to Refreshers" CTA so state resets cleanly.
    BackHandler(onBack = onNextModule)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBackground)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { Spacer(Modifier.height(32.dp)) }

        item {
            Text(
                text = stringResource(R.string.quiz_complete_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF0A3D27),
            )
        }

        item { Spacer(Modifier.height(32.dp)) }

        item {
            CircularScoreArc(
                scorePercent = uiState.scorePercent,
                size = 160.dp,
            )
        }

        item { Spacer(Modifier.height(16.dp)) }

        item {
            Text(
                text = stringResource(R.string.quiz_correct_count, uiState.correctCount, uiState.totalCount),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF444444),
            )
        }

        item { Spacer(Modifier.height(32.dp)) }

        // Badge
        item {
            Column(
                modifier = Modifier
                    .background(Color(0xFFD7F0E5), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "🏅", fontSize = 40.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = uiState.badgeLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF0A3D27),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.quiz_badge_earned),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF1B6B4A),
                )
            }
        }

        // Per-question review section
        if (uiState.questions.isNotEmpty()) {
            item { Spacer(Modifier.height(24.dp)) }

            item {
                Text(
                    text = stringResource(R.string.quiz_review_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF0A3D27),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            itemsIndexed(uiState.questions) { idx, question ->
                val selected = uiState.answers[idx]
                val isCorrect = selected == question.correctIndex
                QuizReviewRow(
                    questionNumber = idx + 1,
                    questionText = question.questionText,
                    isCorrect = isCorrect,
                    explanation = question.explanation,
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                // Full-width "Try Again" sits on its own row above the
                // Back/Next pair. Only rendered when the retry-window gate is
                // open. Generous vertical contentPadding gives it a chunkier
                // hit area to balance the visual weight of the row below.
                if (showTryAgain) {
                    Button(
                        onClick = { onTryAgain?.invoke() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC23C02),
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Tight horizontal contentPadding + maxLines=1/softWrap=false
                    // keeps Bengali labels (e.g. "রিফ্রেশারে ফিরুন") on a single line
                    // even on narrow screens; falls back to an ellipsis only if the
                    // text genuinely can't fit.
                    OutlinedButton(
                        onClick = onBackToSpice,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(50.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.quiz_back_to_home),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Button(
                        onClick = onNextModule,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(50.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(
                            text = if (isRefresherQuiz) {
                                stringResource(R.string.quiz_back_to_refreshers)
                            } else {
                                stringResource(R.string.quiz_more_modules)
                            },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun QuizReviewRow(
    questionNumber: Int,
    questionText: String,
    isCorrect: Boolean,
    explanation: String,
) {
    val bg = if (isCorrect) Color(0xFFD7F0E5) else Color(0xFFFFE8E8)
    val indicator = if (isCorrect) "✓" else "✗"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            text = "$indicator  $questionNumber. $questionText",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        if (explanation.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF444444),
            )
        }
    }
}
