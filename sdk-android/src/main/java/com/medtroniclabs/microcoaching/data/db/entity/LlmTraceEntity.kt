package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Audit record for every LLM inference call (online Gemini or edge Gemma).
 *
 * Schema follows DataDesign v1.1 §4.2. One row per inference attempt, linked
 * to the parent [CoachingEventEntity] via [coachingEventId].
 *
 * Enables observability: validator pass-rate, latency, fallback frequency,
 * and token usage — without storing any prompt or response text in plain form.
 *
 * Written by the coaching decision engine (Phase D) and edge path (Phase G).
 * Synced to the Knowledge Layer for backend analytics.
 */
@Entity(
    tableName = "llm_trace",
    indices = [
        Index(value = ["sync_status"]),
        Index(value = ["coaching_event_id"]),
    ]
)
data class LlmTraceEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "event_schema_version")
    val eventSchemaVersion: Int = 1,

    @ColumnInfo(name = "sdk_version")
    val sdkVersion: String,

    /** FK to the [CoachingEventEntity] that triggered this inference. */
    @ColumnInfo(name = "coaching_event_id")
    val coachingEventId: String,

    /** Model identifier. E.g. "gemma3-1b-it-int4" or "gemini-2.0-flash". */
    @ColumnInfo(name = "model_id")
    val modelId: String,

    /** Template used to build the prompt. E.g. "T-HTN-COUNSEL-v2". */
    @ColumnInfo(name = "prompt_template_id")
    val promptTemplateId: String,

    @ColumnInfo(name = "prompt_template_version")
    val promptTemplateVersion: Int,

    /** Serialised ContextPack JSON sent to the model. Stored for debugging; never logged. */
    @ColumnInfo(name = "context_pack_json")
    val contextPackJson: String,

    /** Raw model output JSON. Null when the call failed before a response arrived. */
    @ColumnInfo(name = "raw_response_json")
    val rawResponseJson: String? = null,

    /** True if OutputValidator passed for this response. */
    @ColumnInfo(name = "validated")
    val validated: Boolean,

    /** Rule ID that caused validation failure. Null when [validated] is true. */
    @ColumnInfo(name = "validator_failure")
    val validatorFailure: String? = null,

    /** True if a pre-authored Bangla fallback card was served instead of the model output. */
    @ColumnInfo(name = "fallback_used")
    val fallbackUsed: Boolean = false,

    /** Wall-clock inference latency in milliseconds. */
    @ColumnInfo(name = "latency_ms")
    val latencyMs: Int? = null,

    @ColumnInfo(name = "input_tokens")
    val inputTokens: Int? = null,

    @ColumnInfo(name = "output_tokens")
    val outputTokens: Int? = null,

    @ColumnInfo(name = "timestamp_local")
    val timestampLocal: Long = System.currentTimeMillis(),

    /** Values: pending | synced | failed */
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "pending",

    @ColumnInfo(name = "synced_at")
    val syncedAt: Long? = null,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
)
