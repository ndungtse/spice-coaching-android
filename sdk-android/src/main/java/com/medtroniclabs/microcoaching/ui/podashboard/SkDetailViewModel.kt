package com.medtroniclabs.microcoaching.ui.podashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Backs the single-SK "My SK" detail screen, keyed by [skId]. */
class SkDetailViewModel(
    private val skId: String,
    private val source: PODashboardDataSource,
    val networkAvailable: StateFlow<Boolean> = MicroCoachingSDK.getInstance().networkAvailable,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SkDetailUiState>(SkDetailUiState.Loading)
    val uiState: StateFlow<SkDetailUiState> = _uiState.asStateFlow()

    init { load() }

    /** Re-run the fetch from an error state. */
    fun retry() = load()

    private fun load() {
        _uiState.value = SkDetailUiState.Loading
        viewModelScope.launch {
            _uiState.value = runCatching { source.loadSkDetail(skId) }
                .fold(
                    onSuccess = { detail ->
                        if (detail != null) SkDetailUiState.Ready(detail)
                        else SkDetailUiState.Error("SK not found")
                    },
                    onFailure = { SkDetailUiState.Error(it.message ?: "Failed to load SK", it.isDashboardAuthError()) },
                )
        }
    }

    companion object {
        fun factory(
            skId: String,
            source: PODashboardDataSource = ApiPODashboardDataSource(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SkDetailViewModel(skId, source) as T
        }
    }
}
