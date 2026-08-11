package com.medtroniclabs.microcoaching.ui.coaching

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.badges.BadgesTab
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.common.rememberLastSyncedSubtitle
import com.medtroniclabs.microcoaching.ui.common.SectionState
import com.medtroniclabs.microcoaching.ui.learn.KnowledgeDocument
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.LearnUiState
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

/** Tab indices for the SK home. */
private const val TAB_COACHING = 0
private const val TAB_BADGES = 1

/**
 * SK (CHW) home: "Personalised Coaching" header with Coaching | Badges tabs.
 * Coaching reuses the full refresher/training/knowledge surface (plus a streak banner);
 * Badges shows learning achievements (stub-backed). Replaces the former Leaderboard tab;
 * `ui/leaderboard/` stays in-tree, dormant and annotated `@Deprecated` as such.
 */
@Composable
fun SKCoachingScreen(
    uiState: LearnUiState,
    chwId: String,
    onModuleSelected: (LearnModule) -> Unit,
    onRefresherStart: (LearnModule) -> Unit,
    onShowQuickLearn: (moduleFamilyId: String?, queueFamilyIds: List<String>) -> Unit,
    onShowRefresherQuiz: (queueFamilyIds: List<String>) -> Unit,
    onClose: () -> Unit,
    onRetrySync: () -> Unit,
    onSeeAllTraining: () -> Unit,
    onSeeAllRefreshers: () -> Unit,
    knowledgeState: SectionState<List<KnowledgeDocument>>,
    onKnowledgeDocSelect: (KnowledgeDocument) -> Unit,
    onSeeAllKnowledge: () -> Unit,
    cachedDocIds: Set<String> = emptySet(),
    onOpenTrainingRequests: (() -> Unit)? = null,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_COACHING) }
    // "Last synced …" belongs to the module surface, so show it only on the
    // Coaching tab (hidden on Badges).
    val syncedSubtitle = rememberLastSyncedSubtitle()

    Column(modifier = Modifier.fillMaxSize().background(SurfaceMuted)) { // off-white so cards stand out
        SdkScreenHeader(
            title = stringResource(R.string.modules_screen_title),
            subtitle = syncedSubtitle.takeIf { selectedTab == TAB_COACHING },
            onBack = onClose,
            onHome = onClose,
            // largeTitle = true,
            titleAtStart = true,
        )
        CoachingTopTabs(
            labels = listOf(
                stringResource(R.string.coaching_tab_coaching),
                stringResource(R.string.coaching_tab_badges),
            ),
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                TAB_COACHING -> {
                    // onShowQuickLearn / onSeeAllKnowledge are no longer forwarded — the banner and the
                    // knowledge see-all entry died with the sub-tab split; params kept so the LearnNavGraph
                    // wiring stays untouched.
                    CoachingTab(
                        uiState = uiState,
                        chwId = chwId,
                        onModuleSelected = onModuleSelected,
                        onRetrySync = onRetrySync,
                        onSeeAllTraining = onSeeAllTraining,
                        knowledgeState = knowledgeState,
                        onKnowledgeDocSelect = onKnowledgeDocSelect,
                        cachedDocIds = cachedDocIds,
                        onRefresherStart = onRefresherStart,
                        onShowRefresherQuiz = onShowRefresherQuiz,
                        onSeeAllRefreshers = onSeeAllRefreshers,
                        onOpenTrainingRequests = onOpenTrainingRequests,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> BadgesTab(chwId = chwId)
            }
        }
    }
}
