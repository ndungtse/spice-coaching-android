package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One achievement badge as the backend knows it, for the signed-in CHW.
 *
 * Filled wholesale from `GET /sync/badges`, which returns the tenant's active
 * catalogue and the CHW's earned badges together. Earned-ness lives on the row as
 * [earnedAt] rather than in a separate table: the response gives no other per-CHW
 * state, and a single ordered list is exactly what the Badges grid and the Your
 * Journey path both read.
 *
 * @param badgeId backend badge id (the response's `id`).
 * @param chwId the CHW these rows were synced for — the table holds one CHW's view
 *   at a time, swapped wholesale by `BadgeDao.replaceForUser`.
 * @param imageStoragePath stable object path of the artwork, independent of any
 *   signature. Kept as the durable identity behind [imageUrl].
 * @param imageUrl presigned artwork URL from the last sync. Read through
 *   `AssetCache`, which keys on the URL path so a rotated signature still resolves
 *   to the same cached file.
 * @param imageExpiresAt absolute epoch-second expiry of [imageUrl] — the wire
 *   carries a relative lifetime, converted once at sync time.
 * @param sequence backend display order within the catalogue.
 * @param moduleIds JSON array of the module ids this badge is awarded for. Stored
 *   for the journey's not-yet-wired "Start lesson" target.
 * @param earnedAt ISO-8601 timestamp the CHW earned this badge; null means unearned.
 * @param rank resolved position after ordering by [sequence], so reads reproduce
 *   server order without re-sorting.
 * @param lastSynced wall-clock millis of the sync that wrote this row.
 */
@Entity(tableName = "badge", indices = [Index("chw_id")])
data class BadgeEntity(
    @PrimaryKey @ColumnInfo(name = "badge_id") val badgeId: String,
    @ColumnInfo(name = "chw_id") val chwId: String,
    @ColumnInfo(name = "name") val name: String? = null,
    @ColumnInfo(name = "domain") val domain: String? = null,
    @ColumnInfo(name = "image_storage_path") val imageStoragePath: String? = null,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    @ColumnInfo(name = "image_expires_at_epoch_sec") val imageExpiresAt: Long? = null,
    @ColumnInfo(name = "sequence") val sequence: Int = 0,
    @ColumnInfo(name = "module_ids") val moduleIds: String? = null,
    @ColumnInfo(name = "earned_at") val earnedAt: String? = null,
    /**
     * Set on-device the moment every module behind this badge is complete, so the
     * tick appears without waiting for a sync.
     */
    @ColumnInfo(name = "locally_earned_at") val locallyEarnedAt: String? = null,
    @ColumnInfo(name = "rank") val rank: Int = 0,
    @ColumnInfo(name = "last_synced") val lastSynced: Long? = null,
)
