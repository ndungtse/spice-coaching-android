package com.medtroniclabs.microcoaching.ui.badges

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.CenterProgress
import com.medtroniclabs.microcoaching.ui.common.NoticeBanner
import com.medtroniclabs.microcoaching.ui.common.SectionContent
import com.medtroniclabs.microcoaching.ui.common.rememberManualInboundSyncState
import com.medtroniclabs.microcoaching.ui.badges.components.BadgeMedallion
import com.medtroniclabs.microcoaching.ui.badges.components.YourJourneyBanner
import com.medtroniclabs.microcoaching.ui.learn.modules.components.ModuleGrid
import com.medtroniclabs.microcoaching.ui.theme.MutedText
import com.medtroniclabs.microcoaching.ui.theme.SpiceNavy
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

/** Height reserved for the first-load spinner, which has no content to size against. */
private val LOADING_HEIGHT = 240.dp

/**
 * Badges tab body — replaces the Leaderboard top-tab on the SK home, hosted by
 * [com.medtroniclabs.microcoaching.ui.coaching.SKCoachingScreen]. Backed by the
 * `badge` table that inbound sync fills from `GET /sync/badges`, so the grid renders
 * offline from the last snapshot.
 *
 * Shows the badge grid by default; tapping the Your Journey banner swaps in
 * [YourJourneyScreen] in place (a `rememberSaveable` toggle rather than a nav route, so the
 * one-home-route invariant and the overlaying chat FAB are untouched), with system-back
 * returning to the grid.
 *
 * Like [com.medtroniclabs.microcoaching.ui.podashboard.PODashboardTab] /
 * [com.medtroniclabs.microcoaching.ui.trainingvideos.TrainingVideosSubTab], this tab creates
 * its own view model internally so the call site only passes [chwId].
 *
 * Being a sibling of `CoachingTab` rather than one of its sub-tabs, it isn't covered by that
 * screen's pull-to-refresh and brings its own — the same full inbound sync, so a pull here
 * refreshes badges along with everything else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgesTab(
    chwId: String,
    modifier: Modifier = Modifier,
) {
    val vm: BadgesViewModel = viewModel(factory = BadgesViewModel.factory(chwId))
    val state by vm.state.collectAsState()
    var showJourney by rememberSaveable { mutableStateOf(false) }
    val manualSync = rememberManualInboundSyncState()

    PullToRefreshBox(
        isRefreshing = manualSync.isRefreshing,
        onRefresh = { manualSync.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        if (showJourney) {
            // The journey screen owns its own scroll container, so it is not wrapped in
            // the one below — nesting two vertical scrollers would leave the inner one
            // measuring against unbounded height.
            SectionContent(state = state, onRetry = vm::retry) { snapshot ->
                BackHandler { showJourney = false }
                YourJourneyScreen(
                    snapshot = snapshot,
                    onBack = { showJourney = false },
                )
            }
        } else {
            // Hoisted out of the state branches: the pull gesture only arms over a
            // scrollable child, so loading and error have to scroll too, not just the grid.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // A refresh that only partly landed would otherwise report success
                // silently; say so, the way the Coaching tab does.
                if (manualSync.lastResult?.anyFailure == true) {
                    NoticeBanner(stringResource(R.string.coaching_sync_partial_notice))
                }
                SectionContent(
                    state = state,
                    onRetry = vm::retry,
                    // The default spinner sizes itself with fillMaxSize, which collapses
                    // to nothing under a scroll container's unbounded height — give it a
                    // real one so first load shows a spinner and not a blank tab.
                    loading = { CenterProgress(Modifier.fillMaxWidth().height(LOADING_HEIGHT)) },
                ) { snapshot ->
                    BadgesGrid(
                        snapshot = snapshot,
                        onOpenJourney = { showJourney = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun BadgesGrid(
    snapshot: BadgesSnapshot,
    onOpenJourney: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Header band
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceMuted)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.coaching_tab_badges),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = SpiceNavy,
            )
            Text(
                text = stringResource(
                    R.string.badges_earned_count,
                    snapshot.earnedCount,
                    snapshot.totalCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText,
            )
        }

        Column(modifier = Modifier.padding(16.dp)) {
            YourJourneyBanner(
                earnedCount = snapshot.earnedCount,
                totalCount = snapshot.totalCount,
                onClick = onOpenJourney,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            ModuleGrid(items = snapshot.badges, columns = 3, verticalSpacing = 24.dp) {
                BadgeMedallion(it)
            }
            Spacer(Modifier.height(80.dp)) // chat-FAB clearance
        }
    }
}
