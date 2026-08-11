package com.medtroniclabs.microcoaching.ui.badges

import com.medtroniclabs.microcoaching.R

/**
 * Source of a CHW's achievement badges + journey milestones for the Badges tab.
 *
 * TODO(backend): swap in the real badges/achievements source when it exists — the SDK has
 * no badges entity/DTO/endpoint yet, so [StubBadgesDataSource] is the only implementation
 * today.
 */
interface BadgesDataSource {
    suspend fun load(chwId: String): BadgesSnapshot
}

/**
 * Display-only stub so the Badges tab is demoable before the backend lands. Names, states,
 * and milestone titles are hardcoded placeholders — kept unlocalized English, mirroring
 * [com.medtroniclabs.microcoaching.ui.leaderboard.StubLeaderboardDataSource].
 * Exactly 13 badges — 5 earned, 1 current, 7 locked — mirroring the design mock's
 * "5 of 13". The Your Journey milestones reuse the same badge artwork + state, one lesson
 * per badge.
 */
class StubBadgesDataSource : BadgesDataSource {

    override suspend fun load(chwId: String): BadgesSnapshot {
        val badges = listOf(
            AchievementBadge("first-step", "First Step", BadgeState.EARNED, R.drawable.badge_first_step),
            AchievementBadge("first-question", "First Question", BadgeState.EARNED, R.drawable.badge_first_question),
            AchievementBadge("building-patterns", "Building Patterns", BadgeState.EARNED, R.drawable.badge_building_pattern),
            AchievementBadge("danger-spotter", "Danger Spotter", BadgeState.EARNED, R.drawable.badge_danger_spotter),
            AchievementBadge("emergency-ready", "Emergency Ready", BadgeState.EARNED, R.drawable.badge_emergency_ready),
            AchievementBadge("growth-tracker", "Growth Tracker", BadgeState.CURRENT, R.drawable.badge_growth_tracker),
            AchievementBadge("immunisation-pro", "Immunisation Pro", BadgeState.LOCKED, R.drawable.badge_immunization_pro),
            AchievementBadge("uhis-navigator", "UHIS Navigator", BadgeState.LOCKED, R.drawable.badge_uhis_navigator),
            AchievementBadge("ten-refreshers", "Ten Refreshers", BadgeState.LOCKED, R.drawable.badge_ten_refreshers),
            AchievementBadge("twenty-five-refreshers", "Twenty-Five Refreshers", BadgeState.LOCKED, R.drawable.badge_twenty_five_refreshers),
            AchievementBadge("five-mastered", "Five Mastered", BadgeState.LOCKED, R.drawable.badge_five_mastered),
            AchievementBadge("half-way", "Half Way", BadgeState.LOCKED, R.drawable.badge_half_way),
            AchievementBadge("full-collection", "Full Collection", BadgeState.LOCKED, R.drawable.badge_full_collection),
        )

        // One journey milestone per badge — shared artwork + state, paired with the lesson
        // it unlocks. Codes/titles are placeholder coaching content until the real learning
        // path exists.
        val codes = listOf(
            "101", "102", "103", "104", "105",
            "201", "202", "203", "204", "205",
            "301", "302", "303",
        )
        val titles = listOf(
            "Recognizing Danger Signs",
            "ANC Visit Essentials",
            "Building Care Routines",
            "Spotting Emergencies",
            "Emergency Referral Steps",
            "Growth Monitoring",
            "Immunization Schedules",
            "Navigating UHIS",
            "Refresher Habits",
            "Consistent Practice",
            "Mastering the Basics",
            "Halfway Milestone",
            "Full Coaching Mastery",
        )
        val milestones = badges.mapIndexed { i, badge ->
            JourneyMilestone(
                id = badge.id,
                code = codes[i],
                title = titles[i],
                state = badge.state,
                image = badge.image,
            )
        }

        return BadgesSnapshot(badges = badges, milestones = milestones)
    }
}
