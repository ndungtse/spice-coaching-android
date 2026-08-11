package com.medtroniclabs.microcoaching.ui.chat

/**
 * A real suggested question populated from the CHW's morning card cache.
 * [moduleFamilyId] anchors the suggestion to its source module family so the LLM prompt
 * can pull grounded clinical guidance when the user taps it.
 */
data class SuggestedQuestion(
    val question: String,
    val banglaQuestion: String = "",
    val moduleFamilyId: String? = null,
)

/** UI state for [ChatViewModel]. Observed by [ChatScreen]. */
sealed class ChatUiState {

    /** Initial state — checking model availability. */
    object Loading : ChatUiState()

    /**
     * On-device setup is still needed before chat can open. [ChatScreen] shows
     * the [com.medtroniclabs.microcoaching.ui.screens.components.CoachingSetupContent]
     * surface — the AI model card (capable devices), the Bengali voice pack card
     * (auto-downloading), and the TTS read-aloud card when its pack is missing.
     *
     * Reached both on capable devices whose Gemma model isn't on disk yet AND on
     * low-end devices (which never download the AI model but may still need the
     * voice/TTS packs). The old behavior — low-end short-circuiting straight to
     * [Ready] and the model auto-jumping into chat — is replaced by this gate.
     *
     * @param downloadProgress -1 when no download has started; 0–100 when in
     *   flight or paused. Held across pause/resume so the UI keeps showing the
     *   last percent until a fresh `RUNNING` emission overwrites it.
     * @param isDownloading true while WorkManager is actively running the worker.
     * @param isPaused true after the user pressed pause; mutually exclusive with
     *   [isDownloading]. When both are false and [downloadProgress] is -1, the
     *   UI shows the initial "Download AI Model" CTA.
     * @param downloadBytesDownloaded bytes received so far. 0 until first progress emit.
     * @param downloadTotalBytes total expected bytes. 0 when the server hasn't reported
     *   Content-Length (chunked transfer). UI should fall back to a percent-only display
     *   when this is 0.
     * @param aiRequired false on low-end devices — they run in retrieval-only mode, so
     *   no AI model card is shown and no AI download is attempted. The setup screen then
     *   only surfaces the voice/TTS packs.
     * @param aiReady true once the Gemma model has finished downloading and is present on
     *   disk. Drives the AI card's "Done" state and enables the manual "Go to chat" button
     *   (the automatic transition into chat waits for the voice pack too — see
     *   [ChatViewModel.maybeAutoEnterChat]).
     * @param aiSizeBytes real download size for the selected model, resolved from the
     *   server. Null until that lands (or when the server is unreachable), in which
     *   case the card falls back to the catalog's approximate constant.
     * @param loadError why the engine failed to load a model that IS on disk. Set only
     *   on the bounce-back path — the user tapped "Go to chat", the load failed, and
     *   they were returned here; without it the screen looks inert.
     */
    data class SetupRequired(
        val downloadProgress: Int = -1,
        val isDownloading: Boolean = false,
        val isPaused: Boolean = false,
        val downloadBytesDownloaded: Long = 0L,
        val downloadTotalBytes: Long = 0L,
        val aiRequired: Boolean = true,
        val aiReady: Boolean = false,
        val aiSizeBytes: Long? = null,
        val loadError: String? = null,
    ) : ChatUiState()

    /**
     * Model is loaded and chat is ready.
     * @param messages Current conversation history.
     * @param isGenerating True while the LLM is streaming a response.
     * @param streamingText Partial text being accumulated during streaming.
     * @param error Non-null if the last inference failed.
     * @param suggestedQuestions Quick-start chips from morning card cache.
     * @param feedback Per-message thumbs state, keyed by [ChatMessage.id]:
     *   `true` = thumbs-up, `false` = thumbs-down, absent = no rating. In-memory
     *   only — not persisted, so it resets on history reload. Thumbs-up
     *   emits a `chat_feedback_positive` event immediately; thumbs-down emits
     *   `chat_feedback_negative` when the detail sheet closes (so the note rides
     *   along).
     * @param feedbackNotes Free-text detail a CHW typed in the thumbs-down sheet,
     *   keyed by [ChatMessage.id]. Held in memory (resets on history reload) AND
     *   sent to the backend inside the `chat_feedback_negative` event's
     *   `payload_json.feedback` (Events Modelling 1.5).
     */
    data class Ready(
        val messages: List<ChatMessage> = emptyList(),
        val isGenerating: Boolean = false,
        val streamingText: String = "",
        val error: String? = null,
        val modelPresent: Boolean = false,
        val isModelDownloading: Boolean = false,
        val modelDownloadProgress: Int = -1,
        val modelDownloadBytesDownloaded: Long = 0L,
        val modelDownloadTotalBytes: Long = 0L,
        val suggestedQuestions: List<SuggestedQuestion> = emptyList(),
        val feedback: Map<Long, Boolean> = emptyMap(),
        val feedbackNotes: Map<Long, String> = emptyMap(),
    ) : ChatUiState()

    /** Unrecoverable error (e.g. model load failed, DB error). */
    data class Error(val message: String) : ChatUiState()
}
