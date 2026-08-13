package com.medtroniclabs.microcoaching.ui.badges

/**
 * Progress state of an achievement badge / journey milestone.
 *
 * A badge is earned or it isn't — there is no "next up" state, because the backend has no
 * notion of one and guessing at it (the lowest-sequence unearned badge) claimed an order the
 * catalogue doesn't actually promise. Drives every visual difference in the Badges tab and
 * the Your Journey path: full-colour artwork for [EARNED], greyscale behind a lock for
 * [LOCKED]; only [EARNED] counts toward the "X of N earned" tally.
 */
enum class BadgeState {
    /** Unlocked — full-colour artwork with a completed marker. */
    EARNED,

    /** Not yet unlocked — greyscale artwork behind a lock. */
    LOCKED,
}

/**
 * One achievement badge shown in the Badges tab grid.
 *
 * Named `AchievementBadge` — deliberately not `Badge`/`CoachingBadge` — because
 * [com.medtroniclabs.microcoaching.ui.common.CoachingBadge] already owns that name for the
 * small red pending-count badge overlaid on the chat FAB. That's an unrelated concept (a
 * numeric count pill); this is an earned/locked milestone medallion, hence the more
 * specific name.
 *
 * [imageUrl] is the backend's presigned artwork URL, read through the shared asset
 * cache so it renders offline after the first view.
 */
data class AchievementBadge(
    val id: String,
    val name: String,
    val state: BadgeState,
    val imageUrl: String?,
)

/**
 * One stop on the "Your Journey" learning path. Shares [imageUrl]/[state] with the matching
 * [AchievementBadge]; [title] is the badge's name and [code] its position along the path.
 */
data class JourneyMilestone(
    val id: String,
    val code: String,
    val title: String,
    val state: BadgeState,
    val imageUrl: String?,
)

/**
 * A resolved set of a CHW's badges plus the Your Journey milestones (same catalogue, one
 * entry per badge). [earnedCount] / [totalCount] label both the badge grid ("X of N earned")
 * and the journey path ("X of N milestones").
 */
data class BadgesSnapshot(
    val badges: List<AchievementBadge>,
    val milestones: List<JourneyMilestone>,
) {
    val earnedCount: Int get() = badges.count { it.state == BadgeState.EARNED }
    val totalCount: Int get() = badges.size
}
