package com.medtroniclabs.microcoaching.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue

/**
 * Bottom sheet shown after a CHW taps thumbs-down on a chat response, inviting
 * optional free-text detail on why the answer fell short. Thumbs-up never opens
 * this sheet.
 *
 * The thumbs-down telemetry event is emitted when this sheet CLOSES (via
 * [onCommit]) rather than on the thumb tap, so the typed note travels in the same
 * `chat_feedback_negative` event (`payload_json.feedback`). Both the Submit button
 * and a scrim-tap / swipe-down commit the current text — whatever the CHW typed is
 * captured either way; an empty field simply commits with no note.
 *
 * @param initialText Any note already captured for this message (so re-opening
 *   the sheet shows what was typed before).
 * @param onCommit Called with the current text when the sheet closes (button,
 *   scrim, or swipe). The caller both records the event and hides the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatFeedbackNoteSheet(
    initialText: String,
    onCommit: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(initialText) }

    ModalBottomSheet(
        onDismissRequest = { onCommit(text) },
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_feedback_sheet_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.chat_feedback_sheet_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
                placeholder = { Text(stringResource(R.string.chat_feedback_sheet_placeholder)) },
                shape = RoundedCornerShape(12.dp),
                minLines = 3,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onCommit(text) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpiceBlue),
            ) {
                Text(
                    text = stringResource(R.string.chat_feedback_sheet_submit),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
