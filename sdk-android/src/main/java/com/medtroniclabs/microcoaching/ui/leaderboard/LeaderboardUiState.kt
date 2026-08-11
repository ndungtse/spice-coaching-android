package com.medtroniclabs.microcoaching.ui.leaderboard

/** UI state for the SK leaderboard tab. */
sealed class LeaderboardUiState {
    object Loading : LeaderboardUiState()
    data class Error(val message: String) : LeaderboardUiState()
    data class Ready(
        val period: LeaderboardPeriod,
        val snapshot: LeaderboardSnapshot,
    ) : LeaderboardUiState()
}
