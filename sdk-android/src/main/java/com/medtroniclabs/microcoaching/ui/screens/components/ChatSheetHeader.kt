package com.medtroniclabs.microcoaching.ui.screens.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R

// ── Sheet tuning knobs — tweak these to reshape the chat sheet chrome ─────────
/**
 * Vertical padding above and below the AI Coach header row. Smaller = the
 * avatar / title sits closer to the drag handle. Reduce further (e.g. 2.dp)
 * if you want the header to hug the handle.
 */
private val ChatHeaderVerticalPadding = 4.dp

/**
 * Horizontal inset of the AI Coach header row from the sheet edges.
 */
private val ChatHeaderHorizontalPadding = 16.dp

/**
 * Header row for the chat sheet — avatar, title, online dot, optional close icon.
 */
@Composable
fun ChatSheetHeader(
    onClose: () -> Unit,
    showCloseIcon: Boolean,
    onClearHistory: () -> Unit,
    showVoiceModelDownloadAction: Boolean = false,
    onDownloadVoiceModel: () -> Unit = {},
) {
    // Two local toggles power the overflow flow:
    //   - `showOverflow`: anchors the kebab dropdown to the kebab IconButton
    //   - `showConfirm`: gates the destructive AlertDialog so the user has to
    //     explicitly confirm before chat history is wiped.
    var showOverflow by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ChatHeaderHorizontalPadding,
                vertical = ChatHeaderVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Square avatar — slightly larger than the in-bubble avatar to read as
        // an identity badge rather than a per-message glyph.
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.chat_header_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
        )
        if (showCloseIcon) {
            // Overflow kebab + dropdown anchored to it. The dropdown only
            // surfaces destructive actions (Clear chat) — split here from the
            // close button so a stray tap on Close doesn't expand a menu.
            Box {
                IconButton(onClick = { showOverflow = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.chat_overflow_open_menu),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
                DropdownMenu(
                    expanded = showOverflow,
                    onDismissRequest = { showOverflow = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_overflow_clear_history)) },
                        onClick = {
                            showOverflow = false
                            showConfirm = true
                        },
                    )
                    // Visible only when the SDK is in Bengali mode and the offline
                    // voice model isn't already present or in flight — see the
                    // visibility gate in CoachingChatSurface for the full predicate.
                    if (showVoiceModelDownloadAction) {
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.chat_overflow_download_voice_model))
                            },
                            onClick = {
                                showOverflow = false
                                onDownloadVoiceModel()
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.chat_close_sheet),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.chat_clear_history_dialog_title)) },
            text = { Text(stringResource(R.string.chat_clear_history_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onClearHistory()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.chat_clear_history_dialog_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.chat_clear_history_dialog_cancel))
                }
            },
        )
    }
}
