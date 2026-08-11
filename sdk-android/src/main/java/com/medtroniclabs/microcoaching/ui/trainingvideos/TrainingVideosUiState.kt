// The subclasses below necessarily reference their deprecated parent.
@file:Suppress("DEPRECATION")

package com.medtroniclabs.microcoaching.ui.trainingvideos

/** UI state for the Training sub-tab, backed by the `assigned_video` catalogue. */
@Deprecated(
    "Superseded by SectionState<List<TrainingVideo>> — its Error case was never produced. " +
        "See ui/common/SectionState.kt",
)
sealed class TrainingVideosUiState {
    object Loading : TrainingVideosUiState()
    data class Error(val message: String) : TrainingVideosUiState()
    data class Ready(val videos: List<TrainingVideo>) : TrainingVideosUiState() {
        /** Newest video (the list is newest-first) — the tab's featured card. */
        val featured: TrainingVideo? get() = videos.firstOrNull()
    }
}
