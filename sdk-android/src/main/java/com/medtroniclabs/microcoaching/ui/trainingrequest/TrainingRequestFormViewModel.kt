package com.medtroniclabs.microcoaching.ui.trainingrequest

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.data.db.dao.AssignedModuleDao
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.dao.RequestedModuleDao
import com.medtroniclabs.microcoaching.data.db.dao.ModuleDao
import com.medtroniclabs.microcoaching.data.localized.LocalizedText
import com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder
import com.medtroniclabs.microcoaching.util.stripEmoji
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrainingRequestFormUiState(
    val modules: List<ModulePickerItem> = emptyList(),
    val selectedModule: ModulePickerItem? = null,
    /**
     * Non-null = "suggest a new module" mode: the CHW types a topic that
     * doesn't exist in the catalogue instead of picking one. Mutually
     * exclusive with [selectedModule]; null = normal picker mode.
     */
    val customModuleTitle: String? = null,
    val reason: String = "",
    val submitting: Boolean = false,
    @StringRes val errorRes: Int? = null,
) {
    val canSubmit: Boolean
        get() = !submitting &&
            (selectedModule != null || !customModuleTitle.isNullOrBlank()) &&
            reason.isNotBlank() // reason is mandatory
}

sealed interface TrainingRequestFormEvent {
    /**
     * Submit succeeded. Carries no message: the confirmation copy is owned by
     * the client (the backend's success text has described the module as
     * "available", which is misleading for a request that's only just been
     * raised).
     */
    object Submitted : TrainingRequestFormEvent
}

/**
 * Drives the training-request form. Submitting records a `module_requested`
 * telemetry event locally and kicks an outbound flush; there is no synchronous
 * submit API. The write always succeeds offline — the
 * event ships on the next sync (immediately when online, since
 * [flushOutbound]'s worker carries a network constraint).
 *
 * The picker lists every published family the CHW is NOT already assigned —
 * training they don't yet have — so assigned modules are subtracted (see
 * [excludingAssigned]). Opening the form kicks a full inbound sync (when
 * online) via [refreshCatalogue] so the catalogue is current, and the module
 * list is OBSERVED from Room so retired families drop out in place.
 *
 * A client-side duplicate guard blocks a second request for the same module /
 * topic, checking both the local `module_requested` history and the server's
 * request history so a request raised on another device is caught too.
 */
