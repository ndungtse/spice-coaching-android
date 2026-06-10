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
     * No model file found on device.
     * [ChatScreen] should show a download prompt.
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
     */
    data class ModelNotReady(
        val downloadProgress: Int = -1,
        val isDownloading: Boolean = false,
        val isPaused: Boolean = false,
        val downloadBytesDownloaded: Long = 0L,
        val downloadTotalBytes: Long = 0L,
    ) : ChatUiState()

    /**
     * Model is loaded and chat is ready.
     * @param messages Current conversation history.
     * @param isGenerating True while the LLM is streaming a response.
     * @param streamingText Partial text being accumulated during streaming.
     * @param error Non-null if the last inference failed.
     * @param suggestedQuestions Quick-start chips from morning card cache.
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
    ) : ChatUiState()

    /** Unrecoverable error (e.g. model load failed, DB error). */
    data class Error(val message: String) : ChatUiState()
}
