package com.medtroniclabs.microcoaching.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ai.translation.TranslationModelState
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * Small inline chip that surfaces the on-device translation pack lifecycle to
 * the CHW.
 *
 * Hidden when:
 *  - SDK language is English (no translation needed), or
 *  - the pack is [TranslationModelState.Ready].
 *
 * Renders a spinner + Bangla "Translation pack downloading…" during
 * [TranslationModelState.Downloading] / [TranslationModelState.Unknown].
 *
 * Renders a passive footer "Translation unavailable — content shown in English"
 * during [TranslationModelState.Failed].
 *
 * Place at the top of the chat screen and just under the coaching card title.
 */
@Composable
fun TranslationModelStateChip(modifier: Modifier = Modifier) {
    // Compose previews and IDE inspection don't have an initialised SDK — bail
    // out silently so the surrounding screen still renders.
    if (LocalInspectionMode.current) return
    val sdk = MicroCoachingSDK.getInstance()
    if (sdk.language != Language.BANGLA) return

    val state by sdk.translationModelState.collectAsState()

    when (state) {
        TranslationModelState.Ready -> return
        TranslationModelState.Unknown,
        TranslationModelState.Downloading -> ChipRow(
            modifier = modifier,
            text = stringResource(R.string.translation_pack_downloading),
            background = Color(0xFFFFF3CD),
            content = Color(0xFF856404),
            spinner = true,
        )
        is TranslationModelState.Failed -> ChipRow(
            modifier = modifier,
            text = stringResource(R.string.translation_pack_unavailable),
            background = Color(0xFFFFE5E5),
            content = Color(0xFF8A1F1F),
            spinner = false,
        )
    }
}

@Composable
private fun ChipRow(
    modifier: Modifier,
    text: String,
    background: Color,
    content: Color,
    spinner: Boolean,
) {
    Row(
        modifier = modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = content,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}
