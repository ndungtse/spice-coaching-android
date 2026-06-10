package com.medtroniclabs.microcoaching.ui.screens.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.chat.ChatUiState

/**
 * The "you need to download stuff before chat works" surface — shown when the
 * Gemma AI model isn't on disk yet. Lists the AI model (required) and the
 * Bengali voice model (optional) as a compact two-card stack with per-item
 * actions, plus a "Download both" CTA that starts the AI download now and
 * queues the voice download to follow.
 *
 * The host (chat surface) owns the chain logic via [onRequestBothDownload];
 * this composable is pure UI.
 */
@Composable
fun ModelNotReadyContent(
    uiState: ChatUiState.ModelNotReady,
    voiceModelState: DownloadItemUiState,
    aiModelPresent: Boolean,
    onRequestAiDownload: () -> Unit,
    onPauseAiDownload: () -> Unit,
    onResumeAiDownload: () -> Unit,
    onCancelAiDownload: () -> Unit,
    onRequestVoiceDownload: () -> Unit,
    onCancelVoiceDownload: () -> Unit,
    onRequestBothDownload: () -> Unit,
    onClose: () -> Unit,
    showCloseIcon: Boolean,
) {
    val aiItemState = uiState.toAiDownloadItemState(modelPresent = aiModelPresent)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // ── Title + lead-in ──────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.chat_ai_coaching_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.chat_model_download_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(20.dp))

        // ── Per-item cards ───────────────────────────────────────────────────
        DownloadItemCard(
            icon = DownloadItemIcon.AiSparkle,
            title = stringResource(R.string.download_card_ai_title),
            sizeLabel = stringResource(R.string.download_card_ai_size),
            isRequired = true,
            state = aiItemState,
            onDownload = onRequestAiDownload,
            onPause = onPauseAiDownload,
            onResume = onResumeAiDownload,
            onCancel = onCancelAiDownload,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        DownloadItemCard(
            icon = DownloadItemIcon.Microphone,
            title = stringResource(R.string.download_card_voice_title),
            sizeLabel = stringResource(R.string.download_card_voice_size),
            isRequired = false,
            state = voiceModelState,
            onDownload = onRequestVoiceDownload,
            // Voice model has no pause/resume in v1 — the sherpa worker
            // tar.bz2 extract isn't trivially resumable. Cancel wipes.
            onCancel = onCancelVoiceDownload,
            modifier = Modifier.fillMaxWidth(),
        )

        // ── "Download both" CTA ──────────────────────────────────────────────
        val showBothCta = aiItemState is DownloadItemUiState.Idle &&
            (voiceModelState is DownloadItemUiState.Idle ||
                voiceModelState is DownloadItemUiState.Failed)
        if (showBothCta) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRequestBothDownload,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.download_both))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.download_both_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )
        }

        // ── Dismiss button (only inside the bottom-sheet host) ───────────────
        // When something is actively downloading or paused mid-flight, label
        // the dismiss action "Continue in background" — it more accurately
        // describes what happens (the worker keeps running while the sheet
        // closes). Falls back to "Maybe later" when nothing is in flight.
        if (showCloseIcon) {
            Spacer(Modifier.height(16.dp))
            val dismissLabelRes = if (aiItemState.isInFlight() || voiceModelState.isInFlight()) {
                R.string.chat_download_continue_in_background
            } else {
                R.string.chat_download_maybe_later
            }
            TextButton(onClick = onClose) {
                Text(
                    text = stringResource(dismissLabelRes),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * True when the download is actively running (or queued / paused / extracting)
 * — i.e. closing the sheet means the worker keeps going. Done / Idle / Failed
 * end states return false so the sheet falls back to the "Maybe later" label.
 */
private fun DownloadItemUiState.isInFlight(): Boolean = when (this) {
    is DownloadItemUiState.Downloading,
    is DownloadItemUiState.Preparing,
    is DownloadItemUiState.Paused,
    is DownloadItemUiState.Extracting -> true
    is DownloadItemUiState.Idle,
    is DownloadItemUiState.Done,
    is DownloadItemUiState.Failed -> false
}
