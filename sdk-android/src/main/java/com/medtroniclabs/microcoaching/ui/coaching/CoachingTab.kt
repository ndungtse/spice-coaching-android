package com.medtroniclabs.microcoaching.ui.coaching

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.NoticeBanner
import com.medtroniclabs.microcoaching.ui.common.SectionState
import com.medtroniclabs.microcoaching.ui.common.rememberManualInboundSyncState
import com.medtroniclabs.microcoaching.ui.learn.KnowledgeDocument
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.LearnUiState
import com.medtroniclabs.microcoaching.ui.trainingvideos.TrainingVideosSubTab

/** Sub-tab indices within the Coaching tab. */
private const val SUBTAB_TRAINING = 0
private const val SUBTAB_REFRESHER = 1
private const val SUBTAB_KNOWLEDGE = 2

/**
 * Header-less coaching body shared by [SKCoachingScreen] and [POCoachingScreen].
 * Hosts the Training | Refresher | Knowledge sub-tab chips ([CoachingSubTabChips])
 * above a shared pull-to-refresh area that swaps in the selected sub-tab body.
 * Sub-tabs are rememberSaveable STATE, not nav routes, preserving the
 * one-home-route invariant; the parent owns the header + top-level tabs.
 *
 * @param libraryAsGrid true for PO — Learning Library as a 4-up grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachingTab(
    uiState: LearnUiState,
    chwId: String,
    onModuleSelected: (LearnModule) -> Unit,
    onRetrySync: () -> Unit,
    onSeeAllTraining: () -> Unit,
    knowledgeState: SectionState<List<KnowledgeDocument>>,
    onKnowledgeDocSelect: (KnowledgeDocument) -> Unit,
    cachedDocIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    libraryAsGrid: Boolean = false, // true for PO — Learning Library as 4-up grid
    onRefresherStart: (LearnModule) -> Unit = {},
    onShowRefresherQuiz: (queueFamilyIds: List<String>) -> Unit = {},
    onSeeAllRefreshers: () -> Unit = {},
    onOpenTrainingRequests: (() -> Unit)? = null, // SK-only; PO leaves it null
) {
    // No whole-tab error state: the three sub-tabs read three different tables
    // (module_cache, assigned_video, published_source_document) written by
    // independent, individually-non-fatal pulls. A failure in one is rendered by
    // the section that consumes it, so the chips, the other sub-tabs and
    // pull-to-refresh all stay reachable.
    var selectedSubTab by rememberSaveable { mutableIntStateOf(SUBTAB_TRAINING) }
    // Pull-to-refresh forces a full-catalogue inbound sync so a newly-assigned
    // module surfaces immediately (an incremental watermark pull would omit its
    // unchanged content — see MicroCoachingSDK.triggerFullInboundSync). This is the
    // shared coaching body for both SK and PO, so wrapping here covers both roles.
    val manualSync = rememberManualInboundSyncState()

    Column(modifier) {
        // Chips stay fixed above the refresh area — only the sub-tab body scrolls.
        CoachingSubTabChips(
            chips = listOf(
                SubTabChip(Icons.AutoMirrored.Outlined.MenuBook, stringResource(R.string.coaching_subtab_training)),
                SubTabChip(Icons.Outlined.Autorenew, stringResource(R.string.coaching_subtab_refresher)),
                SubTabChip(Icons.Outlined.Lightbulb, stringResource(R.string.coaching_subtab_knowledge)),
            ),
            selectedIndex = selectedSubTab,
            onSelect = { selectedSubTab = it },
        )
        // A refresh that only partly landed used to report success silently — say so once,
        // above the sub-tabs, since the affected content may be in any of them.
        if (manualSync.lastResult?.anyFailure == true) {
            NoticeBanner(stringResource(R.string.coaching_sync_partial_notice))
        }
        PullToRefreshBox(
            isRefreshing = manualSync.isRefreshing,
            onRefresh = { manualSync.refresh() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when (selectedSubTab) {
                SUBTAB_TRAINING -> TrainingVideosSubTab(
                    chwId = chwId,
                    modifier = Modifier.fillMaxSize(),
                )
                SUBTAB_REFRESHER -> RefresherSubTab(
                    isLoading = uiState is LearnUiState.Loading,
                    onModuleSelected = onModuleSelected,
                    onRefresherStart = onRefresherStart,
                    onShowRefresherQuiz = onShowRefresherQuiz,
                    onSeeAllRefreshers = onSeeAllRefreshers,
                    onSeeAllTraining = onSeeAllTraining,
                    modifier = Modifier.fillMaxSize(),
                    onRetrySync = onRetrySync,
                    libraryAsGrid = libraryAsGrid,
                    onOpenTrainingRequests = onOpenTrainingRequests,
                )
                SUBTAB_KNOWLEDGE -> KnowledgeSubTab(
                    state = knowledgeState,
                    onDocSelect = onKnowledgeDocSelect,
                    cachedDocIds = cachedDocIds,
                    onRetry = onRetrySync,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

