package com.medtroniclabs.microcoaching.ui.badges

import com.medtroniclabs.microcoaching.data.db.entity.BadgeEntity

/**
 * Turn the synced rows into what the Badges grid and the Your Journey path render.
 *
 * A badge is [BadgeState.EARNED] when either timestamp is set and [BadgeState.LOCKED]
 * otherwise. There is no third state: the response says which badges exist and which
 * this CHW holds, and there is nothing in between to derive.
 *
 * The two timestamps differ only in who noticed first. `earned_at` is the server's;
 * `locally_earned_at` is the device's, written when the CHW finishes the last module
 * behind a badge so the tick lands immediately rather than a sync later.
 *
 * The journey's accent [JourneyMilestone.code] is the milestone's 1-based position
 * along the path rather than the raw `sequence`: sequence only has to define an
 * order, so it may be 0-based or sparse, and rendering it verbatim would show a "0"
 * as the first milestone.
 *
 * Pure — unit-testable without Room or Compose.
 */
internal fun List<BadgeEntity>.toBadgesSnapshot(): BadgesSnapshot {
    val badges = map { row ->
        AchievementBadge(
            id = row.badgeId,
            name = row.name.orEmpty(),
            state = row.badgeState(),
            imageUrl = row.imageUrl,
        )
    }
    val milestones = mapIndexed { index, row ->
        JourneyMilestone(
            id = row.badgeId,
            code = (index + 1).toString(),
            title = row.name.orEmpty(),
            state = row.badgeState(),
            imageUrl = row.imageUrl,
        )
    }
    return BadgesSnapshot(badges = badges, milestones = milestones)
}

private fun BadgeEntity.badgeState(): BadgeState =
    if (earnedAt != null || locallyEarnedAt != null) BadgeState.EARNED else BadgeState.LOCKED
