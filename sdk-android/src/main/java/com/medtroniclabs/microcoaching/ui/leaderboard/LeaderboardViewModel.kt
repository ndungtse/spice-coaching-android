package com.medtroniclabs.microcoaching.ui.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the SK leaderboard tab: loads a [LeaderboardSnapshot] per selected period
 * from [LeaderboardDataSource] (a stub today — see that file's TODO).
 */
class LeaderboardViewModel(
    private val chwId: String,
    private val source: LeaderboardDataSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LeaderboardUiState>(LeaderboardUiState.Loading)
    val uiState: StateFlow<LeaderboardUiState> = _uiState.asStateFlow()

    init { selectPeriod(LeaderboardPeriod.ALL_TIME) }

    fun selectPeriod(period: LeaderboardPeriod) {
        _uiState.value = LeaderboardUiState.Loading
        viewModelScope.launch {
            _uiState.value = runCatching { source.load(chwId, period) }
                .fold(
                    onSuccess = { LeaderboardUiState.Ready(period, it) },
                    onFailure = { LeaderboardUiState.Error(it.message ?: "Failed to load leaderboard") },
                )
        }
    }

    companion object {
        fun factory(
            chwId: String,
            source: LeaderboardDataSource = StubLeaderboardDataSource(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LeaderboardViewModel(chwId, source) as T
        }
    }
}
