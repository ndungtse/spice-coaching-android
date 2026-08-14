package com.medtroniclabs.microcoaching.ui.podashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the "Top Searched Suggested" module/topic drill-down. */
sealed class SuggestionDetailUiState {
    object Loading : SuggestionDetailUiState()
    data class Error(val message: String, val isAuth: Boolean = false) : SuggestionDetailUiState()
    data class Ready(val detail: SuggestionDetail) : SuggestionDetailUiState()
}

/** Backs the "Top Searched Suggested" drill-down for one suggestion, keyed by [suggestionId]. */
class SuggestionDetailViewModel(
    private val suggestionId: String,
    private val source: PODashboardDataSource,
    val networkAvailable: StateFlow<Boolean> = MicroCoachingSDK.getInstance().networkAvailable,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SuggestionDetailUiState>(SuggestionDetailUiState.Loading)
    val uiState: StateFlow<SuggestionDetailUiState> = _uiState.asStateFlow()

    init { load() }

    /** Re-run the fetch from an error state. */
    fun retry() = load()

    private fun load() {
        _uiState.value = SuggestionDetailUiState.Loading
        viewModelScope.launch {
            _uiState.value = runCatching { source.loadSuggestionDetail(suggestionId) }
                .fold(
                    onSuccess = { detail ->
                        if (detail != null) SuggestionDetailUiState.Ready(detail)
                        else SuggestionDetailUiState.Error("Suggestion not found")
                    },
                    onFailure = { SuggestionDetailUiState.Error(it.message ?: "Failed to load suggestion", it.isDashboardAuthError()) },
                )
        }
    }

    companion object {
        fun factory(
            suggestionId: String,
            source: PODashboardDataSource = ApiPODashboardDataSource(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SuggestionDetailViewModel(suggestionId, source) as T
        }
    }
}
