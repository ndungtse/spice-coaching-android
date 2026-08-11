package com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.CircularScoreArc
import com.medtroniclabs.microcoaching.ui.learn.LearnUiState
import com.medtroniclabs.microcoaching.ui.learn.LearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.finishQuiz
import com.medtroniclabs.microcoaching.ui.learn.QuizQuestion

/**
 * Refresher quiz UI rendered inside [RefresherQuizBottomSheet]. Drives off
 * [LearnViewModel.uiState] — supports two terminal states:
 *
 * 1. [LearnUiState.QuizInProgress] → progress bar + question + answers +
 *    in-place [AnswerFeedbackOverlay].
 * 2. [LearnUiState.QuizResult] → [CircularScoreArc] + "Back to modules" CTA
 *    that closes the sheet via [onDismiss]. Optional [onNextRefresher] adds a
 *    "Next Refresher →" button above the back button.
 *
 * The sheet does not navigate to a separate route — everything stays inside
 * the partial-height container, matching the design.
 */
@Composable
fun RefresherQuizContent(
    viewModel: LearnViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onNextRefresher: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    android.util.Log.d(
        "RefresherQuizContent",
        "render uiState=${state::class.simpleName}" +
            (if (state is LearnUiState.QuizInProgress) " questions=${(state as LearnUiState.QuizInProgress).questions.size}" else ""),
    )
    when (val s = state) {
        is LearnUiState.QuizInProgress -> {
            if (s.questions.isEmpty()) {
                EmptyQuizView(onDismiss = onDismiss, modifier = modifier)
            } else {
                QuizInProgressView(
                    questions = s.questions,
                    viewModel = viewModel,
                    onAllAnswered = { viewModel.finishQuiz() },
                    onClose = onDismiss,
                    modifier = modifier,
                )
            }
        }
        is LearnUiState.QuizResult -> QuizResultView(
            scorePercent = s.scorePercent,
            correctCount = s.correctCount,
            totalCount = s.totalCount,
            onDismiss = onDismiss,
            onNextRefresher = onNextRefresher,
            modifier = modifier,
        )
        else -> EmptyQuizView(onDismiss = onDismiss, modifier = modifier)
    }
}

@Composable
private fun EmptyQuizView(
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.refresher_quiz_no_questions),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF6B7280),
        )
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.refresher_quiz_back))
        }
    }
}

@Composable
private fun QuizInProgressView(
    questions: List<QuizQuestion>,
    viewModel: LearnViewModel,
    onAllAnswered: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    SharedQuizInProgressContent(
        questions = questions,
        viewModel = viewModel,
        onAllAnswered = onAllAnswered,
        modifier = modifier,
        onClose = onClose,
    )
}

@Composable
private fun QuizResultView(
    scorePercent: Int,
    correctCount: Int,
    totalCount: Int,
    onDismiss: () -> Unit,
    modifier: Modifier,
    onNextRefresher: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.padding(top = 8.dp)) {
            CircularScoreArc(scorePercent = scorePercent)
        }
        Text(
            text = stringResource(R.string.quiz_correct_count, correctCount, totalCount),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        if (onNextRefresher != null) {
            Button(
                onClick = onNextRefresher,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.quiz_next_refresher))
            }
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.refresher_quiz_back))
        }
    }

    // Side-effect: auto-clear pending state on entry to result.
    LaunchedEffect(scorePercent, correctCount, totalCount) {
        // No-op — kept for future haptic / sound hooks.
    }
}

