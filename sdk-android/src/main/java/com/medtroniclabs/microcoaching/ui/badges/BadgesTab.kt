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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.medtroniclabs.microcoaching.ui.common.ErrorState
import com.medtroniclabs.microcoaching.ui.badges.components.BadgeMedallion
import com.medtroniclabs.microcoaching.ui.badges.components.YourJourneyBanner
import com.medtroniclabs.microcoaching.ui.learn.modules.components.ModuleGrid
import com.medtroniclabs.microcoaching.ui.theme.MutedText
import com.medtroniclabs.microcoaching.ui.theme.SpiceNavy
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

/**
 * Badges tab body — replaces the Leaderboard top-tab on the SK home, hosted by
 * [com.medtroniclabs.microcoaching.ui.coaching.SKCoachingScreen]. Stub-backed
 * ([BadgesViewModel] / [StubBadgesDataSource]) until the real badges/achievements
 * backend exists.
 *
 * Shows the badge grid by default; tapping the Your Journey banner swaps in
 * [YourJourneyScreen] in place (a `rememberSaveable` toggle rather than a nav route, so the
 * one-home-route invariant and the overlaying chat FAB are untouched), with system-back
 * returning to the grid.
 *
 * Like [com.medtroniclabs.microcoaching.ui.podashboard.PODashboardTab] /
 * [com.medtroniclabs.microcoaching.ui.trainingvideos.TrainingVideosSubTab], this tab creates
 * its own view model internally so the call site only passes [chwId]. Each state stays
 * independently scrollable for small screens (pull-to-refresh doesn't wrap this sibling of
 * `CoachingTab`).
 */
@Composable
fun BadgesTab(
    chwId: String,
    modifier: Modifier = Modifier,
) {
    val vm: BadgesViewModel = viewModel(factory = BadgesViewModel.factory(chwId))
    val state by vm.uiState.collectAsState()
    var showJourney by rememberSaveable { mutableStateOf(false) }

    when (val s = state) {
        is BadgesUiState.Loading -> CenterProgress(modifier)

        is BadgesUiState.Error -> ErrorState(message = s.message, modifier = modifier)

        is BadgesUiState.Ready -> {
            val snapshot = s.snapshot
            if (showJourney) {
                BackHandler { showJourney = false }
                YourJourneyScreen(
                    snapshot = snapshot,
                    onBack = { showJourney = false },
                    modifier = modifier,
                )
            } else {
                BadgesGrid(
                    snapshot = snapshot,
                    onOpenJourney = { showJourney = true },
                    modifier = modifier,
                )
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
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
