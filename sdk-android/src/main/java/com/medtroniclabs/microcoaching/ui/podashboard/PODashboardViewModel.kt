package com.medtroniclabs.microcoaching.ui.podashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.medtroniclabs.microcoaching.MicroCoachingSDK

/**
 * Backs the PO dashboard tab and its list drill-downs (Responsive SKs, Chatbot Usage,
 * Modules Completed). Loads a [PoDashboard] for the selected [DateRange] from
 * [PODashboardDataSource] (a stub today — see that file's TODO). Defaults to the last 7 days.
 */
class PODashboardViewModel(
    private val chwId: String,
    private val source: PODashboardDataSource,
    /**
     * Window to open on. Drill-downs pass the tab's selected range so their figures
     * are computed over the same window the PO was looking at; the tab itself passes
     * null and starts on [defaultDashboardRange].
     */
    initialRange: DateRange? = null,
    /** SDK connectivity signal — drives the offline indicator and reconnect refresh (AC4/AC5). */
    val networkAvailable: StateFlow<Boolean> = MicroCoachingSDK.getInstance().networkAvailable,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PODashboardUiState>(PODashboardUiState.Loading)
    val uiState: StateFlow<PODashboardUiState> = _uiState.asStateFlow()

    // Selected window is held separately from the loaded data so the date-range
    // picker stays visible during Loading/Error (a failed load must not hide it).
    private val _range = MutableStateFlow(initialRange ?: defaultDashboardRange())
    val range: StateFlow<DateRange> = _range.asStateFlow()

    // Drives the pull-to-refresh spinner without flipping the whole tab to Loading.
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Wall-clock time of the last SUCCESSFUL dashboard load. The dashboard is a
    // live-fetch feature with no persisted sync timestamp (unlike coaching's
    // SyncPrefs), so we capture "how fresh is what's on screen" here and surface
    // it as a "Last synced …" subtitle. 0L until the first success; left untouched
    // on failure so a failed refresh never claims fresh data.
    private val _lastLoadedAt = MutableStateFlow(0L)
    val lastLoadedAt: StateFlow<Long> = _lastLoadedAt.asStateFlow()

    init {
        load(showLoading = true)
        // Foreground refresh when connectivity returns while the tab is open (AC4).
        viewModelScope.launch {
            var wasAvailable = networkAvailable.value
            networkAvailable.collect { available ->
                if (available && !wasAvailable) load(showLoading = false)
                wasAvailable = available
            }
        }
    }

    fun selectRange(range: DateRange) {
        _range.value = range
        load(showLoading = true)
    }

    /** Re-fetch the current range (pull-to-refresh) without blanking existing content. */
    fun refresh() = load(showLoading = false)

    /** Re-run the load from an error state, showing the spinner. */
    fun retry() = load(showLoading = true)

    private fun load(showLoading: Boolean) {
        if (showLoading) _uiState.value = PODashboardUiState.Loading else _isRefreshing.value = true
        viewModelScope.launch {
            _uiState.value = runCatching { source.loadDashboard(chwId, _range.value) }
                .fold(
                    onSuccess = {
                        // Reflect the snapshot's own fetch time (fresh == now, cached == stored)
                        // so the "Last synced" subtitle survives process death (AC3).
                        _lastLoadedAt.value = it.fetchedAt
                        // The offline cache holds a single snapshot and serves it whatever
                        // window was asked for, so a cached load can answer with a different
                        // period than the one requested — on a cold start offline, or when an
                        // online fetch fails after the picker already moved. Adopt the window
                        // the data actually covers, so the picker, the figures, and the range
                        // the drill-downs open on all describe the same period.
                        if (it.range != _range.value) _range.value = it.range
                        PODashboardUiState.Ready(it)
                    },
                    onFailure = {
                        PODashboardUiState.Error(it.message ?: "Failed to load dashboard", it.isDashboardAuthError())
                    },
                )
            _isRefreshing.value = false
        }
    }

    companion object {
        fun factory(
            chwId: String,
            initialRange: DateRange? = null,
            source: PODashboardDataSource = defaultPODashboardSource(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PODashboardViewModel(chwId, source, initialRange) as T
        }
    }
}

/**
 * Default window: the last 7 days (inclusive), as UTC start-of-day millis.
 *
 * Delegates to [defaultRange] rather than repeating the arithmetic — this used to
 * compute "today" from the device clock while the data source computed it in UTC,
 * so the tab and any screen falling back to the source default could open on
 * windows a day apart.
 */
private fun defaultDashboardRange(): DateRange = defaultRange()
