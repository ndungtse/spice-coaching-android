package com.medtroniclabs.microcoaching.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ViewModel for the onboarding flow (coach mark → slides).
 *
 * Uses [AndroidViewModel] so it can access [Application.applicationContext]
 * for SharedPrefs reads/writes without holding a `Context` reference directly.
 *
 * Created without Hilt — uses Compose's default `viewModel()` factory inside
 * [CoachingFlowActivity] (AndroidViewModelFactory is the default).
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Slides())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /**
     * Mark the coach-mark as dismissed and save to SharedPrefs so it is
     * never shown again on subsequent launches.
     */
    fun markOnboarded() {
        OnboardingPrefs.markOnboarded(getApplication())
    }

    /** Advance to the next slide. Signals [OnboardingUiState.Done] after the last slide. */
    fun nextSlide() {
        val current = _uiState.value as? OnboardingUiState.Slides ?: return
        val nextIndex = current.currentIndex + 1
        _uiState.value = if (nextIndex < current.totalSlides) {
            current.copy(currentIndex = nextIndex)
        } else {
            OnboardingUiState.Done
        }
    }

    /** Called when the user taps "Skip" or "Done" on the slide carousel. */
    fun markSlideDone() {
        _uiState.value = OnboardingUiState.Done
    }
}
