package com.medtroniclabs.microcoaching.sync

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.entity.BadgeEntity
import com.medtroniclabs.microcoaching.network.BadgeSyncPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

// Badge sync — extension function on SyncApi.
private const val TAG = "SyncApi"

/**
 * Fetch the CHW's badge catalogue and replace their rows in `badge`.
 *
 * The endpoint takes no `since`: every call returns the full snapshot, which is
 * also what re-signs each badge's artwork URL — and what lets the swap prune
 * badges the tenant no longer has. On any failure the previous rows stay intact,
 * so the Badges tab still renders what it last knew.
 */
suspend fun SyncApi.pullBadges(): BadgesResult {
    val userId = chwId
    if (userId.isBlank()) {
        Log.d(TAG, "Badges: no CHW signed in — skipping.")
        return BadgesResult(skipped = true)
    }
    return safeInbound(
        label = "Badges",
        failureStage = "inbound_badges",
        call = { apiService.pullBadges() },
        onSuccess = { body ->
            val rows = mergeBadgePayloads(
                available = body.availableBadges,
                earned = body.earnedBadges,
                chwId = userId,
                nowMillis = System.currentTimeMillis(),
            )
            val dao = db.badgeDao()
            val before = dao.getAllForUser(userId).map { it.badgeId }.toSet()
            dao.replaceForUser(userId, rows)
            val earnedCount = rows.count { it.earnedAt != null }
            val pruned = (before - rows.map { it.badgeId }.toSet()).size
            Log.i(TAG, "Badges sync OK: total=${rows.size} earned=$earnedCount pruned=$pruned")
            BadgesResult(count = rows.size, earnedCount = earnedCount, prunedCount = pruned)
        },
        onFailure = { error, kind -> BadgesResult(error = error, errorKind = kind) },
    )
}

/**
 * Fold the response's two lists into the ordered rows the `badge` table holds.
 *
 * The lists are unioned by id rather than treating `available_badges` as the whole
 * catalogue: a badge whose definition was deactivated after the CHW earned it
 * appears only in `earned_badges`, and dropping it would take away a badge they
 * earned. Earned entries win on metadata, since they are the fresher record for
 * a badge that appears in both.
 *
 * A badge absent from *both* lists is gone from the tenant entirely and is pruned by
 * the swap in [com.medtroniclabs.microcoaching.data.db.dao.BadgeDao.replaceForUser].
 *
 * Ordering is by `sequence` (badges missing one sort last, then by name) and is
 * written into `rank`, so reads reproduce this order without re-sorting. Ids repeat
 * across the two lists, so the union is also what keeps the primary key from
 * silently dropping rows.
 *
 * Top-level and pure so the union/order/expiry rules are unit-testable without Room
 * or a network stub.
 */
internal fun mergeBadgePayloads(
    available: List<BadgeSyncPayload>,
    earned: List<BadgeSyncPayload>,
    chwId: String,
    nowMillis: Long,
): List<BadgeEntity> {
    val earnedById = earned.associateBy { it.id }
    val merged = LinkedHashMap<String, BadgeSyncPayload>()
    available.forEach { merged[it.id] = earnedById[it.id] ?: it }
    // Not putIfAbsent — that is API 24 and the SDK's minSdk is 23.
    earnedById.forEach { (id, payload) -> if (id !in merged) merged[id] = payload }

    val nowSec = nowMillis / 1000L
    return merged.values
        .sortedWith(
            compareBy(
                { it.sequence == null },
                { it.sequence ?: Int.MAX_VALUE },
                { it.name.orEmpty() },
            ),
        )
        .mapIndexed { idx, payload ->
            BadgeEntity(
                badgeId = payload.id,
                chwId = chwId,
                name = payload.name,
                domain = payload.domain,
                imageStoragePath = payload.imageStoragePath,
                imageUrl = payload.imagePresignedUrl,
                // The wire carries a relative lifetime; the column holds an absolute
                // expiry anchored to this row's own sync time.
                imageExpiresAt = payload.imagePresignedExpiresSeconds?.let { absoluteExpiry(nowSec, it) },
                sequence = payload.sequence ?: 0,
                moduleIds = payload.moduleIds
                    .takeIf { it.isNotEmpty() }
                    ?.let { ids -> JsonArray(ids.map(::JsonPrimitive)).toString() },
                earnedAt = payload.earnedAt,
                rank = idx,
                lastSynced = nowMillis,
            )
        }
}
