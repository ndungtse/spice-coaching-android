package com.medtroniclabs.microcoaching.ui.podashboard

/** UI state for the PO dashboard tab + its drill-down list screens. */
sealed class PODashboardUiState {
    object Loading : PODashboardUiState()
    data class Error(val message: String) : PODashboardUiState()
    data class Ready(val dashboard: PoDashboard) : PODashboardUiState()
}

/** UI state for the single-SK detail screen. */
sealed class SkDetailUiState {
    object Loading : SkDetailUiState()
    data class Error(val message: String) : SkDetailUiState()
    data class Ready(val detail: SkDetail) : SkDetailUiState()
}
