package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.medtroniclabs.microcoaching.data.db.entity.AssignedVideoEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/reconcile access for the per-CHW assigned-video catalogue that backs the
 * Training sub-tab. The list is server-authoritative for metadata but the
 * progress columns are device-authoritative between syncs, so the mutating
 * operations are [reconcileForUser] (a full-snapshot merge) and
 * [updateProgress] (a monotonic local write from the player).
 */
@Dao
interface AssignedVideoDao {

    /**
     * Reactive Training-list source for [chwId], most-recently-assigned first — so the
     * newest assignment is the featured hero card. ISO-8601 `assigned_at` sorts
     * chronologically as text; nulls fall last, with server [rank] as the tiebreaker.
     */
    @Query("SELECT * FROM assigned_video WHERE chw_id = :chwId ORDER BY assigned_at DESC, rank ASC")
    fun getForUser(chwId: String): Flow<List<AssignedVideoEntity>>

    /** One row (used for the resume anchor / stream resolution at play time). */
    @Query("SELECT * FROM assigned_video WHERE video_id = :videoId AND chw_id = :chwId")
    suspend fun getById(videoId: String, chwId: String): AssignedVideoEntity?

    @Query("SELECT COUNT(*) FROM assigned_video WHERE chw_id = :chwId")
    suspend fun countForUser(chwId: String): Int

    /** True while [chwId] has at least one assigned video not yet completed. EXISTS avoids loading rows. */
    @Query("SELECT EXISTS(SELECT 1 FROM assigned_video WHERE chw_id = :chwId AND completed = 0)")
    fun hasIncompleteFlow(chwId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<AssignedVideoEntity>)

    /**
     * Reconcile un-assignments after a full assigned-snapshot pull: drop the
     * CHW's rows whose `video_id` is no longer in [videoIds]. Callers skip this
     * on an empty response so a transient blank snapshot can't wipe the list.
     */
    @Query("DELETE FROM assigned_video WHERE chw_id = :chwId AND video_id NOT IN (:videoIds)")
    suspend fun deleteForUserNotIn(chwId: String, videoIds: List<String>)

    /** Progress projection used by [reconcileForUser] to merge monotonically. */
    @Query(
        "SELECT video_id AS videoId, last_position_ms AS lastPositionMs, " +
            "percent_watched AS percentWatched, completed AS completed, " +
            "last_watched_at AS lastWatchedAt, duration_ms AS durationMs " +
            "FROM assigned_video WHERE chw_id = :chwId",
    )
    suspend fun existingProgress(chwId: String): List<VideoProgressRow>

    /**
     * Record a video's length, but only while it is still unknown.
     *
     * Duration is discovered from several places — the catalogue, the player, a
     * probe of the media itself — and they all agree, so the first answer wins and
     * later ones are no-ops. Guarding on the current value also means this can be
     * called freely without churning the row and waking every observer.
     */
    @Query(
        "UPDATE assigned_video SET duration_ms = :durationMs " +
            "WHERE video_id = :videoId AND chw_id = :chwId AND duration_ms <= 0 AND :durationMs > 0",
    )
    suspend fun updateDurationIfUnknown(videoId: String, chwId: String, durationMs: Long)

    /** Video ids for this CHW whose length is still unknown — the probe's work list. */
    @Query("SELECT video_id FROM assigned_video WHERE chw_id = :chwId AND duration_ms <= 0")
    suspend fun idsMissingDuration(chwId: String): List<String>

    /**
     * Monotonic local progress write from the player — never regresses. Keeps the
     * greater `last_position_ms` / `percent_watched`, and `completed` stays true
     * once set. Mirrors the backend's monotonic merge so the card + resume anchor
     * reflect the newest playback immediately, before telemetry round-trips.
     */
    @Query(
        """
        UPDATE assigned_video
        SET last_position_ms = MAX(last_position_ms, :positionMs),
            percent_watched = MAX(percent_watched, :percent),
            completed = CASE WHEN completed = 1 OR :completed = 1 THEN 1 ELSE 0 END,
            last_watched_at = :watchedAt
        WHERE video_id = :videoId AND chw_id = :chwId
        """,
    )
    suspend fun updateProgress(
        videoId: String,
        chwId: String,
        positionMs: Long,
        percent: Double,
        completed: Boolean,
        watchedAt: String?,
    )

    /**
     * Atomically reconcile [chwId]'s assigned videos to [rows] — upsert
     * metadata/thumbnail while merging server progress monotonically against any
     * locally-newer progress, then prune rows no longer assigned. Skips the whole
     * swap on an empty snapshot so a transient blank response can't clear the list.
     */
    @Transaction
    suspend fun reconcileForUser(chwId: String, rows: List<AssignedVideoEntity>) {
        if (rows.isEmpty()) return
        val existing = existingProgress(chwId).associateBy { it.videoId }
        val merged = rows.map { mergeVideoProgress(it, existing[it.videoId]) }
        upsertAll(merged)
        deleteForUserNotIn(chwId, rows.map { it.videoId })
    }
}

/** Lightweight progress projection for the monotonic reconcile merge. */
data class VideoProgressRow(
    val videoId: String,
    val lastPositionMs: Long,
    val percentWatched: Double,
    val completed: Boolean,
    val lastWatchedAt: String?,
    val durationMs: Long,
)

/**
 * Merge a freshly-synced [row] with the device's [prev] progress **monotonically**:
 * keep the greater `last_position_ms` / `percent_watched`, keep `completed` once
 * either side set it, and prefer the incoming `last_watched_at` (falling back to
 * the previous). Returns [row] unchanged when there is no prior progress.
 *
 * Duration is carried the same way. The catalogue reports it as unknown until the
 * backend has probed the media, so without this a length the device worked out for
 * itself would be erased by the very next sync.
 *
 * Pure so the merge (the "a lower / delayed sync must not regress progress"
 * guarantee) is unit-testable without a Room harness.
 */
internal fun mergeVideoProgress(row: AssignedVideoEntity, prev: VideoProgressRow?): AssignedVideoEntity {
    if (prev == null) return row
    return row.copy(
        lastPositionMs = maxOf(row.lastPositionMs, prev.lastPositionMs),
        percentWatched = maxOf(row.percentWatched, prev.percentWatched),
        completed = row.completed || prev.completed,
        lastWatchedAt = row.lastWatchedAt ?: prev.lastWatchedAt,
        durationMs = if (row.durationMs > 0) row.durationMs else prev.durationMs,
    )
}
