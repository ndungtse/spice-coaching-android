package com.medtroniclabs.microcoaching.ui.screens.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ai.voice.stt.SttModelState

private const val BYTES_PER_MB: Long = 1_048_576L

/**
 * Inline banner shown above the chat input while the Bengali sherpa STT model
 * is downloading, extracting, or in a failed state. Hidden for `Idle` / `Ready`.
 *
 * The chat surface chooses whether to render this — see how
 * `CoachingChatSurface` filters out Idle/Ready before passing the state down.
 */
@Composable
fun SttDownloadBanner(
    state: SttModelState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    if (state is SttModelState.Idle || state is SttModelState.Ready) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (state is SttModelState.Failed) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val statusText: String = when (state) {
                is SttModelState.Downloading -> {
                    if (state.totalBytes > 0L && state.progressPercent >= 0) {
                        val dl = (state.bytesDownloaded / BYTES_PER_MB)
                            .coerceAtLeast(0L).toInt()
                        val total = (state.totalBytes / BYTES_PER_MB)
                            .coerceAtLeast(0L).toInt()
                        stringResource(
                            R.string.stt_banner_downloading,
                            dl,
                            total,
                            state.progressPercent,
                        )
                    } else {
                        stringResource(R.string.stt_banner_downloading_preparing)
                    }
                }
                is SttModelState.Extracting -> stringResource(R.string.stt_banner_extracting)
                is SttModelState.Failed -> stringResource(R.string.stt_banner_failed)
                else -> ""
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (state is SttModelState.Failed) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            when (state) {
                is SttModelState.Downloading, is SttModelState.Extracting -> {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.stt_banner_cancel))
                    }
                }
                is SttModelState.Failed -> {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.stt_banner_retry))
                    }
                }
                else -> Unit
            }
        }
    }
}
