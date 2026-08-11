package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row cache of the last complete PO dashboard snapshot (MED-I516).
 *
 * [id] is a constant sentinel (0) so upsert(REPLACE) always keeps exactly one row.
 * [chwId] guards reads — a snapshot is only served to the same PO that wrote it.
 * [payloadJson] is a `StrictJson`-serialized `PoDashboard`; [fetchedAt] drives the
 * "Last synced" subtitle. The table is wiped on logout via
 * [com.medtroniclabs.microcoaching.MicroCoachingSDK.clearDashboardCache].
 */
@Entity(tableName = "dashboard_cache")
data class DashboardCacheEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = 0,
    @ColumnInfo(name = "chw_id") val chwId: String,
    @ColumnInfo(name = "from_date") val fromDate: String,
    @ColumnInfo(name = "to_date") val toDate: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)
