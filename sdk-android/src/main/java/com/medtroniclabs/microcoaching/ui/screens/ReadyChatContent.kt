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
import androidx.compose.material3.HorizontalDivider
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
import com.medtroniclabs.microcoaching.domain.decision.AnswerMode
import com.medtroniclabs.microcoaching.ui.screens.components.AnsweringModeSheet
import com.medtroniclabs.microcoaching.ui.screens.components.ChatModeBar
import com.medtroniclabs.microcoaching.ui.screens.components.LocalModelOfferCard
import com.medtroniclabs.microcoaching.ui.screens.components.SttDownloadBanner
import com.medtroniclabs.microcoaching.ui.screens.components.isModelTransferInFlight
import com.medtroniclabs.microcoaching.ui.common.MessageBubble
import com.medtroniclabs.microcoaching.ui.common.rememberCopyToClipboard
import com.medtroniclabs.microcoaching.ui.screens.components.AssistantMessageActions
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
    onSpeakMessage: (Long, String) -> Unit,
    speakingMessageId: Long?,
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
    onRequestDownload: () -> Unit = {},
    onPauseDownload: () -> Unit = {},
    onResumeDownload: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    showTtsInstall: Boolean = false,
    onInstallTts: () -> Unit = {},
    onEnableLocalModel: () -> Unit = {},
    onDisableLocalModel: (deleteFile: Boolean) -> Unit = {},
    onDeleteLocalModel: () -> Unit = {},
    onDismissModelOffer: () -> Unit = {},
) {
    val listState = rememberLazyListState()

    // The answering sheet is UI-local: it presents choices the ViewModel already owns and
    // holds no state of its own beyond being open.
    var showAnsweringSheet by remember { mutableStateOf(false) }

    // Which assistant message (by id) currently has the thumbs-down detail sheet
    // open, or null. UI-local: the sheet is supplementary to the already-recorded
    // negative event.
    var feedbackSheetFor by remember { mutableStateOf<Long?>(null) }
    val copyToClipboard = rememberCopyToClipboard()

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
        )
        // Full-width, directly under the header: the mode label and a download progress row
        // do not fit beside the header's avatar and icon buttons, least of all in Bengali.
        ChatModeBar(
            answerMode = uiState.answerMode,
            networkAvailable = networkAvailable,
            preferOnline = preferOnline,
            modelDownload = uiState.modelDownload,
            onOpenSheet = { showAnsweringSheet = true },
            onPauseDownload = onPauseDownload,
            onResumeDownload = onResumeDownload,
            onCancelDownload = onCancelDownload,
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        // Translation pack status — slim row below the header; only renders when
        // SDK lang=Bangla and the BN pack is downloading or failed.
        TranslationModelStateChip(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )

        // Only ever present on eligible hardware, while connected, and before the user has
        // decided — the ViewModel owns that predicate.
        if (uiState.showModelOffer) {
            LocalModelOfferCard(
                sizeBytes = uiState.modelSizeBytes,
                onSetUp = onEnableLocalModel,
                onDismiss = onDismissModelOffer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

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
                            AssistantMessageActions(
                                onCopy = { copyToClipboard(message.text) },
                                onSpeak = { onSpeakMessage(message.id, message.text) },
                                isSpeaking = speakingMessageId == message.id,
                                vote = uiState.feedback[message.id],
                                onFeedback = { helpful ->
                                    onFeedback(message.id, helpful)
                                    // Thumbs-down opens the detail sheet on the same tap; the
                                    // row disables both thumbs afterwards, so this fires once.
                                    if (!helpful) feedbackSheetFor = message.id
                                },
                                modifier = Modifier.padding(start = 56.dp, bottom = 4.dp),
                            )
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

        // Suggestion chips live above the input, not inside the message list, so they
        // stay reachable without losing scroll position. They surface for as long as
        // the source data has chips to offer; ChatViewModel decides when to refresh.
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

    if (showAnsweringSheet) {
        AnsweringModeSheet(
            answerMode = uiState.answerMode,
            preferOnline = preferOnline,
            networkAvailable = networkAvailable,
            // Eligibility is inferred from the state the ViewModel already publishes: a device
            // that can never host the model is never offered it and never has a file for it.
            modelEligible = uiState.modelEligible,
            modelEnabled = uiState.answerMode == AnswerMode.ON_DEVICE_ASSISTED ||
                uiState.modelDownload.isModelTransferInFlight(),
            modelDownload = uiState.modelDownload,
            modelSizeBytes = uiState.modelSizeBytes,
            modelOnDiskBytes = uiState.modelOnDiskBytes,
            showTtsInstall = showTtsInstall,
            onSetOnlineMode = onSetOnlineMode,
            onEnableLocalModel = onEnableLocalModel,
            onDisableLocalModel = onDisableLocalModel,
            onDeleteLocalModel = onDeleteLocalModel,
            onRequestDownload = onRequestDownload,
            onPauseDownload = onPauseDownload,
            onResumeDownload = onResumeDownload,
            onCancelDownload = onCancelDownload,
            onInstallTts = onInstallTts,
            onDismiss = { showAnsweringSheet = false },
        )
    }
}


/**
 * Single "Today" date marker between the header and the message list. Real
 * day-boundary logic (split history by date) is a follow-up; the design only
 * calls for one pill.
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
