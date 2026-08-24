package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Low-friction digital proficiency signal — one row per observed SPICE
 * interaction, carrying only a success flag and an error type.
 *
 * **This table has no writer.** Its one producer wrote a `sync_attempt` row
 * after every outbound push, which no backend consumer read and which kept the
 * outbound queue permanently non-empty (each push created the row that made the
 * next tick find work). The entity, DAO and the `OutboundSyncApi` paging arms
 * are retained so the table needs no migration; they operate on an empty table.
 *
 * Chat turns are not recorded here — `digital_help_used` lives in
 * [CoachingEventEntity], which carries the `trigger_type` / `inference_mode` /
 * `validator_status` / `fallback_used` columns this entity does not.
 */
@Entity(
    tableName = "digital_proficiency_event",
    indices = [
        Index(value = ["sync_status"]),
        Index(value = ["chw_id", "event_type"]),
    ]
)
data class DigitalProficiencyEventEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "event_schema_version")
    val eventSchemaVersion: Int = 1,

    @ColumnInfo(name = "sdk_version")
    val sdkVersion: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "chw_id")
    val chwId: String,

    @ColumnInfo(name = "tenant_id")
    val tenantId: Int? = null,

    /**
     * Interaction type. Nothing writes a value today — see the class KDoc.
     */
    @ColumnInfo(name = "event_type")
    val eventType: String,

    /** True if the interaction completed successfully. */
    @ColumnInfo(name = "success")
    val success: Boolean,

    /** Machine-readable error code on failure (e.g. "auth_failed", "network_timeout"). Null on success. */
    @ColumnInfo(name = "error_type")
    val errorType: String? = null,

    /** Device connectivity at event time. Values: online | offline | restored */
    @ColumnInfo(name = "network_state")
    val networkState: String? = null,

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
