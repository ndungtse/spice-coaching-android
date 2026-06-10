package com.medtroniclabs.microcoaching.ui.onboarding

/** UI state for the onboarding pager managed by [OnboardingViewModel]. */
sealed class OnboardingUiState {

    /** Showing the coach-mark (first-launch overlay). Not used after first launch. */
    object CoachMark : OnboardingUiState()

    /**
     * Showing the onboarding slide carousel.
     * @param currentIndex Index of the currently visible slide (0-based).
     * @param totalSlides Total number of slides.
     */
    data class Slides(
        val currentIndex: Int = 0,
        val totalSlides: Int = OnboardingSlideData.SLIDE_COUNT,
    ) : OnboardingUiState()

    /** Onboarding complete — [CoachingNavGraph] should navigate to ModuleReady. */
    object Done : OnboardingUiState()
}
