package com.medtroniclabs.microcoaching.ui.podashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the "Top Searched Existing" module drill-down. */
sealed class SearchedModuleDetailUiState {
    object Loading : SearchedModuleDetailUiState()
    data class Error(val message: String, val isAuth: Boolean = false) : SearchedModuleDetailUiState()
    data class Ready(val detail: SearchedModuleDetail) : SearchedModuleDetailUiState()
}

/**
 * Backs the "Top Searched Existing" drill-down for one module, keyed by [moduleId].
 *
 * [range] is the window the PO had selected on the tab, passed down through the route
 * so this screen's figures cover the same period as the row that was tapped. Falls back
 * to [defaultRange] only when a caller has none.
 */
class SearchedModuleDetailViewModel(
    private val moduleId: String,
    private val source: PODashboardDataSource,
    private val range: DateRange = defaultRange(),
    val networkAvailable: StateFlow<Boolean> = MicroCoachingSDK.getInstance().networkAvailable,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchedModuleDetailUiState>(SearchedModuleDetailUiState.Loading)
    val uiState: StateFlow<SearchedModuleDetailUiState> = _uiState.asStateFlow()

    init { load() }

    /** Re-run the fetch from an error state. */
    fun retry() = load()

    private fun load() {
        _uiState.value = SearchedModuleDetailUiState.Loading
        viewModelScope.launch {
            _uiState.value = runCatching { source.loadSearchedModuleDetail(moduleId, range) }
                .fold(
                    onSuccess = { detail ->
                        if (detail != null) SearchedModuleDetailUiState.Ready(detail)
                        else SearchedModuleDetailUiState.Error("Module not found")
                    },
                    onFailure = { SearchedModuleDetailUiState.Error(it.message ?: "Failed to load module", it.isDashboardAuthError()) },
                )
        }
    }

    companion object {
        fun factory(
            moduleId: String,
            range: DateRange = defaultRange(),
            source: PODashboardDataSource = ApiPODashboardDataSource(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SearchedModuleDetailViewModel(moduleId, source, range) as T
        }
    }
}
