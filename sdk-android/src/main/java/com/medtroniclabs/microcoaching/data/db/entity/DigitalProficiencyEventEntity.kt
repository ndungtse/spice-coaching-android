package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Low-friction digital proficiency signal captured from SPICE usage.
 *
 * Schema follows DataDesign v1.1 §4.3. One row per observed digital interaction.
 * These signals feed the CHW gap profile, which drives morning card selection (UC-1).
 *
 * Event types tracked here:
 *   sync_attempt       — data sync attempt (success/failure)
 *   login_attempt      — login success/failure [TEAM-CONFIRM: requires SPICE hook]
 *   form_submit        — SPICE form submission outcome [TEAM-CONFIRM: requires SPICE hook]
 *
 * NOTE: `digital_help_used` (chat assistant turns) is NOT recorded into this
 * entity. It lives in [CoachingEventEntity] because the v1.1 spec needs the
 * `trigger_type` / `inference_mode` / `validator_status` / `fallback_used`
 * columns this entity intentionally doesn't carry. See
 * [com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder.recordDigitalHelpUsed].
 *
 * Recorded by [DigitalSignalRecorder] (Phase F). Synced to Knowledge Layer by
 * OutboundSyncWorker (Phase B) for backend gap-profile computation.
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
     * Interaction type.
     * Values: digital_help_used | sync_attempt | login_attempt | form_submit
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
