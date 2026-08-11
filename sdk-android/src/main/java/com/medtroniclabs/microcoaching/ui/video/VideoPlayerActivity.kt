package com.medtroniclabs.microcoaching.ui.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.data.asset.AssetKind
import com.medtroniclabs.microcoaching.data.asset.InsufficientStorageException
import com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder
import com.medtroniclabs.microcoaching.network.MediaUrlResolver
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fullscreen Media3/ExoPlayer playback.
 *
 * Two launch modes:
 *  - **Rich-card** ([start]): a `src`/`object_name` media node, streamed. No
 *    resume, progress, or download — the original behaviour, unchanged.
 *  - **Training video** ([startTraining]): an assigned training video keyed by
 *    its `source_document_id`. Resumes from [EXTRA_RESUME_POSITION_MS], reports
 *    watch progress via [VideoProgressReporter], prefers a locally-downloaded
 *    (pinned) file over streaming, and offers a download / remove-download action.
 */
class VideoPlayerActivity : ComponentActivity() {

    internal sealed interface PlaybackState {
        data object Loading : PlaybackState
        data class Ready(val url: String) : PlaybackState
        data object Error : PlaybackState
    }

    /** Download affordance state for the training-video mode. */
    internal sealed interface DownloadUiState {
        /** Not a training video / download not applicable — hide the action. */
        data object Hidden : DownloadUiState
        data object NotDownloaded : DownloadUiState
        data class Downloading(val percent: Int?) : DownloadUiState
        data object Downloaded : DownloadUiState
    }

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Loading)
    private val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _download = MutableStateFlow<DownloadUiState>(DownloadUiState.Hidden)
    private val download: StateFlow<DownloadUiState> = _download.asStateFlow()

    /** Non-null in training mode — turns player callbacks into progress + telemetry. */
    private var reporter: VideoProgressReporter? = null

    /** The assigned video's `source_document_id` (== AssetCache key), training mode only. */
    private var videoId: String? = null
    private var resumePositionMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = intent.getStringExtra(EXTRA_SRC)
        val objectName = intent.getStringExtra(EXTRA_OBJECT_NAME)
        val videoIdExtra = intent.getStringExtra(EXTRA_VIDEO_ID)?.takeIf { it.isNotBlank() }
        val title = intent.getStringExtra(EXTRA_TITLE)
        resumePositionMs = intent.getLongExtra(EXTRA_RESUME_POSITION_MS, 0L).coerceAtLeast(0L)
        videoId = videoIdExtra

        if (src.isNullOrBlank() && objectName.isNullOrBlank() && videoIdExtra == null) {
            finish()
            return
        }

        val headerTitle = title?.takeIf { it.isNotBlank() }

        setContent {
            SdkLocalizedTheme {
                val current by state.collectAsState()
                val downloadState by download.collectAsState()
                VideoPlayerScreen(
                    state = current,
                    downloadState = downloadState,
                    title = headerTitle,
                    startPositionMs = resumePositionMs,
                    onBack = ::finish,
                    onToggleDownload = ::onToggleDownload,
                    onProgress = { pos, dur -> reporter?.onCheckpoint(pos, dur) },
                    onFlush = { pos, dur -> reporter?.onFlush(pos, dur) },
                    onEnded = { dur -> reporter?.onCompleted(dur) },
                )
            }
        }

        if (videoIdExtra != null) initTrainingMode(videoIdExtra) else initRichCardMode(src, objectName)
    }

    /** Rich-card node: stream directly from the resolved presigned URL. */
    private fun initRichCardMode(src: String?, objectName: String?) {
        _download.value = DownloadUiState.Hidden
        lifecycleScope.launch {
            val url = MediaUrlResolver.resolve(src, objectName)
            _state.value = if (url.isNullOrBlank()) PlaybackState.Error else PlaybackState.Ready(url)
        }
    }

    /**
     * Training video: build the progress reporter, prefer a downloaded local
     * file over streaming, and reflect the current download state in the top bar.
     */
    private fun initTrainingMode(videoId: String) {
        val sdk = MicroCoachingSDK.getInstance()
        val chwId = sdk.currentCHWId
        if (!chwId.isNullOrBlank()) {
            reporter = VideoProgressReporter(
                videoId = videoId,
                chwId = chwId,
                dao = sdk.database.assignedVideoDao(),
                recorder = EventRecorder(sdk.database.coachingEventDao(), sessionId = "video-player", chwId = chwId),
                scope = lifecycleScope,
            )
        }
        lifecycleScope.launch {
            val local = withContext(Dispatchers.IO) {
                runCatching { sdk.assetCache.localCachedFile(videoId) }.getOrNull()
            }
            _download.value = if (local != null && withContext(Dispatchers.IO) {
                    runCatching { sdk.assetCache.isPinned(videoId) }.getOrDefault(false)
                }
            ) {
                DownloadUiState.Downloaded
            } else {
                DownloadUiState.NotDownloaded
            }
            val url = local?.let { Uri.fromFile(it).toString() }
                ?: MediaUrlResolver.resolveSourceDocument(videoId)
            _state.value = if (url.isNullOrBlank()) PlaybackState.Error else PlaybackState.Ready(url)
        }
    }

    /** Download-to-keep / remove-download for the current training video. */
    private fun onToggleDownload() {
        val id = videoId ?: return
        val sdk = MicroCoachingSDK.getInstance()
        when (download.value) {
            DownloadUiState.Downloaded -> lifecycleScope.launch {
                withContext(Dispatchers.IO) { runCatching { sdk.assetCache.remove(id) } }
                _download.value = DownloadUiState.NotDownloaded
            }
            DownloadUiState.NotDownloaded -> lifecycleScope.launch {
                _download.value = DownloadUiState.Downloading(null)
                val file = try {
                    withContext(Dispatchers.IO) {
                        sdk.assetCache.download(
                            key = id,
                            kind = AssetKind.VIDEO,
                            onProgress = { downloaded, total ->
                                val pct = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null
                                _download.value = DownloadUiState.Downloading(pct)
                            },
                        ) { MediaUrlResolver.resolveSourceDocument(id) }
                    }
                } catch (e: InsufficientStorageException) {
                    _download.value = DownloadUiState.NotDownloaded
                    Toast.makeText(this@VideoPlayerActivity, R.string.training_videos_storage_full, Toast.LENGTH_LONG).show()
                    return@launch
                } catch (e: Exception) {
                    null
                }
                _download.value = if (file != null) DownloadUiState.Downloaded else DownloadUiState.NotDownloaded
                if (file == null) {
                    Toast.makeText(this@VideoPlayerActivity, R.string.training_videos_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
            else -> Unit // Downloading / Hidden — ignore taps.
        }
    }

    companion object {
        private const val EXTRA_SRC = "extra_video_src"
        private const val EXTRA_OBJECT_NAME = "extra_video_object_name"
        private const val EXTRA_VIDEO_ID = "extra_video_id"
        private const val EXTRA_TITLE = "extra_video_title"
        private const val EXTRA_RESUME_POSITION_MS = "extra_video_resume_position_ms"

        /** Launch fullscreen streaming playback for a rich-card node ([src] or [objectName]). */
        fun start(context: Context, src: String?, objectName: String?) {
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_SRC, src)
                putExtra(EXTRA_OBJECT_NAME, objectName)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /**
         * Launch fullscreen playback for an assigned training video, resuming
         * from [resumePositionMs]. [videoId] is the canonical `source_document_id`
         * used to resolve the stream, persist progress, and key an offline download.
         */
        fun startTraining(context: Context, videoId: String, title: String?, resumePositionMs: Long) {
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_ID, videoId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_RESUME_POSITION_MS, resumePositionMs)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
private fun VideoPlayerScreen(
    state: VideoPlayerActivity.PlaybackState,
    downloadState: VideoPlayerActivity.DownloadUiState,
    title: String?,
    startPositionMs: Long,
    onBack: () -> Unit,
    onToggleDownload: () -> Unit,
    onProgress: (Long, Long) -> Unit,
    onFlush: (Long, Long) -> Unit,
    onEnded: (Long) -> Unit,
) {
    Scaffold(
        topBar = {
            SdkScreenHeader(
                title = title ?: stringResource(R.string.rich_video_title),
                onBack = onBack,
                trailing = if (downloadState is VideoPlayerActivity.DownloadUiState.Hidden) {
                    null
                } else {
                    { DownloadAction(downloadState, onToggleDownload, Modifier.align(Alignment.CenterEnd)) }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is VideoPlayerActivity.PlaybackState.Ready -> ExoPlayerSurface(
                    url = state.url,
                    startPositionMs = startPositionMs,
                    onProgress = onProgress,
                    onFlush = onFlush,
                    onEnded = onEnded,
                )
                VideoPlayerActivity.PlaybackState.Error -> Text(
                    text = stringResource(R.string.rich_media_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )
                VideoPlayerActivity.PlaybackState.Loading -> CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun DownloadAction(
    state: VideoPlayerActivity.DownloadUiState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is VideoPlayerActivity.DownloadUiState.Downloading -> Box(
            modifier = modifier.padding(end = 8.dp).size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (state.percent != null) {
                CircularProgressIndicator(
                    progress = { state.percent / 100f },
                    color = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        else -> IconButton(onClick = onToggle, modifier = modifier.padding(end = 8.dp)) {
            val (icon, desc) = when (state) {
                VideoPlayerActivity.DownloadUiState.Downloaded ->
                    Icons.Filled.DownloadDone to R.string.training_videos_remove_download
                else ->
                    Icons.Filled.Download to R.string.training_videos_download
            }
            Icon(imageVector = icon, contentDescription = stringResource(desc), tint = Color.White)
        }
    }
}
