package com.medtroniclabs.microcoaching.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ai.voice.ChatVoiceInputController
import com.medtroniclabs.microcoaching.ai.voice.TtsState
import com.medtroniclabs.microcoaching.ai.voice.VoiceInputController
import com.medtroniclabs.microcoaching.ai.voice.stt.SttModelState
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper
import com.medtroniclabs.microcoaching.ui.common.ChatInputState
import com.medtroniclabs.microcoaching.ui.common.rememberChatInputState
import com.medtroniclabs.microcoaching.ui.screens.ChatScreen
import com.medtroniclabs.microcoaching.ui.screens.components.toVoiceDownloadItemState

/**
 * Compose surface for the AI Coaching chat — shared between the standalone
 * [CoachingChatFragment] (for hosts that embed chat directly in their layout)
 * and [CoachingChatBottomSheet] (which hosts it inside a Material3
 * `ModalBottomSheet`). Owns the [ChatViewModel] factory, locale wrapping, and
 * voice-controller resolution so both call sites stay one liners.
 *
 * @param patientId Anonymized patient ID (use patientTrackId from SPICE).
 *   Empty string for non-patient-specific coaching.
 * @param systemContext Optional coaching context / system prompt override.
 * @param onClose Invoked when the chat surface should be dismissed. The
 *   [ChatScreen] header's close button calls this. When embedded as a plain
 *   Fragment without a sheet, pass an empty lambda — the close icon stays
 *   hidden in that case.
 * @param showCloseIcon When `true`, the header renders a trailing close icon
 *   wired to [onClose]. The sheet uses `true`; standalone Fragment embeds
 *   should pass `false` so the host owns dismissal.
 */
