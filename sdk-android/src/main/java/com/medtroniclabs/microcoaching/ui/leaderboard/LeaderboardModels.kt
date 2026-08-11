package com.medtroniclabs.microcoaching.ui.leaderboard

import androidx.annotation.StringRes
import com.medtroniclabs.microcoaching.R

/** Time window for the leaderboard ranking. */
enum class LeaderboardPeriod(@get:StringRes val labelRes: Int) {
    ALL_TIME(R.string.leaderboard_period_all_time),
    THIS_MONTH(R.string.leaderboard_period_this_month),
    THIS_WEEK(R.string.leaderboard_period_this_week),
}

/** One ranked SK row. Display-only — never persisted or logged (PII). */
data class LeaderboardEntry(
    val rank: Int,
    val displayName: String,
    val xp: Int,
    val streakDays: Int,
    val isCurrentUser: Boolean = false,
    val avatarUrl: String? = null,
)

/** The peer group the ranking is scoped to (e.g. an Upazila). */
data class LeaderboardGroup(
    val name: String,
    val memberCount: Int,
    val updatedLabel: String,
)

/** A resolved leaderboard snapshot for one period. */
data class LeaderboardSnapshot(
    val group: LeaderboardGroup,
    val entries: List<LeaderboardEntry>,
    val currentUser: LeaderboardEntry?,
) {
    val isLeading: Boolean get() = currentUser?.rank == 1
}
