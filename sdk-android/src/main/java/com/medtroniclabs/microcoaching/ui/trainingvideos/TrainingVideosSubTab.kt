package com.medtroniclabs.microcoaching.ui.trainingvideos

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.CardRowSkeleton
import com.medtroniclabs.microcoaching.ui.common.EmptyState
import com.medtroniclabs.microcoaching.ui.common.SectionContent
import com.medtroniclabs.microcoaching.ui.learn.modules.components.SectionHeader
import com.medtroniclabs.microcoaching.ui.trainingvideos.components.FeaturedVideoCard
import com.medtroniclabs.microcoaching.ui.trainingvideos.components.TrainingVideoRow
import com.medtroniclabs.microcoaching.ui.video.VideoPlayerActivity

/**
 * Training sub-tab body: a featured (newest) video hero card, then a "More videos" section
 * for the rest of the assigned catalogue. Backed by [TrainingVideosViewModel] reading the
 * `assigned_video` table; tapping a card opens [VideoPlayerActivity] (resuming from saved
 * progress), and each card exposes a download / remove-download action.
 *
 * An empty list now distinguishes "the videos pull failed" from "nothing is assigned":
 * [TrainingVideosViewModel.state] folds the `ASSIGNED_VIDEOS` sync outcome into the section
 * state, so a failure shows a retry instead of a bare "No training videos yet".
 */
@Composable
fun TrainingVideosSubTab(
    chwId: String,
    modifier: Modifier = Modifier,
) {
    val vm: TrainingVideosViewModel = viewModel(factory = TrainingVideosViewModel.factory(chwId))
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val onOpen: (TrainingVideo) -> Unit = { video ->
        val resumeMs = if (video.completed) 0L else video.lastPositionMs
        VideoPlayerActivity.startTraining(context, video.id, video.title, resumeMs)
    }
    val onDownloadToggle: (TrainingVideo) -> Unit = { video ->
        vm.onDownloadToggle(
            video = video,
            onStorageFull = {
                Toast.makeText(context, R.string.training_videos_storage_full, Toast.LENGTH_LONG).show()
            },
            onError = {
                Toast.makeText(context, R.string.training_videos_download_failed, Toast.LENGTH_SHORT).show()
            },
        )
    }

    // The scroll container is hoisted OUT of the state branches: CoachingTab wraps this
    // sub-tab in a PullToRefreshBox, and the pull gesture only arms over a scrollable child,
    // so every state — loading, failed, empty — has to stay scrollable.
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionContent(
            state = state,
            onRetry = vm::retry,
            loading = { CardRowSkeleton() },
        ) { videos ->
            if (videos.isEmpty()) {
                EmptyState(stringResource(R.string.training_videos_empty))
            } else {
                // Keyed on the ids so a refresh that brings new videos re-sweeps,
                // while a progress tick that only changes a percentage does not.
                LaunchedEffect(videos.map { it.id }) { vm.onVideosShown() }
                videos.firstOrNull()?.let { featured ->
                    FeaturedVideoCard(
                        video = featured,
                        onClick = { onOpen(featured) },
                        onDownloadToggle = { onDownloadToggle(featured) },
                        modifier = Modifier.padding(16.dp),
                    )
                }
                SectionHeader(
                    title = stringResource(R.string.training_videos_more),
                    seeAllLabel = null,
                    onSeeAllClick = null,
                )
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val rest = videos.drop(1)
                    if (rest.isEmpty()) {
                        // The only assigned video is the featured one above, so this
                        // section would otherwise be a heading over blank space.
                        EmptyState(stringResource(R.string.training_videos_no_more))
                    } else {
                        rest.forEach { video ->
                            TrainingVideoRow(video = video, onClick = { onOpen(video) })
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp)) // chat-FAB clearance
    }
}
