package com.medtroniclabs.microcoaching.ui.screens.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.text.format.Formatter
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R

/**
 * Predefined card icons. Keeps the call site to a single enum instead of
 * threading raw `ImageVector`s through the public API.
 */
enum class DownloadItemIcon { AiSparkle, Microphone, ReadAloud }

/**
 * Compact card showing a single downloadable asset (AI model or voice model)
 * with inline status + actions. Designed for the redesigned
 * [CoachingSetupContent] but reusable for any "list of pending downloads" UX.
 *
 * Action buttons reflect [state]:
 *   - [DownloadItemUiState.Idle] / [DownloadItemUiState.Failed] → Download / Retry
 *   - [DownloadItemUiState.Downloading] / Preparing → Pause + Cancel
 *   - [DownloadItemUiState.Extracting] → spinner only (extraction is short and uncancellable)
 *   - [DownloadItemUiState.Paused] → Resume + Cancel
 *   - [DownloadItemUiState.Done] → green check, no actions
 *   - [DownloadItemUiState.Unusable] → warning glyph and "Download again", no check mark.
 *     The action is suppressed when [DownloadItemUiState.Unusable.canRetry] is false, so the
 *     card never offers a re-download the SDK would decline.
 */
@Composable
fun DownloadItemCard(
    icon: DownloadItemIcon,
    title: String,
    sizeLabel: String,
    isRequired: Boolean,
    state: DownloadItemUiState,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {},
    // Overrides the Idle/Failed button label. Used by the TTS card, whose action
    // opens the system TTS-data installer ("Install") rather than starting an
    // in-app download.
    actionLabel: String? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // ── Top row: icon · title + subtitle · primary action ────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                LeadingIcon(icon = icon, state = state)
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    SubtitleRow(state = state, sizeLabel = sizeLabel, isRequired = isRequired)
                }
                Spacer(Modifier.size(8.dp))
                TrailingAction(
                    state = state,
                    onDownload = onDownload,
                    onResume = onResume,
                    onPause = onPause,
                    actionLabel = actionLabel,
                )
            }

            // ── Optional progress + cancel row ───────────────────────────────
            val progressRow = state is DownloadItemUiState.Downloading ||
                state is DownloadItemUiState.Preparing ||
                state is DownloadItemUiState.Paused
            if (progressRow) {
                Spacer(Modifier.height(10.dp))
                ProgressRow(state = state, onCancel = onCancel)
            }
        }
    }
}

@Composable
private fun LeadingIcon(icon: DownloadItemIcon, state: DownloadItemUiState) {
    val (iconColor, bgColor) = when (state) {
        is DownloadItemUiState.Done ->
            MaterialTheme.colorScheme.onPrimary to MaterialTheme.colorScheme.primary
        is DownloadItemUiState.Failed, is DownloadItemUiState.Unusable ->
            MaterialTheme.colorScheme.onErrorContainer to MaterialTheme.colorScheme.errorContainer
        else ->
            MaterialTheme.colorScheme.onSecondaryContainer to
                MaterialTheme.colorScheme.secondaryContainer
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        val vector: ImageVector = when {
            state is DownloadItemUiState.Done -> Icons.Filled.Check
            state is DownloadItemUiState.Failed -> Icons.Filled.WarningAmber
            // Not a check: the file is on disk but cannot be used.
            state is DownloadItemUiState.Unusable -> Icons.Filled.WarningAmber
            icon == DownloadItemIcon.AiSparkle -> Icons.Filled.AutoAwesome
            icon == DownloadItemIcon.ReadAloud -> Icons.AutoMirrored.Filled.VolumeUp
            else -> Icons.Filled.Mic
        }
        Icon(
            imageVector = vector,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SubtitleRow(
    state: DownloadItemUiState,
    sizeLabel: String,
    isRequired: Boolean,
) {
    val label = when {
        isRequired -> stringResource(R.string.download_card_label_required)
        else -> stringResource(R.string.download_card_label_optional)
    }
    val statusText: String? = when (state) {
        is DownloadItemUiState.Done -> stringResource(R.string.download_card_status_done)
        is DownloadItemUiState.Failed -> stringResource(R.string.download_card_status_failed)
        is DownloadItemUiState.Unusable -> stringResource(R.string.download_card_ai_damaged)
        is DownloadItemUiState.Preparing ->
            stringResource(R.string.download_card_status_preparing)
        is DownloadItemUiState.Extracting ->
            stringResource(R.string.download_card_status_extracting)
        is DownloadItemUiState.Paused ->
            stringResource(R.string.download_card_status_paused_progress, state.progressPercent)
        is DownloadItemUiState.Downloading -> null // shown in progress row instead
        is DownloadItemUiState.Idle -> null
    }
    Text(
        text = if (statusText != null) "$label · $sizeLabel · $statusText" else "$label · $sizeLabel",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TrailingAction(
    state: DownloadItemUiState,
    onDownload: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    actionLabel: String? = null,
) {
    when (state) {
        is DownloadItemUiState.Idle, is DownloadItemUiState.Failed -> {
            FilledTonalButton(onClick = onDownload) {
                Text(actionLabel ?: stringResource(R.string.download_card_action_download))
            }
        }
        is DownloadItemUiState.Unusable -> {
            // Only offer the action when the SDK will perform it; past the re-download
            // budget the subtitle states the problem and stops there.
            if (state.canRetry) {
                FilledTonalButton(onClick = onDownload) {
                    Text(stringResource(R.string.download_card_ai_redownload))
                }
            }
        }
        is DownloadItemUiState.Paused -> {
            FilledTonalButton(onClick = onResume) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.download_card_action_resume))
            }
        }
        is DownloadItemUiState.Downloading, is DownloadItemUiState.Preparing -> {
            IconButton(onClick = onPause) {
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = stringResource(R.string.download_card_action_pause),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is DownloadItemUiState.Extracting -> {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
        is DownloadItemUiState.Done -> {
            // No trailing button — the leading icon already shows the check.
        }
    }
}

@Composable
private fun ProgressRow(
    state: DownloadItemUiState,
    onCancel: () -> Unit,
) {
    val percent: Int
    val statusText: String
    val indeterminate: Boolean
    when (state) {
        is DownloadItemUiState.Downloading -> {
            percent = state.progressPercent
            val hasBytes = state.totalBytes > 0L && state.progressPercent >= 0
            // Formatted by the platform, as the size line above is — dividing by 1 MiB here
            // and labelling it "MB" would make the two rows disagree about the same file.
            val context = LocalContext.current
            val downloaded = Formatter.formatShortFileSize(context, state.bytesDownloaded.coerceAtLeast(0L))
            statusText = if (hasBytes) {
                stringResource(
                    R.string.download_card_progress_mb,
                    downloaded,
                    Formatter.formatShortFileSize(context, state.totalBytes),
                    percent,
                )
            } else if (state.bytesDownloaded > 0L) {
                stringResource(R.string.download_card_progress_indeterminate, downloaded)
            } else {
                stringResource(R.string.download_card_status_preparing)
            }
            indeterminate = !hasBytes
        }
        is DownloadItemUiState.Preparing -> {
            percent = 0
            statusText = stringResource(R.string.download_card_status_preparing)
            indeterminate = true
        }
        is DownloadItemUiState.Paused -> {
            percent = state.progressPercent
            statusText = stringResource(
                R.string.download_card_status_paused_progress,
                state.progressPercent,
            )
            indeterminate = false
        }
        else -> return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            if (indeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(
                    progress = { percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        TextButton(
            onClick = onCancel,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 8.dp,
                vertical = 0.dp,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.download_card_action_cancel))
        }
    }
}
