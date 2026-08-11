package com.medtroniclabs.microcoaching.ui.badges

import androidx.annotation.DrawableRes

/**
 * Progress state of an achievement badge / journey milestone.
 *
 * Drives every visual difference in the Badges tab and the Your Journey path: full-colour
 * artwork for [EARNED] / [CURRENT], greyscale behind a lock for [LOCKED]; only [EARNED]
 * counts toward the "X of N earned" tally.
 */
enum class BadgeState {
    /** Unlocked — full-colour artwork with a completed marker. */
    EARNED,

    /** The next badge within reach — full-colour artwork flagged "NOW". */
    CURRENT,

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
 */
data class AchievementBadge(
    val id: String,
    val name: String,
    val state: BadgeState,
    @get:DrawableRes val image: Int,
)

/**
 * One stop on the "Your Journey" learning path: a badge shown against the lesson it unlocks.
 * Shares [image]/[state] with the matching [AchievementBadge]; [code] and [title] are the
 * lesson's short code and name shown alongside the medallion on the path.
 */
data class JourneyMilestone(
    val id: String,
    val code: String,
    val title: String,
    val state: BadgeState,
    @get:DrawableRes val image: Int,
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
