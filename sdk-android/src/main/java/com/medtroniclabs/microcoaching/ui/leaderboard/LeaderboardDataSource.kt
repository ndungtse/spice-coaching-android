package com.medtroniclabs.microcoaching.ui.leaderboard

/**
 * Source of leaderboard rankings.
 *
 * TODO(P2-backend): replace [StubLeaderboardDataSource] with a real implementation
 * once the backend leaderboard endpoint exists — see
 * docs/dashboads_and_leaderboard/04_implementation_plan.md (backend dependencies).
 */
interface LeaderboardDataSource {
    suspend fun load(chwId: String, period: LeaderboardPeriod): LeaderboardSnapshot
}

/** Fake data so the leaderboard is demoable before the backend lands. Names + avatars are placeholders. */
class StubLeaderboardDataSource : LeaderboardDataSource {

    // Placeholder face avatars (deterministic per person) until the backend supplies real ones.
    private fun avatar(id: Int) = "https://i.pravatar.cc/150?img=$id"

    override suspend fun load(chwId: String, period: LeaderboardPeriod): LeaderboardSnapshot {
        val group = LeaderboardGroup(name = "Dhamrai Upazila", memberCount = 28, updatedLabel = "12:00 AM")
        return when (period) {
            LeaderboardPeriod.THIS_WEEK -> {
                val you = LeaderboardEntry(1, "You", xp = 145, streakDays = 12, isCurrentUser = true, avatarUrl = avatar(15))
                LeaderboardSnapshot(
                    group = group,
                    entries = listOf(
                        you,
                        LeaderboardEntry(2, "Nasrin Akter", xp = 130, streakDays = 9, avatarUrl = avatar(9)),
                        LeaderboardEntry(3, "Sumaiya Islam", xp = 115, streakDays = 5, avatarUrl = avatar(32)),
                        LeaderboardEntry(4, "Rahela Khanam", xp = 95, streakDays = 7, avatarUrl = avatar(20)),
                        LeaderboardEntry(5, "Fatema Begum", xp = 80, streakDays = 4, avatarUrl = avatar(5)),
                    ),
                    currentUser = you,
                )
            }
            else -> {
                val you = LeaderboardEntry(5, "You", xp = 980, streakDays = 12, isCurrentUser = true, avatarUrl = avatar(15))
                LeaderboardSnapshot(
                    group = group,
                    entries = listOf(
                        LeaderboardEntry(1, "Fatema Begum", xp = 1840, streakDays = 14, avatarUrl = avatar(5)),
                        LeaderboardEntry(2, "Nasrin Akter", xp = 1620, streakDays = 9, avatarUrl = avatar(9)),
                        LeaderboardEntry(3, "Rahela Khanam", xp = 1410, streakDays = 7, avatarUrl = avatar(20)),
                        LeaderboardEntry(4, "Sumaiya Islam", xp = 1190, streakDays = 5, avatarUrl = avatar(32)),
                        you,
                    ),
                    currentUser = you,
                )
            }
        }
    }
}
