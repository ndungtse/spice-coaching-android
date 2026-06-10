package com.medtroniclabs.microcoaching.ui.screens.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ai.voice.ChatVoiceInputController

/**
 * Compact pill that tells the user which engine is transcribing right now —
 * the platform recognizer's on-device pack, the platform recognizer over
 * Google's cloud, or the local sherpa-onnx Bengali model. Visible only while
 * the mic is active so it doesn't clutter the idle chat surface.
 */
@Composable
fun SttBackendBadge(
    backend: ChatVoiceInputController.Backend,
    modifier: Modifier = Modifier,
) {
    val (label, dotColor) = when (backend) {
        ChatVoiceInputController.Backend.PlatformOnDevice ->
            stringResource(R.string.chat_voice_backend_on_device) to
                Color(0xFF34A853) // green
        ChatVoiceInputController.Backend.PlatformCloud ->
            stringResource(R.string.chat_voice_backend_server) to
                Color(0xFFFB8C00) // orange
        ChatVoiceInputController.Backend.OfflineSherpa ->
            stringResource(R.string.chat_voice_backend_offline_model) to
                Color(0xFF1A73E8) // blue
        ChatVoiceInputController.Backend.Unknown -> return
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
