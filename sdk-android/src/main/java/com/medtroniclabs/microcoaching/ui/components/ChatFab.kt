package com.medtroniclabs.microcoaching.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme

/**
 * Floating action button for the **CHW AI chat** entry point.
 *
 * Designed to be placed in the bottom-right corner of the host's home screen.
 * On tap the host opens [com.medtroniclabs.microcoaching.ui.chat.CoachingChatBottomSheet]
 * for a quick conversational session with the on-device assistant.
 *
 * **Host integration:**
 * ```kotlin
 * binding.chatFabComposeView.setContent {
 *     MicroCoachingTheme {
 *         ChatFab(onClick = {
 *             CoachingChatBottomSheet.show(parentFragmentManager)
 *         })
 *     }
 * }
 * ```
 */
@Composable
fun ChatFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SdkLocalizedTheme {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = stringResource(R.string.fab_open_chat),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatFabPreview() {
    MicroCoachingTheme {
        ChatFab(onClick = {})
    }
}