class TrainingRequestFormViewModel(
    private val chwId: String,
    private val eventRecorder: EventRecorder,
    private val coachingEventDao: CoachingEventDao,
    private val requestedModuleDao: RequestedModuleDao,
    private val moduleDao: ModuleDao,
    private val assignedModuleDao: AssignedModuleDao,
    private val isOnline: () -> Boolean,
    private val refreshCatalogue: () -> Unit = {},
    private val flushOutbound: () -> Unit = {},
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrainingRequestFormUiState())
    val uiState: StateFlow<TrainingRequestFormUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<TrainingRequestFormEvent>()
    val events: SharedFlow<TrainingRequestFormEvent> = _events.asSharedFlow()

    init {
        if (isOnline()) runCatching { refreshCatalogue() }
        observeModules()
    }

    private fun observeModules() {
        viewModelScope.launch {
            combine(
                moduleDao.observePickerRowsLatestPerFamily(),
                assignedModuleDao.getAssignedForUser(chwId),
            ) { rows, assigned ->
                rows
                    .map { row ->
                        ModulePickerItem(
                            moduleId = row.moduleId,
                            moduleFamilyId = row.moduleFamilyId,
                            title = LocalizedText.decode(row.titleJson),
                            domain = row.domain,
                            thumbnailUrl = row.thumbnailUrl,
                        )
                    }
                    // Same display order as the module lists: domain, then Bangla
                    // title with English fallback (see ModuleEntity.sortedForDisplay).
                    .sortedWith(
                        compareBy(
                            { it.domain },
                            { it.title.bn?.takeIf { t -> t.isNotBlank() } ?: it.title.en.orEmpty() },
                            { it.moduleFamilyId },
                        ),
                    )
                    // The picker offers only modules the CHW isn't already assigned.
                    .excludingAssigned(
                        assignedModuleIds = assigned.map { it.moduleId }.toSet(),
                        assignedFamilyIds = assigned.mapNotNull { it.moduleFamilyId }.toSet(),
                    )
            }.collect { pickerItems ->
                _uiState.update { state ->
                    // Re-resolve the selection on every emission: a sync or
                    // assignment change may retire/hide the picked family, so drop
                    // it rather than submit a now-unavailable module.
                    val selected = state.selectedModule?.let { current ->
                        pickerItems.find { it.moduleFamilyId == current.moduleFamilyId }
                    }
                    state.copy(modules = pickerItems, selectedModule = selected)
                }
            }
        }
    }

    fun selectModule(item: ModulePickerItem) {
        _uiState.update { it.copy(selectedModule = item, customModuleTitle = null, errorRes = null) }
    }

    /** Switch to "suggest a new module" mode, prefilled with the picker's search text. */
    fun enterCustomModuleMode(prefill: String = "") {
        _uiState.update {
            it.copy(
                customModuleTitle = prefill.take(TRAINING_REQUEST_CUSTOM_TITLE_MAX_CHARS),
                selectedModule = null,
                errorRes = null,
            )
        }
    }

    /** Back to picker mode; discards the typed topic. */
    fun exitCustomModuleMode() {
        _uiState.update { it.copy(customModuleTitle = null, errorRes = null) }
    }

    fun updateCustomModuleTitle(text: String) {
        if (_uiState.value.customModuleTitle == null) return
        _uiState.update {
            it.copy(customModuleTitle = stripEmoji(text).take(TRAINING_REQUEST_CUSTOM_TITLE_MAX_CHARS))
        }
    }

    fun updateReason(text: String) {
        _uiState.update { it.copy(reason = stripEmoji(text).take(TRAINING_REQUEST_REASON_MAX_CHARS)) }
    }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return
        _uiState.update { it.copy(submitting = true, errorRes = null) }
        viewModelScope.launch {
            try {
                val selected = state.selectedModule
                val customName = if (selected == null) state.customModuleTitle?.trim().orEmpty() else ""

                // Client-side duplicate guard — a CHW shouldn't raise two requests
                // for the same module/topic. Checks the local history and the
                // server's, so one raised on another device is caught too.
                val existing = runCatching { coachingEventDao.getModuleRequested(chwId) }
                    .getOrDefault(emptyList())
                val existingOnServer = runCatching { requestedModuleDao.getForUser(chwId) }
                    .getOrDefault(emptyList())
                val isDuplicate = when {
                    selected != null ->
                        existing.any { it.moduleId == selected.moduleId } ||
                            existingOnServer.any { it.moduleId == selected.moduleId }
                    customName.isNotEmpty() ->
                        existing.any {
                            parseRequestedModuleName(it.payloadJson)?.equals(customName, ignoreCase = true) == true
                        } ||
                            existingOnServer.any {
                                it.requestedModuleName?.equals(customName, ignoreCase = true) == true
                            }
                    else -> false
                }
                if (isDuplicate) {
                    _uiState.update {
                        it.copy(submitting = false, errorRes = R.string.training_request_error_duplicate)
                    }
                    return@launch
                }

                // Record locally (survives offline) and kick an outbound flush;
                // the worker's network constraint sends it now if online, else
                // holds until connectivity returns.
                eventRecorder.recordModuleRequested(
                    moduleId = selected?.moduleId,
                    moduleFamilyId = selected?.moduleFamilyId,
                    requestedModuleName = customName.ifEmpty { null },
                    reason = normalizeReason(state.reason),
                )
                flushOutbound()
                _uiState.update { it.copy(submitting = false) }
                _events.emit(TrainingRequestFormEvent.Submitted)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(submitting = false, errorRes = R.string.training_request_error_generic)
                }
            }
        }
    }

    companion object {
        fun factory(chwId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val sdk = MicroCoachingSDK.getInstance()
                    return TrainingRequestFormViewModel(
                        chwId = chwId,
                        eventRecorder = EventRecorder(
                            dao = sdk.database.coachingEventDao(),
                            sessionId = sdk.coachingSessionId,
                            chwId = chwId,
                        ),
                        coachingEventDao = sdk.database.coachingEventDao(),
                        requestedModuleDao = sdk.database.requestedModuleDao(),
                        moduleDao = sdk.database.moduleDao(),
                        assignedModuleDao = sdk.database.assignedModuleDao(),
                        isOnline = { sdk.isNetworkAvailable() },
                        // Full-catalogue pull (same as pull-to-refresh) so the
                        // picker reflects the currently-published catalogue.
                        refreshCatalogue = { sdk.triggerFullInboundSync() },
                        // Network-constrained flush: sends the just-recorded
                        // request now if online, else on reconnect.
                        flushOutbound = { sdk.syncCoordinator.triggerOutboundNow() },
                    ) as T
                }
            }
    }
}