@Composable
fun CoachingChatSurface(
    patientId: String,
    systemContext: String,
    onClose: () -> Unit,
    showCloseIcon: Boolean,
) {
    val context = LocalContext.current
    val viewModelStoreOwner = LocalViewModelStoreOwner.current
        ?: error("CoachingChatSurface requires a ViewModelStoreOwner in CompositionLocal")
    val application = context.applicationContext as android.app.Application

    val sdk = MicroCoachingSDK.getInstance()

    // Resolve the registered STT controller up-front so the mic icon visibility
    // is stable for the lifetime of this composition. SDK.Builder installs an
    // AndroidSpeechRecognizerEngine by default when enableVoice=true; hosts can
    // override via Builder.voiceInputController(...).
    val voiceInput: VoiceInputController? = remember {
        sdk.voiceInputController?.takeIf { sdk.config.enableVoice && it.isAvailable() }.also {
            if (sdk.voiceInputController == null && sdk.config.enableVoice) {
                android.util.Log.w(
                    "CoachingChatSurface",
                    "config.enableVoice=true but no VoiceInputController registered — mic hidden.",
                )
            }
        }
    }

    val viewModel: ChatViewModel = remember(patientId, systemContext) {
        ViewModelProvider(
            viewModelStoreOwner,
            ChatViewModel.Factory(
                application = application,
                patientId = patientId,
                systemContext = systemContext,
            ),
        )[ChatViewModel::class.java]
    }

    // ── STT state ─────────────────────────────────────────────────────────────
    // Hoisted to this composable so transcription callbacks can mutate the
    // chat input field. The reference impl
    // (gemma-2b-kotlin/.../SpeechScreen.kt) follows the same shape.
    val inputState = rememberChatInputState()
    var isRecording by rememberSaveable { mutableStateOf(false) }

    // Snapshot of the input text taken when listening starts, so partial
    // transcripts append to whatever the user had typed before tapping the mic.
    var preRecordingText by remember { mutableStateOf("") }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
        )
    }

    // Stop any in-flight dictation when this surface leaves composition (sheet
    // dismissed mid-recording). The SDK-singleton controller otherwise kept the
    // anonymous listener below — and the UI state it captures — alive until the
    // recognizer completed on its own, and onError would toast with a dead context.
    DisposableEffect(voiceInput) {
        onDispose {
            if (isRecording) voiceInput?.stopListening()
        }
    }

    fun beginListening(controller: VoiceInputController) {
        preRecordingText = inputState.text
        isRecording = true
        controller.startListening(
            object : VoiceInputController.TranscriptionListener {
                override fun onPartial(transcript: String) {
                    inputState.text = mergeDraft(preRecordingText, transcript)
                }

                override fun onResult(transcript: String) {
                    // Guard: if the recording was already stopped externally (e.g.
                    // user pressed Send while mic was active) AND the text field was
                    // cleared by the send, don't repopulate. The partial transcript
                    // was already captured and sent.
                    if ((isRecording || inputState.text.isNotBlank()) && transcript.isNotBlank()) {
                        inputState.text = mergeDraft(preRecordingText, transcript)
                    }
                    isRecording = false
                }

                override fun onError(message: String) {
                    isRecording = false
                    if (message.isNotBlank()) {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            voiceInput?.let(::beginListening)
        } else {
            // Resolve the permission-denied copy against the SDK locale, not the
            // host app's. Otherwise SPICE running in English would emit the
            // English toast inside a Bangla-mode chat.
            val langCtx = SdkLocaleHelper.wrap(context, sdk.language)
            Toast.makeText(
                context,
                langCtx.getString(R.string.chat_voice_permission_denied),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val onMicTap: (() -> Unit)? = voiceInput?.let { controller ->
        {
            when {
                !hasMicPermission ->
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                isRecording -> {
                    controller.stopListening()
                    // Optimistic local update; the recognizer's onResult/onError
                    // will arrive shortly and is idempotent.
                    isRecording = false
                }
                else -> beginListening(controller)
            }
        }
    }

    // Active STT backend pill driven by the routing decision the controller
    // takes per session (PlatformOnDevice / PlatformCloud / OfflineSherpa).
    // Only available when the SDK installed the default controller — hosts
    // that supplied their own via Builder.voiceInputController() get no badge.
    val chatVoiceController = voiceInput as? ChatVoiceInputController
    val voiceBackend by (chatVoiceController?.activeBackend
        ?: kotlinx.coroutines.flow.MutableStateFlow(ChatVoiceInputController.Backend.Unknown))
        .collectAsState()

    // Bengali sherpa STT model download lifecycle. The same state powers
    // three surfaces: the inline banner above the chat input (only when the
    // chat is already Ready, mid-flight), the voice card on the CoachingSetupContent
    // screen, and visibility of the "Download voice model" overflow menu item.
    //
    // The download is auto-started by ChatViewModel.autoStartOnDevicePacks() on
    // chat open (idempotent), so the voice card shows progress on the setup screen
    // in parallel with the AI model — not only after it lands.
    val sttDownloadState by sdk.sttModelManager.state.collectAsState()
    val bannerState = when (sttDownloadState) {
        is SttModelState.Idle, is SttModelState.Ready -> null
        else -> sttDownloadState
    }
    val voiceModelItemState = sttDownloadState.toVoiceDownloadItemState()

    // Setup screen gating: the voice card only makes sense in BANGLA (sherpa STT
    // is Bengali-only); the TTS "Install" card only when the read-aloud pack is
    // actually missing (Android can't download it in-app with progress).
    val showVoiceCard = sdk.language == Language.BANGLA
    val ttsState by viewModel.tts.state.collectAsState()
    val showTtsInstall = ttsState is TtsState.LanguageMissing

    // Overflow item is visible only when the SDK is in BN mode and the model
    // is neither present nor in flight (Downloading / Extracting). When in
    // flight, the SttDownloadBanner above the input is the relevant control.
    val showVoiceModelDownloadAction = sdk.language == Language.BANGLA &&
        sttDownloadState !is SttModelState.Ready &&
        sttDownloadState !is SttModelState.Downloading &&
        sttDownloadState !is SttModelState.Extracting

    val langCtx = SdkLocaleHelper.wrap(context, sdk.language)
    val networkAvailable by sdk.networkAvailable.collectAsState()
    CompositionLocalProvider(LocalContext provides langCtx) {
        val uiState by viewModel.uiState.collectAsState()
        // Manual chat mode — on-device by default, user opts into online via the
        // header chip. Persisted in SharedPreferences (ChatModePrefs).
        val preferOnline by viewModel.preferOnline.collectAsState()

        // Resolve `moduleFamilyId → title` for any assistant message that
        // carries source attribution. Run inside a LaunchedEffect so the
        // ViewModel's suspend `moduleTitleFor(...)` lookup runs off the main
        // thread and the snapshot-backed map drives recomposition once the
        // title lands. Until then the chip row falls back to
        // `R.string.chat_source_default`.
        val moduleTitles = remember { mutableStateMapOf<String, String?>() }
        val groundingFamilies = (uiState as? ChatUiState.Ready)?.messages
            ?.mapNotNull { it.groundingModuleFamilyId?.takeIf { id -> id.isNotBlank() } }
            ?.toSet().orEmpty()
        LaunchedEffect(groundingFamilies) {
            groundingFamilies.forEach { familyId ->
                if (!moduleTitles.containsKey(familyId)) {
                    moduleTitles[familyId] = viewModel.moduleTitleFor(familyId)
                }
            }
        }
        val moduleTitleLookup: (String?) -> String? = { familyId ->
            familyId?.let { moduleTitles[it] }
        }

        ChatScreen(
            uiState = uiState,
            onSendMessage = { viewModel.sendMessage(it) },
            // sendSuggestion handles localised text resolution, marks the
            // suggestion as used in SharedPreferences, and refreshes the chip
            // row from the remaining unused pool — see ChatSuggestionsRepository.
            onSendSuggested = viewModel::sendSuggestion,
            onRequestDownload = viewModel::requestModelDownload,
            onPauseDownload = viewModel::pauseModelDownload,
            onResumeDownload = viewModel::resumeModelDownload,
            onCancelDownload = viewModel::cancelModelDownload,
            onClearHistory = viewModel::clearChatHistory,
            onSpeakMessage = viewModel::speakText,
            onMicTap = onMicTap,
            inputState = inputState,
            isRecording = isRecording,
            sttDownloadState = bannerState,
            onRetrySttDownload = { sdk.sttModelManager.triggerBengaliDownload() },
            onCancelSttDownload = { sdk.sttModelManager.cancelBengaliDownload() },
            voiceModelItemState = voiceModelItemState,
            onRequestVoiceDownload = { sdk.sttModelManager.triggerBengaliDownload() },
            onCancelVoiceDownload = { sdk.sttModelManager.cancelBengaliDownload() },
            // Setup screen wiring: voice card visibility (BANGLA), TTS install
            // card (pack missing) + its system-installer action, and the manual
            // "Go to chat" entry (the both-ready auto-enter is driven by the VM).
            showVoiceCard = showVoiceCard,
            showTtsInstall = showTtsInstall,
            onInstallTts = viewModel::installTtsData,
            onGoToChat = viewModel::enterChat,
            voiceBackend = voiceBackend.takeIf { it != ChatVoiceInputController.Backend.Unknown },
            showVoiceModelDownloadAction = showVoiceModelDownloadAction,
            onDownloadVoiceModel = { sdk.sttModelManager.triggerBengaliDownload() },
            networkAvailable = networkAvailable,
            preferOnline = preferOnline,
            onSetOnlineMode = viewModel::setPreferOnline,
            moduleTitleLookup = moduleTitleLookup,
            onSourceDocTap = { id, label, startPage -> viewModel.openSourceDocument(id, label, startPage) },
            onFeedback = viewModel::submitFeedback,
            onFeedbackNote = viewModel::commitNegativeFeedback,
            onClose = onClose,
            showCloseIcon = showCloseIcon,
        )
    }
}

/**
 * Merge a transcript chunk into the pre-recording draft. If the draft is empty
 * we just take the transcript; otherwise we glue them with a single space.
 */
private fun mergeDraft(preExisting: String, transcript: String): String =
    if (preExisting.isBlank()) transcript else "$preExisting $transcript"
