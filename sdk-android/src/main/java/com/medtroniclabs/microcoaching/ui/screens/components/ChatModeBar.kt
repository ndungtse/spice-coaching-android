package com.medtroniclabs.microcoaching.ui.screens.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.domain.decision.AnswerMode
import com.medtroniclabs.microcoaching.ui.theme.CoachingTheme

/**
 * Full-width strip under the chat header stating how the next answer will be found, and
 * carrying the on-device model's download when one is in flight.
 *
 * Full-width by necessity, not preference. As a chip inside the header row it competed with a
 * 40dp avatar and two 48dp icon buttons for roughly 180dp on a 360dp screen, which the Bengali
 * label alone overruns — and there was nowhere to put download progress at all. Its own row
 * gets the whole width in either language and leaves the message list untouched.
 *
 * Tapping anywhere opens the answering sheet. The old chip toggled the mode silently on tap,
 * which changed how answers were produced with no statement of what had changed.
 *
 * @param answerMode what will answer the next message, already resolved against connectivity.
 * @param networkAvailable live connectivity, used to explain a stored online preference that
 *   currently cannot be honoured.
 * @param preferOnline the user's stored choice, which differs from [answerMode] whenever
 *   connectivity is missing.
 * @param modelDownload the model's lifecycle; anything in flight is reported here.
 */
@Composable
fun ChatModeBar(
    answerMode: AnswerMode,
    networkAvailable: Boolean,
    preferOnline: Boolean,
    modelDownload: DownloadItemUiState,
    onOpenSheet: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inFlight = modelDownload.isModelTransferInFlight()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.chat_mode_bar_open_sheet),
                onClick = onOpenSheet,
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val online = answerMode == AnswerMode.ONLINE
            // A stored online choice that connectivity blocks is the one case worth its own
            // glyph: the mode is on-device, but not because the user asked for it.
            val blocked = preferOnline && !networkAvailable
            val icon: ImageVector = when {
                online -> Icons.Filled.Cloud
                blocked -> Icons.Filled.CloudOff
                else -> Icons.Filled.Smartphone
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (online) CoachingTheme.colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(width = 8.dp, height = 0.dp))
            // One weighted child, filling: it claims all the space the icon and the trailing
            // control don't need, which is what pins the trailing control to the far edge.
            // Two weighted siblings would instead split the leftover between them and leave
            // the trailing control floating mid-row.
            Text(
                text = stringResource(modeLabelRes(answerMode, blocked)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (inFlight) {
                DownloadControls(
                    modelDownload = modelDownload,
                    onPause = onPauseDownload,
                    onResume = onResumeDownload,
                    onCancel = onCancelDownload,
                )
            } else {
                Text(
                    text = stringResource(R.string.chat_mode_bar_change),
                    style = MaterialTheme.typography.labelMedium,
                    color = CoachingTheme.colors.success,
                    maxLines = 1,
                )
            }
        }

        if (inFlight) {
            Spacer(Modifier.height(4.dp))
            UpgradeProgressRow(modelDownload)
        }
    }
}

/**
 * The one line naming the mode. Written to describe what the CHW gets rather than the
 * mechanism producing it: the model rewords a card that retrieval has already chosen, so
 * presenting it as a quality tier would overstate what turning it on does.
 *
 * On-device is a mode the CHW can choose at any time, connected or not, so its label never
 * asserts anything about connectivity. Only [blockedByConnectivity] — where they asked for
 * online and it cannot be reached — mentions the network, and even then names the answering
 * mode first and the reason second. Leading with the network made a deliberate choice read as
 * a degraded state.
 */
private fun modeLabelRes(answerMode: AnswerMode, blockedByConnectivity: Boolean): Int = when {
    answerMode == AnswerMode.ONLINE -> R.string.chat_mode_bar_online
    blockedByConnectivity -> R.string.chat_mode_bar_on_device_online_unavailable
    answerMode == AnswerMode.ON_DEVICE_ASSISTED -> R.string.chat_mode_bar_on_device_assisted
    else -> R.string.chat_mode_bar_on_device_direct
}

@Composable
private fun DownloadControls(
    modelDownload: DownloadItemUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (modelDownload is DownloadItemUiState.Paused) {
            IconButton(onClick = onResume, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.download_card_action_resume),
                    tint = CoachingTheme.colors.success,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            IconButton(onClick = onPause, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = stringResource(R.string.download_card_action_pause),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.download_card_action_cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun UpgradeProgressRow(modelDownload: DownloadItemUiState) {
    val percent = modelDownload.progressPercentOrNull()
    val label = when (modelDownload) {
        is DownloadItemUiState.WaitingForNetwork -> stringResource(
            if (modelDownload.wifiOnly) {
                R.string.chat_mode_bar_upgrade_waiting_wifi
            } else {
                R.string.chat_mode_bar_upgrade_waiting_network
            },
        )
        is DownloadItemUiState.Paused ->
            stringResource(R.string.chat_mode_bar_upgrade_paused, percent ?: 0)
        is DownloadItemUiState.Preparing ->
            stringResource(R.string.chat_mode_bar_upgrade_preparing)
        else -> if (percent != null) {
            stringResource(R.string.chat_mode_bar_upgrade_progress, percent)
        } else {
            stringResource(R.string.chat_mode_bar_upgrade_preparing)
        }
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(3.dp))
    // An indeterminate bar animates, which reads as movement. Only shown when bytes are
    // actually arriving; a stalled transfer gets a bar parked at what it has received.
    if (percent == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * True while the model download owns the bar — running, constraint-blocked, or paused.
 *
 * Terminal states are excluded: a finished, failed or damaged download is described by the
 * answering sheet, and reporting it on the bar would leave the message permanently in place.
 */
internal fun DownloadItemUiState.isModelTransferInFlight(): Boolean = when (this) {
    is DownloadItemUiState.Downloading,
    is DownloadItemUiState.Preparing,
    is DownloadItemUiState.WaitingForNetwork,
    is DownloadItemUiState.Paused -> true
    is DownloadItemUiState.Idle,
    is DownloadItemUiState.Extracting,
    is DownloadItemUiState.Done,
    is DownloadItemUiState.Failed,
    is DownloadItemUiState.Unusable -> false
}

/** Progress as a percentage, or null when no meaningful figure is available yet. */
internal fun DownloadItemUiState.progressPercentOrNull(): Int? = when (this) {
    is DownloadItemUiState.Downloading -> progressPercent.takeIf { it >= 0 }
    is DownloadItemUiState.Paused -> progressPercent.takeIf { it >= 0 }
    is DownloadItemUiState.WaitingForNetwork -> progressPercent.takeIf { it >= 0 }
    else -> null
}
