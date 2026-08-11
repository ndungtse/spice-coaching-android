package com.medtroniclabs.microcoaching.ui.badges

/** UI state for the Badges tab (stub-backed today — see [BadgesDataSource]). */
sealed class BadgesUiState {
    object Loading : BadgesUiState()
    data class Error(val message: String) : BadgesUiState()
    data class Ready(val snapshot: BadgesSnapshot) : BadgesUiState()
}
