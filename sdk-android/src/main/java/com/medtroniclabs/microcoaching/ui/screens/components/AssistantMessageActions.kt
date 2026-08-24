package com.medtroniclabs.microcoaching.ui.screens.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R

/**
 * The row of controls under an assistant reply: copy, read aloud, and a one-shot
 * helpful/not-helpful rating.
 *
 * Copy comes first because it is the only one that changes nothing — the CHW can take the text
 * and move on. [isSpeaking] flips the same control between start and stop, so a long answer
 * can be cut short instead of waited out.
 *
 * @param vote the rating already given, or null when unrated. A rating locks both thumbs:
 *   [onFeedback] fires once per message, and the chosen thumb stays tinted.
 */
@Composable
internal fun AssistantMessageActions(
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    isSpeaking: Boolean,
    vote: Boolean?,
    onFeedback: (helpful: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rated = vote != null
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(
            icon = Icons.Filled.ContentCopy,
            label = stringResource(R.string.chat_copy),
            onClick = onCopy,
        )
        ActionIcon(
            icon = if (isSpeaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
            label = stringResource(
                if (isSpeaking) R.string.chat_stop_speaking else R.string.chat_speak,
            ),
            onClick = onSpeak,
            tint = if (isSpeaking) MaterialTheme.colorScheme.primary else null,
        )
        ActionIcon(
            icon = Icons.Filled.ThumbUp,
            label = stringResource(R.string.chat_feedback_helpful),
            onClick = { onFeedback(true) },
            enabled = !rated,
            tint = if (vote == true) MaterialTheme.colorScheme.primary else null,
        )
        ActionIcon(
            icon = Icons.Filled.ThumbDown,
            label = stringResource(R.string.chat_feedback_not_helpful),
            onClick = { onFeedback(false) },
            enabled = !rated,
            tint = if (vote == false) MaterialTheme.colorScheme.error else null,
        )
    }
}

/**
 * One control in the row, sized to keep four of them compact under a bubble.
 *
 * @param tint null for the resting colour; a value marks the control as active.
 */
@Composable
private fun ActionIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(ICON_BUTTON_SIZE),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(ICON_SIZE),
            tint = tint ?: MaterialTheme.colorScheme.onBackground.copy(alpha = RESTING_ALPHA),
        )
    }
}

private val ICON_BUTTON_SIZE = 28.dp
private val ICON_SIZE = 16.dp
private const val RESTING_ALPHA = 0.45f
