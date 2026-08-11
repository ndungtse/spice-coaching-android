package com.medtroniclabs.microcoaching.ui.chat

import com.medtroniclabs.microcoaching.network.SourceDocumentRef

/**
 * Public model representing a single chat message.
 * Used in [CoachingDataRepository] and [ChatSession] — safe to hold in SPICE ViewModels.
 */
data class ChatMessage(
    val id: Long = 0,
    val sessionId: String,
    /** "user" or "assistant" */
    val role: String,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    /** OTel trace ID linking this message to its inference span. Null for user messages. */
    val traceId: String? = null,
    /** Where the assistant response came from. See [MessageSource]. Empty for user messages or history. */
    val source: String = "",
    /**
     * Optional in-flight metadata stamped by [ChatViewModel] on assistant messages:
     * grounding chunk IDs, refusal outcome (one of `refused_scope` / `refused_no_ground`
     * / `refused_unsafe` / `served_grounded`), and similar tuning signals. Not persisted —
     * a future TTS layer can read this to announce "module reference: …" before the body
     * but the field gracefully degrades to null if no TTS consumer is wired (chat_plan.md §B6).
     */
    val meta: ChatMessageMeta? = null,
    /**
     * Rich source-document references backing this assistant message, carried
     * verbatim from the BM25-matched module. Each chip renders this document's
     * own `title` / `original_filename`. Empty for user messages, refusals, and
     * ungrounded responses. Persisted on the [com.medtroniclabs.microcoaching
     * .data.db.entity.ChatMessageEntity] so chips re-appear in history without
     * re-querying the original module.
     */
    val sourceDocuments: List<SourceDocumentRef> = emptyList(),
    /**
     * Dominant BM25-matched module family for this message. Used by the chip
     * row only as a *fallback* label when a source document has no title (older
     * modules). Null when the matched module is no longer in cache or for
     * non-grounded messages.
     */
    val groundingModuleFamilyId: String? = null,
    /**
     * 1-indexed PDF page the in-app source-document viewer should open at
     * when a citation chip is tapped. Sourced from the BM25-matched card's
     * `source_pages` field at message-persist time. Null falls back to page 1.
     */
    val startPage: Int? = null,
) {
    /** Deprecated id-only view, derived from [sourceDocuments]. */
    @Deprecated("Use sourceDocuments", ReplaceWith("sourceDocuments.map { it.id }"))
    val sourceDocumentIds: List<String> get() = sourceDocuments.map { it.id }
}

/**
 * In-memory metadata attached to an assistant [ChatMessage] for TTS / telemetry handoff.
 * See [ChatMessage.meta].
 *
 * The trailing fields ([moduleId] … [fallbackUsed]) snapshot the pipeline context
 * of the turn that produced this response so a later thumbs-up/down tap can emit a
 * `chat_feedback_*` event mirroring the original `digital_help_used` row (Events
 * Modelling 1.4) — segmenting feedback by online/edge, module, and validator outcome
 * without a message-store join. Not persisted; history-loaded messages have `meta =
 * null`, so feedback on them falls back to inferring [inferenceMode] from
 * [ChatMessage.source].
 */
data class ChatMessageMeta(
    val outcome: String,
    val groundedFrom: List<String> = emptyList(),
    /** Version-specific module UUID that grounded this response. Null on refusals. */
    val moduleId: String? = null,
    /** `"online"` (backend RAG) or `"edge"` (on-device Gemma / BM25). */
    val inferenceMode: String? = null,
    /** ConnectivityManager snapshot at response time: `"online"` / `"offline"`. */
    val networkState: String? = null,
    /** B4 validator status for the served turn: `"pass"` / `"fail"` / null. */
    val validatorStatus: String? = null,
    /** True when clinician-authored content was served in place of LLM output. */
    val fallbackUsed: Boolean? = null,
    /**
     * The served response **object as a JSON string** — the online RAG response,
     * or the offline-constructed equivalent (same shape, empty retrieval fields).
     * Captured at serve time so a later thumbs-up/down can put it under
     * `payload_json.response` without reconstructing it. Null for history-loaded
     * messages, where it is rebuilt from the visible text on demand.
     */
    val responseJson: String? = null,
    /**
     * The CHW's raw question that produced this response, captured at serve time
     * so a later thumbs-up/down can echo it into `payload_json.question`
     * (Events-Modelling 1.7). Like the rest of [ChatMessageMeta] this is not
     * persisted, so history-loaded messages carry null here and the feedback
     * payload simply omits `question` — best-effort, same as [responseJson].
     */
    val question: String? = null,
)

object ChatRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

object MessageSource {
    const val LOCAL_MODEL = "local_model"
    const val RAG_API = "rag_api"
}
