package com.medtroniclabs.microcoaching.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.chat.ChatUiState
import com.medtroniclabs.microcoaching.ui.chat.SuggestedQuestion
import com.medtroniclabs.microcoaching.ai.voice.stt.SttModelState
import com.medtroniclabs.microcoaching.ai.voice.ChatVoiceInputController
import com.medtroniclabs.microcoaching.ui.screens.components.DownloadItemUiState
import com.medtroniclabs.microcoaching.ui.screens.components.CoachingSetupContent
import com.medtroniclabs.microcoaching.ui.common.FullScreenLoader
import com.medtroniclabs.microcoaching.ui.common.ChatInputState
import com.medtroniclabs.microcoaching.ui.common.rememberChatInputState


@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onSendMessage: (String) -> Unit,
    onSendSuggested: (SuggestedQuestion) -> Unit,
    onRequestDownload: () -> Unit,
    onSpeakMessage: (String) -> Unit,
    onMicTap: (() -> Unit)? = null,
    onClose: () -> Unit = {},
    showCloseIcon: Boolean = false,
    onPauseDownload: () -> Unit = {},
    onResumeDownload: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    inputState: ChatInputState =
        rememberChatInputState(),
    isRecording: Boolean = false,
    sttDownloadState: SttModelState? = null,
    onRetrySttDownload: () -> Unit = {},
    onCancelSttDownload: () -> Unit = {},
    voiceModelItemState: DownloadItemUiState = DownloadItemUiState.Idle,
    onRequestVoiceDownload: () -> Unit = {},
    onCancelVoiceDownload: () -> Unit = {},
    // Setup screen: whether to render the voice card (BANGLA) and TTS install
    // card (pack missing), plus their actions and the manual "Go to chat" entry.
    showVoiceCard: Boolean = false,
    showTtsInstall: Boolean = false,
    onInstallTts: () -> Unit = {},
    onGoToChat: () -> Unit = {},
    voiceBackend: ChatVoiceInputController.Backend? = null,
    showVoiceModelDownloadAction: Boolean = false,
    onDownloadVoiceModel: () -> Unit = {},
    networkAvailable: Boolean = true,
    // Manual chat mode. `preferOnline` is the user's persisted choice (default
    // false = on-device); `onSetOnlineMode` writes it. Effective routing still
    // requires connectivity — see ChatViewModel.sendMessage.
    preferOnline: Boolean = false,
    onSetOnlineMode: (Boolean) -> Unit = {},
    moduleTitleLookup: (String?) -> String? = { null },
    onSourceDocTap: (sourceDocumentId: String, label: String, startPage: Int?) -> Unit = { _, _, _ -> },
    onFeedback: (messageId: Long, positive: Boolean) -> Unit = { _, _ -> },
    onFeedbackNote: (messageId: Long, note: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val error = (uiState as? ChatUiState.Ready)?.error
    LaunchedEffect(error) {
        if (!error.isNullOrBlank()) {
            snackbarHostState.showSnackbar(error)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (uiState) {
                is ChatUiState.Loading -> FullScreenLoader()
                is ChatUiState.SetupRequired -> CoachingSetupContent(
                    uiState = uiState,
                    voiceModelState = voiceModelItemState,
                    showVoiceCard = showVoiceCard,
                    showTtsInstall = showTtsInstall,
                    onRequestAiDownload = onRequestDownload,
                    onPauseAiDownload = onPauseDownload,
                    onResumeAiDownload = onResumeDownload,
                    onCancelAiDownload = onCancelDownload,
                    onRequestVoiceDownload = onRequestVoiceDownload,
                    onCancelVoiceDownload = onCancelVoiceDownload,
                    onInstallTts = onInstallTts,
                    onGoToChat = onGoToChat,
                    onClose = onClose,
                    showCloseIcon = showCloseIcon,
                )

                is ChatUiState.Ready -> ReadyChatContent(
                    uiState = uiState,
                    onSendMessage = onSendMessage,
                    onSendSuggested = onSendSuggested,
                    onSpeakMessage = onSpeakMessage,
                    onMicTap = onMicTap,
                    inputState = inputState,
                    isRecording = isRecording,
                    sttDownloadState = sttDownloadState,
                    onRetrySttDownload = onRetrySttDownload,
                    onCancelSttDownload = onCancelSttDownload,
                    voiceBackend = voiceBackend,
                    showVoiceModelDownloadAction = showVoiceModelDownloadAction,
                    onDownloadVoiceModel = onDownloadVoiceModel,
                    onClose = onClose,
                    showCloseIcon = showCloseIcon,
                    onClearHistory = onClearHistory,
                    networkAvailable = networkAvailable,
                    preferOnline = preferOnline,
                    onSetOnlineMode = onSetOnlineMode,
                    moduleTitleLookup = moduleTitleLookup,
                    onSourceDocTap = onSourceDocTap,
                    onFeedback = onFeedback,
                    onFeedbackNote = onFeedbackNote,
                )

                is ChatUiState.Error -> ErrorContent(message = uiState.message)
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.common_error_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}
