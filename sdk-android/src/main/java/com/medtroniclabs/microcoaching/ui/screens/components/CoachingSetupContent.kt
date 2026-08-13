package com.medtroniclabs.microcoaching.ui.screens.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Context
import android.text.format.Formatter
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ai.model.ModelCatalog
import com.medtroniclabs.microcoaching.ui.chat.ChatUiState

/**
 * On-device setup surface shown before chat is usable — the honest replacement
 * for the old model-only "not ready" screen. It lists whatever a device still
 * needs, as a compact card stack:
 *
 *  - **AI model** (capable devices only, [ChatUiState.SetupRequired.aiRequired]):
 *    behind a manual Download button — it's large (~hundreds of MB).
 *  - **Bengali voice pack** ([showVoiceCard], BANGLA): auto-downloads on entry.
 *  - **Read-aloud (TTS) voice** ([showTtsInstall]): only when the platform pack
 *    is missing; its action opens the system TTS-data installer (Android has no
 *    in-app download for it).
 *
 * The user enters chat via **Go to chat** — enabled once the model is ready (or
 * immediately on low-end). The both-ready auto-enter (model + voice) is driven by
 * the ViewModel; this composable is pure UI.
 */
@Composable
fun CoachingSetupContent(
    uiState: ChatUiState.SetupRequired,
    voiceModelState: DownloadItemUiState,
    showVoiceCard: Boolean,
    showTtsInstall: Boolean,
    onRequestAiDownload: () -> Unit,
    onPauseAiDownload: () -> Unit,
    onResumeAiDownload: () -> Unit,
    onCancelAiDownload: () -> Unit,
    onRequestVoiceDownload: () -> Unit,
    onCancelVoiceDownload: () -> Unit,
    onInstallTts: () -> Unit,
    onGoToChat: () -> Unit,
    onClose: () -> Unit,
    showCloseIcon: Boolean,
) {
    val aiItemState = uiState.toAiDownloadItemState(modelPresent = uiState.aiReady)
    // `aiUnusable` is redundant with `aiReady` today; it states the intent locally, since
    // entering chat with an unusable model only bounces back to this screen.
    val goToChatEnabled = !uiState.aiRequired || (uiState.aiReady && !uiState.aiUnusable)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // ── Title + lead-in ──────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.coaching_setup_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.coaching_setup_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(20.dp))

        // ── AI model card (capable devices only) ─────────────────────────────
        if (uiState.aiRequired) {
            val context = LocalContext.current
            // A live `Content-Length` outranks any cached or configured size: it describes
            // the bytes arriving now, and it is what the progress row counts against, so
            // preferring it keeps the two lines from disagreeing.
            val serverBytes = uiState.downloadTotalBytes.takeIf { it > 0L } ?: uiState.aiSizeBytes
            val aiSizeLabel = remember(serverBytes, uiState.aiOnDiskBytes) {
                aiSizeLabel(context, serverBytes, uiState.aiOnDiskBytes)
            }
            DownloadItemCard(
                icon = DownloadItemIcon.AiSparkle,
                title = stringResource(R.string.download_card_ai_title),
                // Fully composed by [aiSizeLabel]; wrapping it again would re-add a tilde.
                sizeLabel = aiSizeLabel,
                isRequired = true,
                state = aiItemState,
                onDownload = onRequestAiDownload,
                onPause = onPauseAiDownload,
                onResume = onResumeAiDownload,
                onCancel = onCancelAiDownload,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
        }

        // ── Bengali voice pack card (BANGLA only) ────────────────────────────
        if (showVoiceCard) {
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
            Spacer(Modifier.height(10.dp))
        }

        // ── Read-aloud (TTS) card — only when the platform pack is missing ────
        // Android can't download TTS packs in-app with progress; the action opens
        // the system TTS-data installer instead.
        if (showTtsInstall) {
            DownloadItemCard(
                icon = DownloadItemIcon.ReadAloud,
                title = stringResource(R.string.download_card_tts_title),
                sizeLabel = stringResource(R.string.download_card_tts_hint),
                isRequired = false,
                state = DownloadItemUiState.Idle,
                onDownload = onInstallTts,
                actionLabel = stringResource(R.string.download_card_tts_install),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
        }

        // ── Engine load failure ──────────────────────────────────────────────
        // Set when the engine couldn't use a model that is on disk, bouncing the user back
        // here; without it the screen looks like the button did nothing. Already a complete
        // localized sentence picked by the ViewModel for the specific failure, so it renders
        // verbatim — never a native error, which belongs in logcat.
        uiState.loadError?.let { reason ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        // ── Primary "Go to chat" action ──────────────────────────────────────
        // Replaces the old silent auto-navigation. Enabled once the model is
        // ready (or immediately on low-end). Voice/TTS keep going in the
        // background after entering.
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onGoToChat,
            enabled = goToChatEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.coaching_setup_go_to_chat))
        }

        // ── Dismiss button (only inside the bottom-sheet host) ───────────────
        // When something is actively downloading or paused mid-flight, label the
        // dismiss action "Continue in background" — it more accurately describes
        // what happens (the worker keeps running while the sheet closes). Falls
        // back to "Maybe later" when nothing is in flight.
        if (showCloseIcon) {
            Spacer(Modifier.height(8.dp))
            val anyInFlight = aiItemState.isInFlight() || voiceModelState.isInFlight()
            val dismissLabelRes = if (anyInFlight) {
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
 * The AI model card's size line, in one of three forms:
 *  - "N of M" when a file is on disk and shorter than expected, so a partial download is
 *    visible rather than hidden behind the size it was supposed to be.
 *  - the expected size alone, when nothing contradicts it.
 *  - the expected size prefixed with "~", only while it is still unconfirmed by the server,
 *    which is the one case the tilde describes.
 *
 * [resolvedBytes] should be the best server-derived figure available; the catalog constant is
 * a fallback for a first run with no network answer, and must not be able to contradict the
 * progress row, which counts against `Content-Length`.
 *
 * @param resolvedBytes server-derived expected size, or null when none is known yet.
 * @param onDiskBytes actual length of the model file, or null when none is present.
 *
 * Not a composable, so it can be called from inside `remember` where `stringResource` is
 * unavailable.
 */
private fun aiSizeLabel(context: Context, resolvedBytes: Long?, onDiskBytes: Long?): String {
    val expectedBytes = resolvedBytes
        ?: runCatching { MicroCoachingSDK.getInstance().selectedModelVariant().sizeInBytes }
            .getOrElse { ModelCatalog.default().sizeInBytes }
    val expected = Formatter.formatShortFileSize(context, expectedBytes)
    return when {
        onDiskBytes != null && onDiskBytes in 1 until expectedBytes -> context.getString(
            R.string.download_card_ai_size_partial,
            Formatter.formatShortFileSize(context, onDiskBytes),
            expected,
        )
        onDiskBytes == null && resolvedBytes == null ->
            context.getString(R.string.download_card_ai_size, expected)
        else -> context.getString(R.string.download_card_ai_size_exact, expected)
    }
}

/**
 * True when the download is actively running (or queued / paused / extracting)
 * — i.e. closing the sheet means the worker keeps going. Done / Idle / Failed /
 * Unusable end states return false so the sheet falls back to the "Maybe later"
 * label.
 */
private fun DownloadItemUiState.isInFlight(): Boolean = when (this) {
    is DownloadItemUiState.Downloading,
    is DownloadItemUiState.Preparing,
    is DownloadItemUiState.Paused,
    is DownloadItemUiState.Extracting -> true
    is DownloadItemUiState.Idle,
    is DownloadItemUiState.Done,
    is DownloadItemUiState.Failed,
    is DownloadItemUiState.Unusable -> false
}
