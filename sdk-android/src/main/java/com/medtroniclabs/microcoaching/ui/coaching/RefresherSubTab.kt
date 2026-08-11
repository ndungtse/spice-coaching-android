package com.medtroniclabs.microcoaching.ui.coaching

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.CardRowSkeleton
import com.medtroniclabs.microcoaching.sync.SyncDomain
import com.medtroniclabs.microcoaching.sync.SyncOutcome
import com.medtroniclabs.microcoaching.ui.common.CoachingError
import com.medtroniclabs.microcoaching.ui.common.ErrorState
import com.medtroniclabs.microcoaching.ui.common.EmptyState
import com.medtroniclabs.microcoaching.ui.common.TopLoadingBar
import com.medtroniclabs.microcoaching.ui.common.TrainingCardSkeleton
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.modules.components.PracticeZoneCard
import com.medtroniclabs.microcoaching.ui.learn.modules.components.PracticeZonePalette
import com.medtroniclabs.microcoaching.ui.learn.modules.components.RefresherHeroCard
import com.medtroniclabs.microcoaching.ui.learn.modules.components.SectionHeader
import com.medtroniclabs.microcoaching.ui.learn.modules.components.TrainingGrid
import com.medtroniclabs.microcoaching.ui.learn.modules.components.TrainingRequestEntryCard
import com.medtroniclabs.microcoaching.ui.learn.modules.components.TrainingRow

/**
 * Refresher sub-tab of the Coaching tab, shown for both SK and PO: a hero card for the newest
 * assigned module, the Practice Zone (all active refreshers), the Learning Library (assigned
 * training modules), and — SK only — the training-request entry.
 *
 * Refreshers and training modules come straight from
 * [com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore] (same as
 * [com.medtroniclabs.microcoaching.ui.learn.modules.RefreshersScreen]). Tapping a Practice
 * Zone tile runs [onRefresherStart] then [onShowRefresherQuiz], queued with the full active
 * pool so the refresher bottom sheet's "Next refresher" chain is unchanged.
 *
 * @param libraryAsGrid PO renders the Learning Library as a 4-up grid; SK as a scrolling row.
 * @param onOpenTrainingRequests SK-only entry point; null (the default) omits the row.
 */
@Composable
fun RefresherSubTab(
    isLoading: Boolean,
    onModuleSelected: (LearnModule) -> Unit,
    onRefresherStart: (LearnModule) -> Unit,
    onShowRefresherQuiz: (queueFamilyIds: List<String>) -> Unit,
    onSeeAllRefreshers: () -> Unit,
    onSeeAllTraining: () -> Unit,
    modifier: Modifier = Modifier,
    onRetrySync: () -> Unit = {},
    libraryAsGrid: Boolean = false,
    onOpenTrainingRequests: (() -> Unit)? = null,
) {
    val sdk = MicroCoachingSDK.getInstance()
    val store = sdk.coachingModuleStore
    val refreshers by store.refresherModules.collectAsState()
    val training by store.trainingModules.collectAsState()
    val trainingLoaded by store.trainingAssignmentsLoaded.collectAsState()
    val poolFamilyIds = refreshers.map { it.moduleFamilyId }

    // Both sections below are module-catalogue derived, so they share the MODULES sync
    // verdict: a catalogue failure marks each of them individually, rather than blanking
    // the tab. Cached modules still render (flagged stale) — the mapper prefers rows.
    val modulesOutcome by sdk.syncStatus.outcomeFor(SyncDomain.MODULES)
        .collectAsState(initial = SyncOutcome.Unknown)
    val online by sdk.networkAvailable.collectAsState()
    val catalogueError = (modulesOutcome as? SyncOutcome.Failed)
        ?.let { CoachingError.from(it.kind, offline = !online) }

    // Keep the host tile's skipped-refresher badge to still-active refreshers (was ModulesScreen).
    LaunchedEffect(poolFamilyIds) {
        sdk.retainActiveSkippedRefreshers(poolFamilyIds.toSet())
    }

    Column(
        // Must stay scrollable in every state: CoachingTab wraps this in a PullToRefreshBox,
        // and the pull gesture only arms over a scrollable child.
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        TopLoadingBar(visible = isLoading)

        // Hero: newest assigned module (store sorts `training` newest-first).
        val newest = training.firstOrNull()
        if ((isLoading || !trainingLoaded) && training.isEmpty()) {
            TrainingCardSkeleton(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        } else if (newest != null) {
            RefresherHeroCard(
                module = newest,
                onStart = { onModuleSelected(newest) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        // Practice Zone — every active refresher gets a tile (not skipped-only).
        val hasRefreshers = refreshers.isNotEmpty()
        SectionHeader(
            title = stringResource(R.string.coaching_section_practice_zone),
            seeAllLabel = if (hasRefreshers) stringResource(R.string.modules_see_all) else null,
            onSeeAllClick = if (hasRefreshers) onSeeAllRefreshers else null,
        )
        if (isLoading && refreshers.isEmpty()) {
            CardRowSkeleton()
        } else if (refreshers.isEmpty() && catalogueError != null) {
            ErrorState(error = catalogueError, onRetry = onRetrySync)
        } else if (refreshers.isEmpty()) {
            EmptyState(stringResource(R.string.refresher_empty_none))
        } else {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                refreshers.forEachIndexed { index, module ->
                    PracticeZoneCard(
                        module = module,
                        onClick = {
                            onRefresherStart(module)
                            onShowRefresherQuiz(poolFamilyIds)
                        },
                        containerColor = PracticeZonePalette[index % PracticeZonePalette.size],
                    )
                }
            }
        }

        // Learning Library — assigned training modules; NEW badge rides the newest one.
        val libraryTitle = stringResource(R.string.coaching_section_learning_library)
        if ((isLoading || !trainingLoaded) && training.isEmpty()) {
            SectionHeader(title = libraryTitle, seeAllLabel = null, onSeeAllClick = null)
            CardRowSkeleton()
        } else if (training.isEmpty() && catalogueError != null) {
            SectionHeader(title = libraryTitle, seeAllLabel = null, onSeeAllClick = null)
            ErrorState(error = catalogueError, onRetry = onRetrySync)
        } else if (training.isEmpty()) {
            SectionHeader(title = libraryTitle, seeAllLabel = null, onSeeAllClick = null)
            EmptyState(stringResource(R.string.training_empty_no_assigned))
        } else if (libraryAsGrid) {
            TrainingGrid(
                modules = training,
                onSelect = onModuleSelected,
                onSeeAll = onSeeAllTraining,
                maxItems = 4,
                title = libraryTitle,
                newModuleFamilyId = newest?.moduleFamilyId,
            )
        } else {
            TrainingRow(
                modules = training,
                onSelect = onModuleSelected,
                onSeeAll = onSeeAllTraining,
                title = libraryTitle,
                newModuleFamilyId = newest?.moduleFamilyId,
            )
        }

        if (onOpenTrainingRequests != null) {
            TrainingRequestEntryCard(onClick = onOpenTrainingRequests)
        }

        Spacer(Modifier.height(80.dp)) // Chat-FAB clearance
    }
}
