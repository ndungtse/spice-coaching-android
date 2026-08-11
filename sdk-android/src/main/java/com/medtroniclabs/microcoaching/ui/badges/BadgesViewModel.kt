package com.medtroniclabs.microcoaching.ui.badges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Badges tab: loads a [BadgesSnapshot] for [chwId] from [BadgesDataSource] (a
 * stub today — see that file's TODO).
 */
class BadgesViewModel(
    private val chwId: String,
    private val source: BadgesDataSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow<BadgesUiState>(BadgesUiState.Loading)
    val uiState: StateFlow<BadgesUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        _uiState.value = BadgesUiState.Loading
        viewModelScope.launch {
            _uiState.value = runCatching { source.load(chwId) }
                .fold(
                    onSuccess = { BadgesUiState.Ready(it) },
                    onFailure = { BadgesUiState.Error(it.message ?: "Failed to load badges") },
                )
        }
    }

    companion object {
        fun factory(
            chwId: String,
            source: BadgesDataSource = StubBadgesDataSource(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BadgesViewModel(chwId, source) as T
        }
    }
}
