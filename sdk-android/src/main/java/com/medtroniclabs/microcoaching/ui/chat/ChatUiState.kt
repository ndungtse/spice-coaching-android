package com.medtroniclabs.microcoaching.ui.chat

import com.medtroniclabs.microcoaching.domain.decision.AnswerMode
import com.medtroniclabs.microcoaching.ui.screens.components.DownloadItemUiState

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

    /** Brief startup state while history loads. */
    object Loading : ChatUiState()

    /**
     * Chat is usable.
     *
     * Reached on every device, with or without the on-device model: retrieval over the local
     * index can always answer, so there is no device or download state that leaves a CHW
     * unable to ask a question. The model changes how an answer is worded, never whether one
     * is available, and so is reported here as a mode rather than as a precondition.
     *
     * @param messages current conversation history.
     * @param isGenerating true while a response is streaming.
     * @param streamingText partial text accumulated during streaming.
     * @param error non-null if the last inference failed.
     * @param answerMode which pipeline will answer the next message. Resolved per turn from
     *   connectivity, consent and engine state — see
     *   [com.medtroniclabs.microcoaching.domain.decision.resolveAnswerMode].
     * @param modelDownload the on-device model's lifecycle, in the same shape the voice pack
     *   uses, so one card renders either. [DownloadItemUiState.Idle] when the model is absent
     *   and nothing is in flight.
     * @param modelSizeBytes expected download size — server-resolved when available, otherwise
     *   the catalog constant. What the model *should* weigh, never what is on disk.
     * @param modelOnDiskBytes actual length of the model file, when present. Paired with
     *   [modelSizeBytes] so a partial file is visible rather than hidden behind the size it was
     *   supposed to be.
     * @param modelEligible whether this device may host the model at all. False hides the
     *   answer-style choice entirely rather than showing it disabled — there is no action to
     *   offer, and a greyed-out control invites a question with no answer.
     * @param modelEnabled the user's stored consent. Deliberately separate from
     *   [answerMode] being [AnswerMode.ON_DEVICE_ASSISTED]: consent holds while a download is
     *   still arriving or an engine load has failed, and a control driven by the resolved mode
     *   would appear to switch itself off underneath the user.
     * @param showModelOffer whether to render the dismissible offer. Only ever true on
     *   hardware that can host the model, while connected, and before the user has decided —
     *   accepting it starts a download of hundreds of megabytes.
     * @param suggestedQuestions quick-start chips.
     * @param feedback per-message thumbs state, keyed by [ChatMessage.id]: `true` = up,
     *   `false` = down, absent = unrated. In-memory only, so it resets on history reload.
     * @param feedbackNotes free-text detail from the thumbs-down sheet, keyed by
     *   [ChatMessage.id]. Held in memory and sent in the `chat_feedback_negative` event.
     */
    data class Ready(
        val messages: List<ChatMessage> = emptyList(),
        val isGenerating: Boolean = false,
        val streamingText: String = "",
        val error: String? = null,
        val answerMode: AnswerMode = AnswerMode.ON_DEVICE_DIRECT,
        val modelDownload: DownloadItemUiState = DownloadItemUiState.Idle,
        val modelSizeBytes: Long? = null,
        val modelOnDiskBytes: Long? = null,
        val modelEligible: Boolean = false,
        val modelEnabled: Boolean = false,
        val showModelOffer: Boolean = false,
        val suggestedQuestions: List<SuggestedQuestion> = emptyList(),
        val feedback: Map<Long, Boolean> = emptyMap(),
        val feedbackNotes: Map<Long, String> = emptyMap(),
    ) : ChatUiState()

    /**
     * Chat could not be opened at all — a database failure, not a missing model.
     *
     * Rare by construction: retrieval-only answering needs no model and no network, so the
     * states that used to land here now open chat in [Ready] instead.
     */
    data class Error(val message: String) : ChatUiState()
}
