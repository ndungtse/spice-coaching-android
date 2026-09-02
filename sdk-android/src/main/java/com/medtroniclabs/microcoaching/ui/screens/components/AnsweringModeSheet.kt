package com.medtroniclabs.microcoaching.ui.screens.components

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.domain.decision.AnswerMode

/**
 * Where the CHW chooses how answers are found, and manages the optional on-device model.
 *
 * States two things a toggle could not. First, what each mode actually does — one reaches the
 * full library and needs a connection, the other works anywhere from what is already on the
 * phone. Second, what the model changes: the wording of an answer, not which guidance is
 * found. Framing it as a quality tier would misdescribe it and make declining feel like
 * accepting something worse, when the retrieval is identical either way.
 *
 * @param answerMode the currently resolved mode. Marks the selected rows, and decides whether
 *   the answer-style section applies at all — it describes on-device wording, which says nothing
 *   about a backend answer.
 * @param modelEligible false on hardware that cannot host the model; the answer-style section is
 *   then absent rather than shown disabled, since there is no action to offer. Either reason for
 *   hiding it is presentational: consent is stored separately and survives untouched, and a
 *   download in flight stays pausable from the mode bar, which reports it regardless of mode.
 * @param modelEnabled the user's stored consent, which is what the radio reflects — not
 *   whether the engine happens to be loaded this second.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnsweringModeSheet(
    answerMode: AnswerMode,
    preferOnline: Boolean,
    networkAvailable: Boolean,
    modelEligible: Boolean,
    modelEnabled: Boolean,
    modelDownload: DownloadItemUiState,
    modelSizeBytes: Long?,
    modelOnDiskBytes: Long?,
    showTtsInstall: Boolean,
    onSetOnlineMode: (Boolean) -> Unit,
    onEnableLocalModel: () -> Unit,
    onDisableLocalModel: (deleteFile: Boolean) -> Unit,
    onDeleteLocalModel: () -> Unit,
    onRequestDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallTts: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showOptOutDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                text = stringResource(R.string.answering_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))

            ModeRow(
                selected = answerMode == AnswerMode.ONLINE,
                icon = { tint ->
                    Icon(Icons.Filled.Cloud, null, tint = tint, modifier = Modifier.size(18.dp))
                },
                title = stringResource(R.string.answering_sheet_online_title),
                // Named as a requirement, not a warning: it is the tradeoff, and a CHW
                // choosing between the two rows needs it stated plainly.
                subtitle = stringResource(R.string.answering_sheet_online_body),
                onClick = { onSetOnlineMode(true) },
            )
            // Explains why an online selection is not in effect, without silently reverting
            // the choice — it resumes on its own once a network returns.
            if (preferOnline && !networkAvailable) {
                Text(
                    text = stringResource(R.string.answering_sheet_online_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 34.dp, bottom = 6.dp),
                )
            }

            Spacer(Modifier.height(6.dp))

            ModeRow(
                selected = answerMode != AnswerMode.ONLINE,
                icon = { tint ->
                    Icon(Icons.Filled.Smartphone, null, tint = tint, modifier = Modifier.size(18.dp))
                },
                title = stringResource(R.string.answering_sheet_on_device_title),
                subtitle = stringResource(R.string.answering_sheet_on_device_body),
                onClick = { onSetOnlineMode(false) },
            )

            // Answer style only describes how an on-device answer is worded, so it is hidden
            // while online answering is the selected mode — there is no wording choice to make
            // about a backend answer.
            //
            // Visibility only. The stored consent lives in
            // [com.medtroniclabs.microcoaching.ai.model.LocalModelPrefs] and is untouched by
            // this branch, so switching to online and back restores the same selection, and a
            // model already on disk is neither unloaded nor deleted by hiding its controls.
            if (modelEligible && answerMode != AnswerMode.ONLINE) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                AnswerStyleSection(
                    modelEnabled = modelEnabled,
                    modelDownload = modelDownload,
                    modelSizeBytes = modelSizeBytes,
                    modelOnDiskBytes = modelOnDiskBytes,
                    onChooseDirect = { showOptOutDialog = true },
                    onChooseAssisted = onEnableLocalModel,
                    onDeleteLocalModel = onDeleteLocalModel,
                    onRequestDownload = onRequestDownload,
                    onPauseDownload = onPauseDownload,
                    onResumeDownload = onResumeDownload,
                    onCancelDownload = onCancelDownload,
                )
            }

            // Displaced from the removed setup screen. Android offers no in-app download for
            // TTS data, so the action opens the system installer.
            if (showTtsInstall) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.download_card_tts_title),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onInstallTts) {
                        Text(stringResource(R.string.download_card_tts_install))
                    }
                }
            }
        }
    }

    if (showOptOutDialog) {
        OptOutDialog(
            modelSizeBytes = modelOnDiskBytes ?: modelSizeBytes,
            onConfirm = { deleteFile ->
                showOptOutDialog = false
                onDisableLocalModel(deleteFile)
            },
            onDismiss = { showOptOutDialog = false },
        )
    }
}

/**
 * The model's on/off choice, expressed as the two answer styles it produces.
 *
 * Both options are always present, so the choice reads as two ways of answering rather than a
 * feature that is missing until purchased.
 */
