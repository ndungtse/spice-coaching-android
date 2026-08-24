package com.medtroniclabs.microcoaching.ui.trainingvideos

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.asset.AssetCache
import com.medtroniclabs.microcoaching.data.asset.AssetKind
import com.medtroniclabs.microcoaching.data.asset.InsufficientStorageException
import com.medtroniclabs.microcoaching.data.db.dao.AssignedVideoDao
import com.medtroniclabs.microcoaching.data.db.entity.AssignedVideoEntity
import com.medtroniclabs.microcoaching.network.MediaUrlResolver
import com.medtroniclabs.microcoaching.network.SourceDocumentUrlStore
import com.medtroniclabs.microcoaching.sync.SyncDomain
import com.medtroniclabs.microcoaching.ui.common.SectionState
import com.medtroniclabs.microcoaching.ui.common.sectionStateFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the Training sub-tab. Reactively reads the CHW's assigned videos from
 * [AssignedVideoDao] (the durable mirror of `GET /sync/assigned-videos`) so both
 * pull-to-refresh (which re-runs inbound sync) and live watch-progress writes
 * re-render the list. Layers per-video offline-download state on top and owns the
 * download / remove-download action.
 */
class TrainingVideosViewModel(
    private val chwId: String,
    private val dao: AssignedVideoDao,
    private val assetCache: AssetCache,
) : ViewModel() {

    private val sdk = MicroCoachingSDK.getInstance()

    /**
     * Measure any assigned video whose length the backend hasn't supplied.
     *
     * Driven by the list contents rather than by rows appearing: every row composes
     * at once inside the tab's scroll container, so keying off appearance made this
     * look scroll-dependent while really just racing the sync that supplies the
     * media URL. Re-running whenever the list changes also picks up videos whose URL
     * only arrived on a later sync. A no-op once every length is known.
     */
    fun onVideosShown() {
        viewModelScope.launch { VideoDurationProbe.probeMissing(chwId) }
    }

    /** Video ids currently downloading → percent (null while size unknown). */
    private val downloading = MutableStateFlow<Map<String, Int?>>(emptyMap())

    /** Video ids pinned in the asset cache — the "downloaded / view offline" set. */
    private val downloadedIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Videos plus the outcome of the pull that fills their table, so an empty list can say
     * which it is: "the refresh failed" or "nothing is assigned to you". Cached rows always
     * win — a failed refresh marks them stale rather than replacing them with an error.
     */
    val state: StateFlow<SectionState<List<TrainingVideo>>> =
        combine(
            dao.getForUser(chwId),
            downloading,
            downloadedIds,
            sdk.syncStatus.outcomeFor(SyncDomain.ASSIGNED_VIDEOS),
            // network + in-flight folded into one flow (combine's typed arity caps at 5).
            combine(sdk.networkAvailable, sdk.syncStatus.isSyncing) { online, syncing -> online to syncing },
        ) { entities, downloadingMap, downloaded, outcome, (online, syncing) ->
            sectionStateFor(
                rows = entities.map { it.toUiModel(downloadingMap, downloaded) },
                outcome = outcome,
                offline = !online,
                syncing = syncing,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectionState.Loading)

    /** Re-run inbound sync from the sub-tab's error state. */
    fun retry() {
        runCatching {
            if (sdk.config.backendUrl.isNotBlank()) sdk.syncCoordinator.triggerNow()
        }
    }

    init {
        // Keep the pinned-download set in sync with the catalogue (recomputed only
        // when the list itself changes, not on every download-progress tick).
        viewModelScope.launch {
            dao.getForUser(chwId).collect { entities ->
                val pinned = entities.map { it.videoId }
                    .filter { runCatching { assetCache.isPinned(it) }.getOrDefault(false) }
                    .toSet()
                downloadedIds.value = pinned
            }
        }
        // First open after install: the assigned list may be empty until the next
        // inbound sync. Nudge one now (online only) so the tab populates promptly.
        viewModelScope.launch {
            runCatching {
                val sdk = MicroCoachingSDK.getInstance()
                if (dao.countForUser(chwId) == 0 && sdk.config.backendUrl.isNotBlank() && sdk.isNetworkAvailable()) {
                    sdk.syncCoordinator.triggerNow()
                }
            }
        }
    }

    /**
     * Download-to-keep / remove-download for [video]. [onStorageFull] and
     * [onError] let the host surface a message (a snackbar / toast).
     */
    fun onDownloadToggle(video: TrainingVideo, onStorageFull: () -> Unit, onError: () -> Unit) {
        val id = video.id
        when (video.download) {
            is VideoDownloadState.Downloaded -> viewModelScope.launch {
                withContext(Dispatchers.IO) { runCatching { assetCache.remove(id) } }
                downloadedIds.update { it - id }
            }
            is VideoDownloadState.Downloading -> Unit // in flight — ignore taps
            is VideoDownloadState.NotDownloaded -> viewModelScope.launch {
                downloading.update { it + (id to null) }
                val file = try {
                    withContext(Dispatchers.IO) {
                        assetCache.download(
                            key = id,
                            kind = AssetKind.VIDEO,
                            onProgress = { downloaded, total ->
                                val pct = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null
                                downloading.update { it + (id to pct) }
                            },
                            renewUrl = { SourceDocumentUrlStore.renew(id) },
                        ) { MediaUrlResolver.resolveSourceDocument(id) }
                    }
                } catch (e: InsufficientStorageException) {
                    downloading.update { it - id }
                    onStorageFull()
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "video download failed id=$id: ${e.message}")
                    null
                }
                downloading.update { it - id }
                if (file != null) downloadedIds.update { it + id } else onError()
            }
        }
    }

    private fun AssignedVideoEntity.toUiModel(
        downloadingMap: Map<String, Int?>,
        downloaded: Set<String>,
    ): TrainingVideo {
        val fraction = (percentWatched / 100.0).toFloat().coerceIn(0f, 1f)
        val download = when {
            videoId in downloadingMap -> VideoDownloadState.Downloading(downloadingMap[videoId])
            videoId in downloaded -> VideoDownloadState.Downloaded
            else -> VideoDownloadState.NotDownloaded
        }
        return TrainingVideo(
            id = videoId,
            title = title.orEmpty(),
            description = description,
            category = null,
            durationMs = durationMs,
            thumbnailUrl = thumbnailUrl,
            progressFraction = if (completed) 1f else fraction,
            completed = completed,
            lastPositionMs = lastPositionMs,
            download = download,
        )
    }

    companion object {
        private const val TAG = "TrainingVideosVM"

        fun factory(chwId: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val sdk = MicroCoachingSDK.getInstance()
                return TrainingVideosViewModel(
                    chwId = chwId,
                    dao = sdk.database.assignedVideoDao(),
                    assetCache = sdk.assetCache,
                ) as T
            }
        }
    }
}
