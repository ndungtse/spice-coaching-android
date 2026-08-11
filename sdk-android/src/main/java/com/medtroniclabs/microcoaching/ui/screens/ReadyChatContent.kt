package com.medtroniclabs.microcoaching.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.util.Log
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.chat.ChatFeedbackNoteSheet
import com.medtroniclabs.microcoaching.ui.chat.ChatMessage
import com.medtroniclabs.microcoaching.ui.chat.ChatRole
import com.medtroniclabs.microcoaching.ui.chat.ChatUiState
import com.medtroniclabs.microcoaching.ui.chat.MessageSource
import com.medtroniclabs.microcoaching.ui.chat.SuggestedQuestion
import com.medtroniclabs.microcoaching.ui.common.AssistantBubbleWithAvatar
import com.medtroniclabs.microcoaching.ai.voice.stt.SttModelState
import com.medtroniclabs.microcoaching.ui.common.ChatInputBar
import com.medtroniclabs.microcoaching.ai.voice.ChatVoiceInputController
import com.medtroniclabs.microcoaching.ui.screens.components.RecordingBadge
import com.medtroniclabs.microcoaching.ui.screens.components.SttDownloadBanner
import com.medtroniclabs.microcoaching.ui.common.MessageBubble
import com.medtroniclabs.microcoaching.ui.common.StreamingBubble
import com.medtroniclabs.microcoaching.ui.components.TranslationModelStateChip
import com.medtroniclabs.microcoaching.ui.common.ChatInputState
import com.medtroniclabs.microcoaching.ui.screens.components.SourceDocChipRow
import com.medtroniclabs.microcoaching.ui.screens.components.ChatSheetHeader
import com.medtroniclabs.microcoaching.ui.screens.components.SuggestionRow