@Composable
private fun AnswerStyleSection(
    modelEnabled: Boolean,
    modelDownload: DownloadItemUiState,
    modelSizeBytes: Long?,
    modelOnDiskBytes: Long?,
    onChooseDirect: () -> Unit,
    onChooseAssisted: () -> Unit,
    onDeleteLocalModel: () -> Unit,
    onRequestDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.answering_sheet_style_title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    ModeRow(
        selected = !modelEnabled,
        icon = null,
        title = stringResource(R.string.answering_sheet_style_direct_title),
        subtitle = stringResource(R.string.answering_sheet_style_direct_body),
        // Only meaningful as a change; re-selecting the active option must not open the
        // opt-out dialog.
        onClick = { if (modelEnabled) onChooseDirect() },
    )
    Spacer(Modifier.height(4.dp))
    ModeRow(
        selected = modelEnabled,
        icon = null,
        title = stringResource(R.string.answering_sheet_style_assisted_title),
        subtitle = if (modelSizeBytes != null) {
            stringResource(
                R.string.answering_sheet_style_assisted_body_sized,
                Formatter.formatShortFileSize(context, modelSizeBytes),
            )
        } else {
            stringResource(R.string.answering_sheet_style_assisted_body)
        },
        onClick = { if (!modelEnabled) onChooseAssisted() },
    )

    // The tested download rendering, reused rather than reimplemented: progress, pause,
    // resume, damaged and retry-budget states all already live in this card.
    val showCard = modelEnabled && modelDownload !is DownloadItemUiState.Idle
    if (showCard) {
        Spacer(Modifier.height(10.dp))
        DownloadItemCard(
            icon = DownloadItemIcon.AiSparkle,
            title = stringResource(R.string.download_card_ai_title),
            sizeLabel = modelSizeLabel(
                expectedBytes = modelSizeBytes,
                onDiskBytes = modelOnDiskBytes,
            ),
            isRequired = false,
            state = modelDownload,
            onDownload = onRequestDownload,
            onPause = onPauseDownload,
            onResume = onResumeDownload,
            onCancel = onCancelDownload,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Reclaiming the space stays available to someone who kept the file when opting out;
    // without this the only route back to it would be opting in again.
    val fileOnDisk = (modelOnDiskBytes ?: 0L) > 0L
    if (!modelEnabled && fileOnDisk) {
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onDeleteLocalModel) {
            Text(
                text = stringResource(
                    R.string.answering_sheet_free_up_space,
                    Formatter.formatShortFileSize(context, modelOnDiskBytes ?: 0L),
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The size line for the download card: "N of M" whenever a partial file is shorter than
 * expected, so a stalled transfer is visible rather than hidden behind the size it was
 * supposed to reach.
 */
@Composable
private fun modelSizeLabel(expectedBytes: Long?, onDiskBytes: Long?): String {
    val context = LocalContext.current
    if (expectedBytes == null) return ""
    val expected = Formatter.formatShortFileSize(context, expectedBytes)
    return if (onDiskBytes != null && onDiskBytes in 1 until expectedBytes) {
        stringResource(
            R.string.download_card_ai_size_partial,
            Formatter.formatShortFileSize(context, onDiskBytes),
            expected,
        )
    } else {
        stringResource(R.string.download_card_ai_size_exact, expected)
    }
}

/**
 * Confirms turning the model off, with removing its file as a checked-by-default option.
 *
 * Checked by default because the storage is the reason most people turn it off, and a file kept
 * by accident is invisible until it is missed. Unchecking keeps it, which makes turning the
 * model back on instant and free — worth offering to anyone only trying the other style.
 */
@Composable
private fun OptOutDialog(
    modelSizeBytes: Long?,
    onConfirm: (deleteFile: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var deleteFile by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.answering_opt_out_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.answering_opt_out_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Checkbox) { deleteFile = !deleteFile },
                ) {
                    Checkbox(checked = deleteFile, onCheckedChange = { deleteFile = it })
                    Text(
                        text = if (modelSizeBytes != null && modelSizeBytes > 0L) {
                            stringResource(
                                R.string.answering_opt_out_delete_sized,
                                Formatter.formatShortFileSize(context, modelSizeBytes),
                            )
                        } else {
                            stringResource(R.string.answering_opt_out_delete)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteFile) }) {
                Text(stringResource(R.string.answering_opt_out_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.answering_opt_out_cancel))
            }
        },
    )
}

/** A selectable option: radio, optional leading glyph, title, and a line saying what it does. */
@Composable
private fun ModeRow(
    selected: Boolean,
    icon: (@Composable (tint: androidx.compose.ui.graphics.Color) -> Unit)?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row, not just the radio: a 20dp target is not a reasonable ask.
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(width = 8.dp, height = 0.dp))
        if (icon != null) {
            Column {
                Spacer(Modifier.height(2.dp))
                icon(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(width = 8.dp, height = 0.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
