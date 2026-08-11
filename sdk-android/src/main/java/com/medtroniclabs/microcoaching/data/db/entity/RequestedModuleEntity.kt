package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A training request the server has already recorded for this CHW, mirrored from
 * the module sync bundle.
 *
 * This supplements rather than replaces the local `module_requested` event log:
 * that log is written the instant the CHW submits, works offline, and is what the
 * Training Requests list and its duplicate guard read first. These rows only add
 * requests raised on another device, which the local log cannot know about.
 *
 * @param requestId server-assigned request id, and the dedup key.
 * @param chwId owning CHW — requests are per-user.
 * @param moduleId the module requested, when the CHW picked an existing one.
 * @param requestedModuleName free-text topic, when they asked for something new.
 * @param reason optional justification the CHW typed.
 * @param submittedAt ISO-8601 submission timestamp from the backend.
 * @param lastSynced wall-clock ms of the sync that wrote this row.
 */
@Entity(
    tableName = "requested_module",
    indices = [Index("chw_id")],
)
data class RequestedModuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,

    @ColumnInfo(name = "chw_id")
    val chwId: String,

    @ColumnInfo(name = "module_id")
    val moduleId: String? = null,

    @ColumnInfo(name = "requested_module_name")
    val requestedModuleName: String? = null,

    @ColumnInfo(name = "reason")
    val reason: String? = null,

    @ColumnInfo(name = "submitted_at")
    val submittedAt: String? = null,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long? = null,
)