@Composable
internal fun ReadyChatContent(
    uiState: ChatUiState.Ready,
    onSendMessage: (String) -> Unit,
    onSendSuggested: (SuggestedQuestion) -> Unit,
    onSpeakMessage: (String) -> Unit,
    onMicTap: (() -> Unit)?,
    inputState: ChatInputState,
    isRecording: Boolean,
    sttDownloadState: SttModelState?,
    onRetrySttDownload: () -> Unit,
    onCancelSttDownload: () -> Unit,
    voiceBackend: ChatVoiceInputController.Backend?,
    showVoiceModelDownloadAction: Boolean,
    onDownloadVoiceModel: () -> Unit,
    onClose: () -> Unit,
    showCloseIcon: Boolean,
    onClearHistory: () -> Unit,
    networkAvailable: Boolean,
    preferOnline: Boolean,
    onSetOnlineMode: (Boolean) -> Unit,
    moduleTitleLookup: (String?) -> String?,
    onSourceDocTap: (String, String, Int?) -> Unit,
    onFeedback: (Long, Boolean) -> Unit,
    onFeedbackNote: (Long, String) -> Unit,
) {
    val listState = rememberLazyListState()

    // Which assistant message (by id) currently has the thumbs-down detail sheet
    // open, or null. UI-local: the sheet is supplementary to the already-recorded
    // negative event.
    var feedbackSheetFor by remember { mutableStateOf<Long?>(null) }

    // Auto-scroll to the latest item. The +1 accounts for the streaming bubble
    // when generation is in flight. We do NOT include the welcome bubble in this
    // count because it sits above real messages and never animates.
    LaunchedEffect(uiState.messages.size, uiState.streamingText) {
        val realCount = uiState.messages.size + (if (uiState.isGenerating) 1 else 0)
        if (realCount > 0) {
            // +1 for the welcome bubble (always rendered as item 0); list indices
            // are stable so this lands on the last real bubble.
            listState.animateScrollToItem(realCount)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ChatSheetHeader(
            onClose = onClose,
            showCloseIcon = showCloseIcon,
            onClearHistory = onClearHistory,
            showVoiceModelDownloadAction = showVoiceModelDownloadAction,
            onDownloadVoiceModel = onDownloadVoiceModel,
            networkAvailable = networkAvailable,
            preferOnline = preferOnline,
            onSetOnlineMode = onSetOnlineMode,
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        // Translation pack status — slim row below the header; only renders when
        // SDK lang=Bangla and the BN pack is downloading or failed.
        TranslationModelStateChip(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // Today pill — single date marker for now. Real day-boundary logic is
            // a follow-up; the design only shows one entry.
            item { TodayPill() }

            // Welcome seed — UI-only assistant bubble shown above any real
            // messages so the surface always has a friendly opener. Not
            // persisted as a ChatMessage and emits no telemetry.
            //
            // Gated on `messages.isEmpty()`: returning users with restored
            // history see their conversation pick up where it left off without
            // a redundant greeting. Re-appears after the user taps Clear chat.
            if (uiState.messages.isEmpty() && !uiState.isGenerating) {
                item {
                    AssistantBubbleWithAvatar(
                        message = ChatMessage(
                            sessionId = "ui-welcome",
                            role = ChatRole.ASSISTANT,
                            text = stringResource(R.string.chat_welcome_message),
                            source = MessageSource.LOCAL_MODEL,
                        ),
                    )
                }
            }

            items(items = uiState.messages, key = { it.id }) { message ->
                when (message.role) {
                    ChatRole.ASSISTANT -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            AssistantBubbleWithAvatar(message = message)
                            if (message.sourceDocuments.isNotEmpty()) {
                                // Short italic citation line — module title or first doc title
                              /*  val citationText = moduleTitleLookup(message.groundingModuleFamilyId)
                                    ?: message.sourceDocuments.firstOrNull()
                                        ?.title?.takeIf { it.isNotBlank() }
                                if (citationText != null) {
                                    Text(
                                        text = "— $citationText",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontStyle = FontStyle.Italic,
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(
                                            start = 56.dp,
                                            top = 2.dp,
                                            end = 12.dp,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } */
                                SourceDocChipRow(
                                    sourceDocuments = message.sourceDocuments,
                                    moduleTitle = moduleTitleLookup(message.groundingModuleFamilyId),
                                    onTap = onSourceDocTap,
                                    startPage = message.startPage,
                                    modifier = Modifier.padding(start = 56.dp, end = 12.dp),
                                )
                            }
                            Row(
                                modifier = Modifier.padding(start = 56.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = { onSpeakMessage(message.text) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = stringResource(R.string.chat_speak),
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                                    )
                                }
                                // Response feedback (Phase 1) — thumbs up/down emit a
                                // chat_feedback_* telemetry event. A rating is a
                                // one-shot action: once given it locks (both buttons
                                // disable); the chosen thumb stays tinted (primary for
                                // up, error for down).
                                val vote = uiState.feedback[message.id]
                                val rated = vote != null
                                val inactiveTint =
                                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                                IconButton(
                                    onClick = { onFeedback(message.id, true) },
                                    enabled = !rated,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ThumbUp,
                                        contentDescription = stringResource(R.string.chat_feedback_helpful),
                                        modifier = Modifier.size(16.dp),
                                        tint = if (vote == true) MaterialTheme.colorScheme.primary else inactiveTint,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        // One-shot: first tap records thumbs-down and opens
                                        // the detail sheet. (Button is disabled once rated,
                                        // so this only fires on the first tap.)
                                        onFeedback(message.id, false)
                                        feedbackSheetFor = message.id
                                    },
                                    enabled = !rated,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ThumbDown,
                                        contentDescription = stringResource(R.string.chat_feedback_not_helpful),
                                        modifier = Modifier.size(16.dp),
                                        tint = if (vote == false) MaterialTheme.colorScheme.error else inactiveTint,
                                    )
                                }
                            }
                        }
                    }

                    else -> MessageBubble(message = message)
                }
            }

            if (uiState.isGenerating) {
                item { StreamingBubble(text = uiState.streamingText) }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        // Suggestion chips live above the input (per ai-coach.png) — NOT inside
        // the message list anymore. They surface for as long as the source data
        // has chips to offer; ChatViewModel decides when to refresh them.
        if (uiState.suggestedQuestions.isNotEmpty() && !uiState.isGenerating) {
            SuggestionRow(
                questions = uiState.suggestedQuestions,
                onSendSuggested = onSendSuggested,
            )
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        if (sttDownloadState != null) {
            SttDownloadBanner(
                state = sttDownloadState,
                onRetry = onRetrySttDownload,
                onCancel = onCancelSttDownload,
            )
        }

        // Animated "Listening…" pill — visible only while the mic is active.
        // The active backend (Google on-device / Google cloud / offline
        // sherpa-onnx) isn't actionable for the CHW, so we just log it for
        // debugging and surface a friendly recording indicator instead. The
        // explicit SttBackendBadge composable is preserved for hosts that
        // want the routing detail somewhere else.
        if (isRecording) {
            LaunchedEffect(voiceBackend) {
                if (voiceBackend != null &&
                    voiceBackend != ChatVoiceInputController.Backend.Unknown
                ) {
                    Log.d(
                        "ChatScreen",
                        "STT recording started — backend=$voiceBackend",
                    )
                }
            }
            RecordingBadge()
        }

        ChatInputBar(
            onSend = onSendMessage,
            enabled = !uiState.isGenerating,
            onMicTap = onMicTap,
            inputState = inputState,
            isRecording = isRecording,
            modifier = Modifier.navigationBarsPadding(),
        )
    }

    // Thumbs-down detail sheet. Closing it (Submit / scrim / swipe) commits the
    // negative feedback event with the typed note. Renders in its own window
    // (safe above the host BottomSheetDialog).
    val sheetMsgId = feedbackSheetFor
    if (sheetMsgId != null) {
        ChatFeedbackNoteSheet(
            initialText = uiState.feedbackNotes[sheetMsgId].orEmpty(),
            onCommit = { note ->
                onFeedbackNote(sheetMsgId, note)
                feedbackSheetFor = null
            },
        )
    }
}


/**
 * Single date marker between the header and the message list — matches the
 * "Today" pill in `ai-coach.png`. Real day-boundary logic (split history by
 * date) is a follow-up; the design only shows one pill.
 */
@Composable
private fun TodayPill() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_today),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
    }
}
