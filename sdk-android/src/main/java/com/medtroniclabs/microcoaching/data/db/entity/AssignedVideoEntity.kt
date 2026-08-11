package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable mirror of the audio/video documents assigned to a CHW, taken from the
 * `assigned_documents` half of the source-document catalogue. Backs the Training
 * sub-tab, which lists them with resume/progress state.
 *
 * A full assigned snapshot is fetched each inbound cycle and reconciled via
 * [com.medtroniclabs.microcoaching.data.db.dao.AssignedVideoDao.reconcileForUser]:
 * metadata and URLs are refreshed, [rank] preserves the server ordering, and
 * un-assigned rows are pruned — but the per-video **progress** columns are merged
 * **monotonically** (kept whenever the local value is ahead). The catalogue does
 * not return watch progress at all, so in practice that merge is what preserves
 * it: progress is device-local and only travels outward, as telemetry.
 *
 * [videoId] is the canonical `source_document_id`, and doubles as the
 * [com.medtroniclabs.microcoaching.data.asset.AssetCache] dedup key for an
 * offline download. Both presigned URLs are short-lived and persisted so cards
 * render and play offline; the sync that writes them is the only thing that can
 * refresh them, as no on-demand presign endpoint exists.
 *
 * @param videoId stable backend UUID (== `source_document_id`).
 * @param chwId owning CHW — assigned videos are per-user.
 * @param title display label.
 * @param description optional longer description; not supplied by the catalogue.
 * @param durationMs total length in ms; 0 when unknown, which the UI renders as
 *   no duration rather than "0:00". The catalogue does not supply it.
 * @param assignedAt ISO-8601 assignment timestamp from the backend.
 * @param presignedUrl latest presigned URL for the media itself, or null.
 * @param presignedExpiresAt absolute epoch-second expiry of [presignedUrl].
 * @param thumbnailUrl latest presigned thumbnail URL, or null.
 * @param thumbnailExpiresAt absolute epoch-second expiry of [thumbnailUrl].
 * @param thumbnailStoragePath stable storage path (survives URL-signature rotation).
 * @param lastPositionMs last known playback position in ms (resume anchor).
 * @param percentWatched 0–100 watched fraction (monotonic).
 * @param completed sticky once the video was watched to the end.
 * @param lastWatchedAt ISO-8601 timestamp of the latest progress update, or null.
 * @param rank server-supplied ordering (newest-first).
 * @param lastSynced wall-clock ms of the sync that wrote this row.
 */
@Entity(
    tableName = "assigned_video",
    indices = [Index("chw_id")],
)
data class AssignedVideoEntity(
    @PrimaryKey
    @ColumnInfo(name = "video_id")
    val videoId: String,

    @ColumnInfo(name = "chw_id")
    val chwId: String,

    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0,

    @ColumnInfo(name = "assigned_at")
    val assignedAt: String? = null,

    @ColumnInfo(name = "presigned_url")
    val presignedUrl: String? = null,

    @ColumnInfo(name = "presigned_expires_at_epoch_sec")
    val presignedExpiresAt: Long? = null,

    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,

    @ColumnInfo(name = "thumbnail_expires_at_epoch_sec")
    val thumbnailExpiresAt: Long? = null,

    @ColumnInfo(name = "thumbnail_storage_path")
    val thumbnailStoragePath: String? = null,

    @ColumnInfo(name = "last_position_ms")
    val lastPositionMs: Long = 0,

    @ColumnInfo(name = "percent_watched")
    val percentWatched: Double = 0.0,

    @ColumnInfo(name = "completed")
    val completed: Boolean = false,

    @ColumnInfo(name = "last_watched_at")
    val lastWatchedAt: String? = null,

    @ColumnInfo(name = "rank")
    val rank: Int = 0,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long? = null,
)
