package com.medtroniclabs.microcoaching.ui.podashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the document-usage drill-down. */
sealed class DocumentUsageDetailUiState {
    object Loading : DocumentUsageDetailUiState()
    data class Error(val message: String, val isAuth: Boolean = false) : DocumentUsageDetailUiState()
    data class Ready(val detail: DocumentUsageDetail) : DocumentUsageDetailUiState()
}

/**
 * Backs the document-usage drill-down for one document, keyed by [documentId].
 *
 * [range] is the window the PO had selected on the tab, passed down through the route
 * so this screen's figures cover the same period as the row that was tapped. Falls back
 * to [defaultRange] only when a caller has none.
 */
class DocumentUsageDetailViewModel(
    private val documentId: String,
    private val source: PODashboardDataSource,
    private val range: DateRange = defaultRange(),
    val networkAvailable: StateFlow<Boolean> = MicroCoachingSDK.getInstance().networkAvailable,
) : ViewModel() {

    private val _uiState = MutableStateFlow<DocumentUsageDetailUiState>(DocumentUsageDetailUiState.Loading)
    val uiState: StateFlow<DocumentUsageDetailUiState> = _uiState.asStateFlow()

    init { load() }

    /** Re-run the fetch from an error state. */
    fun retry() = load()

    private fun load() {
        _uiState.value = DocumentUsageDetailUiState.Loading
        viewModelScope.launch {
            _uiState.value = runCatching { source.loadDocumentUsageDetail(documentId, range) }
                .fold(
                    onSuccess = { detail ->
                        if (detail != null) DocumentUsageDetailUiState.Ready(detail)
                        else DocumentUsageDetailUiState.Error("Document not found")
                    },
                    onFailure = { DocumentUsageDetailUiState.Error(it.message ?: "Failed to load document", it.isDashboardAuthError()) },
                )
        }
    }

    companion object {
        fun factory(
            documentId: String,
            range: DateRange = defaultRange(),
            source: PODashboardDataSource = ApiPODashboardDataSource(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DocumentUsageDetailViewModel(documentId, source, range) as T
        }
    }
}
