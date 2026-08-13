package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.medtroniclabs.microcoaching.data.db.entity.BadgeEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/replace access for the CHW's badge catalogue.
 *
 * Every column except `locally_earned_at` is backend-authored, so the server
 * snapshot replaces the CHW's rows wholesale. That one column records a badge the
 * device awarded before the server caught up, and is carried across the swap the
 * way `AssignedVideoDao` carries watch progress — otherwise the tick would appear
 * and then vanish on the next sync.
 */
@Dao
interface BadgeDao {

    /** The CHW's badges in backend order — earned, current and locked alike. */
    @Query("SELECT * FROM badge WHERE chw_id = :chwId ORDER BY rank ASC")
    fun getForUser(chwId: String): Flow<List<BadgeEntity>>

    /** One-shot read for the earning rule. */
    @Query("SELECT * FROM badge WHERE chw_id = :chwId")
    suspend fun getAllForUser(chwId: String): List<BadgeEntity>

    /** Badge id → its local award time, for rows that have one. */
    @Query("SELECT badge_id, locally_earned_at FROM badge WHERE chw_id = :chwId AND locally_earned_at IS NOT NULL")
    suspend fun localEarnings(chwId: String): List<LocalBadgeEarning>

    @Query("UPDATE badge SET locally_earned_at = :earnedAt WHERE badge_id = :badgeId")
    suspend fun markLocallyEarned(badgeId: String, earnedAt: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<BadgeEntity>)

    @Query("DELETE FROM badge WHERE chw_id = :chwId")
    suspend fun deleteForUser(chwId: String)

    /**
     * Replace this CHW's badges with [rows], carrying any local award forward.
     *
     * An empty list is honoured: the caller only writes after a successful
     * response, so no rows means the tenant has no active badges — which the UI
     * renders as an empty grid rather than an error.
     */
    @Transaction
    suspend fun replaceForUser(chwId: String, rows: List<BadgeEntity>) {
        val localByBadgeId = localEarnings(chwId).associate { it.badgeId to it.locallyEarnedAt }
        deleteForUser(chwId)
        if (rows.isEmpty()) return
        upsertAll(
            rows.map { row ->
                localByBadgeId[row.badgeId]?.let { row.copy(locallyEarnedAt = it) } ?: row
            },
        )
    }
}

/** Projection for [BadgeDao.localEarnings]. */
data class LocalBadgeEarning(
    @androidx.room.ColumnInfo(name = "badge_id") val badgeId: String,
    @androidx.room.ColumnInfo(name = "locally_earned_at") val locallyEarnedAt: String,
)
