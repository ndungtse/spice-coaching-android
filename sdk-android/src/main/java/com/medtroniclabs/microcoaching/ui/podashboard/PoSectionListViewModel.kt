package com.medtroniclabs.microcoaching.ui.podashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The full list a "Show all" screen renders — only the field for its [PoDashboardSection] is filled. */
data class SectionListPayload(
    val sks: List<SkSummary> = emptyList(),
    val moduleCompletion: List<ModuleCompletion> = emptyList(),
    val topQueries: List<TopQuery> = emptyList(),
    val documentUsage: List<DocumentUsageRow> = emptyList(),
)

sealed class PoSectionListUiState {
    object Loading : PoSectionListUiState()
    data class Error(val message: String, val isAuth: Boolean = false) : PoSectionListUiState()
    data class Ready(val payload: SectionListPayload) : PoSectionListUiState()
}

/**
 * Backs a section "Show all" screen ([PoSectionListScreen]) for one [section] over the
 * dashboard's selected [range]. SK/module sections reuse the full roster from
 * [PODashboardDataSource.loadDashboard]; the search sections fetch their full ranked list.
 */
class PoSectionListViewModel(
    private val section: PoDashboardSection,
    private val range: DateRange,
    private val chwId: String,
    private val source: PODashboardDataSource,
    val networkAvailable: StateFlow<Boolean> = MicroCoachingSDK.getInstance().networkAvailable,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PoSectionListUiState>(PoSectionListUiState.Loading)
    val uiState: StateFlow<PoSectionListUiState> = _uiState.asStateFlow()

    init { reload() }

    /** Re-run the fetch from an error state. */
    fun retry() = reload()

    private fun reload() {
        _uiState.value = PoSectionListUiState.Loading
        viewModelScope.launch {
            _uiState.value = runCatching { fetchPayload() }
                .fold(
                    onSuccess = { PoSectionListUiState.Ready(it) },
                    onFailure = { PoSectionListUiState.Error(it.message ?: "Failed to load", it.isDashboardAuthError()) },
                )
        }
    }

    private suspend fun fetchPayload(): SectionListPayload = when (section) {
        PoDashboardSection.MY_SKS, PoDashboardSection.REFRESHERS -> {
            val d = source.loadDashboard(chwId, range)
            SectionListPayload(sks = d.sks)
        }
        PoDashboardSection.MODULE_COMPLETION -> {
            val d = source.loadDashboard(chwId, range)
            SectionListPayload(moduleCompletion = d.moduleCompletion)
        }
        PoDashboardSection.SEARCHED_EXISTING ->
            SectionListPayload(topQueries = source.loadAllSearchedExisting(range))
        PoDashboardSection.SEARCHED_SUGGESTED ->
            SectionListPayload(topQueries = source.loadAllSearchedSuggested(range))
        PoDashboardSection.DOCUMENT_USAGE ->
            SectionListPayload(documentUsage = source.loadAllDocumentUsage(range))
    }

    companion object {
        fun factory(
            section: PoDashboardSection,
            range: DateRange,
            chwId: String,
            source: PODashboardDataSource = defaultPODashboardSource(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PoSectionListViewModel(section, range, chwId, source) as T
        }
    }
}
