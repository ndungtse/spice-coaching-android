package com.medtroniclabs.microcoaching.ui.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceGreenDark
import com.medtroniclabs.microcoaching.ui.theme.SurfaceBackground

/**
 * Scrollable lesson reference screen.
 *
 * Renders real content from [LearnModule.body], [LearnModule.warningSigns],
 * and [LearnModule.nextStep] — all populated from the seed JSON (bangla_card).
 *
 * @param uiState Must be [LearnUiState.LessonContent] for content to render.
 * @param onContinueToQuiz Called when the CHW taps "Continue to Quiz".
 * @param onBack Called when the CHW taps "Back".
 */
@Composable
fun LessonContentScreen(
    uiState: LearnUiState,
    onContinueToQuiz: () -> Unit,
    onBack: () -> Unit,
) {
    val module = (uiState as? LearnUiState.LessonContent)?.module

    if (module == null) {
        CircularProgressIndicator()
        return
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBackground)
            .verticalScroll(scrollState)
            .padding(24.dp),
    ) {
        Text(
            text = module.title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = SpiceGreenDark,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.learn_reference_guide),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF666666),
        )

        Spacer(Modifier.height(24.dp))

        // Main body content
        if (module.body.isNotBlank()) {
            ContentCard(content = module.body)
            Spacer(Modifier.height(16.dp))
        }

        // Warning signs
        if (module.warningSigns.isNotEmpty()) {
            KeyPoint(
                title = stringResource(R.string.learn_warning_signs),
                body = module.warningSigns.joinToString("\n") { "• $it" },
            )
            Spacer(Modifier.height(8.dp))
        }

        // Next step / action
        if (module.nextStep.isNotBlank()) {
            KeyPoint(
                title = stringResource(R.string.learn_next_step),
                body = module.nextStep,
            )
            Spacer(Modifier.height(8.dp))
        }

        // Referral destination
        if (!module.referralDestination.isNullOrBlank()) {
            KeyPoint(
                title = stringResource(R.string.learn_referral_destination),
                body = module.referralDestination,
            )
        }

        Spacer(Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.common_back))
            }

            Button(
                onClick = onContinueToQuiz,
                modifier = Modifier.weight(2f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(stringResource(R.string.learn_continue_quiz), fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ContentCard(content: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
            .padding(16.dp),
    ) {
        content.lines().forEach { line ->
            if (line.isNotBlank()) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF1A1A1A),
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun KeyPoint(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFEEF7F2), RoundedCornerShape(8.dp))
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = SpiceGreenDark,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF444444),
        )
    }
}
