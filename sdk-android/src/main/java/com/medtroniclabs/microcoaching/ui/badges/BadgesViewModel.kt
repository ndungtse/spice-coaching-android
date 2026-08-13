package com.medtroniclabs.microcoaching.ui.badges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.dao.BadgeDao
import com.medtroniclabs.microcoaching.sync.SyncDomain
import com.medtroniclabs.microcoaching.ui.common.SectionState
import com.medtroniclabs.microcoaching.ui.common.sectionStateFor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the Badges tab. Reactively reads the CHW's badges from [BadgeDao] — the
 * durable mirror of `GET /sync/badges` — so the grid re-renders whenever inbound
 * sync lands new rows or a newly-earned badge arrives.
 */
class BadgesViewModel(
    private val chwId: String,
    private val dao: BadgeDao,
) : ViewModel() {

    private val sdk = MicroCoachingSDK.getInstance()

    /**
     * The badges plus the outcome of the pull that fills their table, so an empty grid can
     * say which it is: "the refresh failed" or "this tenant has no badges". Cached rows
     * always win — a failed refresh marks them stale rather than replacing them with an error.
     */
    val state: StateFlow<SectionState<BadgesSnapshot>> =
        combine(
            dao.getForUser(chwId),
            sdk.syncStatus.outcomeFor(SyncDomain.BADGES),
            sdk.networkAvailable,
            sdk.syncStatus.isSyncing,
        ) { rows, outcome, online, syncing ->
            when (val section = sectionStateFor(rows = rows, outcome = outcome, offline = !online, syncing = syncing)) {
                is SectionState.Loading -> SectionState.Loading
                is SectionState.Failed -> SectionState.Failed(
                    error = section.error,
                    cached = section.cached?.toBadgesSnapshot(),
                )
                is SectionState.Ready -> SectionState.Ready(
                    data = section.data.toBadgesSnapshot(),
                    stale = section.stale,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectionState.Loading)

    /** Re-run inbound sync from the tab's error state. */
    fun retry() {
        runCatching {
            if (sdk.config.backendUrl.isNotBlank()) sdk.syncCoordinator.triggerNow()
        }
    }

    companion object {
        fun factory(chwId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BadgesViewModel(chwId, MicroCoachingSDK.getInstance().database.badgeDao()) as T
        }
    }
}
