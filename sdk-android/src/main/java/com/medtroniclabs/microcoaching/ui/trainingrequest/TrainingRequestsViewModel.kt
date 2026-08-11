package com.medtroniclabs.microcoaching.ui.trainingrequest

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.dao.RequestedModuleDao
import com.medtroniclabs.microcoaching.data.db.dao.ModuleDao
import com.medtroniclabs.microcoaching.data.localized.LocalizedText
import com.medtroniclabs.microcoaching.data.mapper.parseIsoMillis
import com.medtroniclabs.microcoaching.util.friendlyDateLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface TrainingRequestsUiState {
    object Loading : TrainingRequestsUiState
    data class Ready(val requests: List<TrainingRequestRow>) : TrainingRequestsUiState
    data class Error(@StringRes val messageRes: Int) : TrainingRequestsUiState
}

/**
 * Drives the training-requests hub.
 *
 * A request is not a REST submit — it is a `module_requested` telemetry event in
 * the durable local `coaching_event` log, so the hub reads that log directly and
 * reactively: a freshly-recorded request appears with no manual refresh, online or
 * off. The server's own history is folded in on top so requests raised on another
 * device also show up; a request the device already knows about wins, since only
 * the local row is guaranteed to be present the instant it was made.
 *
 * Module titles resolve from the local cache by `module_id`, falling back to the
 * CHW's free-text topic for new-module suggestions.
 */
class TrainingRequestsViewModel(
    private val chwId: String,
    private val coachingEventDao: CoachingEventDao,
    private val requestedModuleDao: RequestedModuleDao,
    private val moduleDao: ModuleDao,
) : ViewModel() {

    val uiState: StateFlow<TrainingRequestsUiState> =
        combine(
            coachingEventDao.observeModuleRequested(chwId),
            requestedModuleDao.observeForUser(chwId),
            moduleDao.observePickerRowsLatestPerFamily(),
        ) { events, serverRequests, pickerRows ->
            val titleByModuleId = pickerRows.associate { it.moduleId to LocalizedText.decode(it.titleJson) }

            fun titleFor(moduleId: String?, suggestedName: String?): String =
                moduleId?.let { titleByModuleId[it]?.forSdkLanguage() }?.takeIf { it.isNotBlank() }
                    ?: suggestedName
                    ?: moduleId
                    ?: "—"

            val local = events.map { event ->
                val suggestedName = parseRequestedModuleName(event.payloadJson)
                DatedRequest(
                    key = requestKey(event.moduleId, suggestedName),
                    submittedAt = event.timestampLocal,
                    row = TrainingRequestRow(
                        requestId = event.eventId,
                        moduleTitle = titleFor(event.moduleId, suggestedName),
                        reason = parseRequestedReason(event.payloadJson),
                        submittedDateLabel = friendlyDateLabel(event.timestampLocal),
                    ),
                )
            }
            val localKeys = local.map { it.key }.toSet()

            val fromServer = serverRequests
                .filter { requestKey(it.moduleId, it.requestedModuleName) !in localKeys }
                .map { req ->
                    val submittedAt = parseIsoMillis(req.submittedAt) ?: 0L
                    DatedRequest(
                        key = requestKey(req.moduleId, req.requestedModuleName),
                        submittedAt = submittedAt,
                        row = TrainingRequestRow(
                            requestId = req.requestId,
                            moduleTitle = titleFor(req.moduleId, req.requestedModuleName),
                            reason = req.reason,
                            submittedDateLabel = friendlyDateLabel(submittedAt),
                        ),
                    )
                }

            val rows = (local + fromServer)
                .distinctBy { it.key }
                .sortedByDescending { it.submittedAt }
                .map { it.row }
            TrainingRequestsUiState.Ready(rows) as TrainingRequestsUiState
        }
            .catch { emit(TrainingRequestsUiState.Error(R.string.training_request_load_error)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = TrainingRequestsUiState.Loading,
            )

    /** A row plus the fields needed to dedupe and order it before display. */
    private data class DatedRequest(
        val key: String,
        val submittedAt: Long,
        val row: TrainingRequestRow,
    )

    companion object {
        /**
         * Identity of a request across the two sources, which assign unrelated ids
         * to the same submission: the module when one was picked, else the
         * case-folded free-text topic.
         */
        internal fun requestKey(moduleId: String?, requestedName: String?): String =
            moduleId?.takeIf { it.isNotBlank() }?.let { "module:$it" }
                ?: "name:${requestedName?.trim()?.lowercase().orEmpty()}"

        fun factory(chwId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val sdk = MicroCoachingSDK.getInstance()
                return TrainingRequestsViewModel(
                    chwId = chwId,
                    coachingEventDao = sdk.database.coachingEventDao(),
                    requestedModuleDao = sdk.database.requestedModuleDao(),
                    moduleDao = sdk.database.moduleDao(),
                ) as T
            }
        }
    }
}
