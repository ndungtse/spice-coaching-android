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
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.common.rememberLastSyncedSubtitle
import com.medtroniclabs.microcoaching.ui.common.SectionState
import com.medtroniclabs.microcoaching.ui.learn.KnowledgeDocument
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.LearnUiState
import com.medtroniclabs.microcoaching.ui.podashboard.PODashboardTab
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

/** Tab indices for the PO home. */
private const val TAB_COACHING = 0
private const val TAB_DASHBOARD = 1

/**
 * PO (Program Officer) home: Coaching | Dashboard tabs. Coaching reuses the shared
 * surface — Practice Zone included, since POs now work refreshers like SKs; Dashboard
 * is a placeholder until P4. The rich PO profile header (name · location · SK count)
 * arrives with the dashboard work — for now the header shows "Personalised Coaching".
 */
@Composable
fun POCoachingScreen(
    uiState: LearnUiState,
    chwId: String,
    onModuleSelected: (LearnModule) -> Unit,
    onClose: () -> Unit,
    onRetrySync: () -> Unit,
    onSeeAllTraining: () -> Unit,
    onRefresherStart: (LearnModule) -> Unit = {},
    onShowRefresherQuiz: (queueFamilyIds: List<String>) -> Unit = {},
    onSeeAllRefreshers: () -> Unit = {},
    knowledgeState: SectionState<List<KnowledgeDocument>>,
    onKnowledgeDocSelect: (KnowledgeDocument) -> Unit,
    onSeeAllKnowledge: () -> Unit,
    onOpenActiveSks: (com.medtroniclabs.microcoaching.ui.podashboard.SkStatus) -> Unit = {},
    onOpenChatbotUsage: () -> Unit = {},
    onOpenModulesCompleted: () -> Unit = {},
    onOpenSkDetail: (String) -> Unit = {},
    onOpenSearchedModule: (String) -> Unit = {},
    onOpenSuggestion: (String) -> Unit = {},
    onOpenDocument: (String) -> Unit = {},
    onShowAllSection: (com.medtroniclabs.microcoaching.ui.podashboard.PoDashboardSection, com.medtroniclabs.microcoaching.ui.podashboard.DateRange) -> Unit = { _, _ -> },
    cachedDocIds: Set<String> = emptySet(),
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_COACHING) }
    // "Last synced …" belongs to the module surface, so show it only on the
    // Coaching tab (hidden on Dashboard).
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
                stringResource(R.string.coaching_tab_dashboard),
            ),
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                TAB_COACHING -> CoachingTab(
                    uiState = uiState,
                    chwId = chwId,
                    onModuleSelected = onModuleSelected,
                    onRetrySync = onRetrySync,
                    onSeeAllTraining = onSeeAllTraining,
                    knowledgeState = knowledgeState,
                    onKnowledgeDocSelect = onKnowledgeDocSelect,
                    // onSeeAllKnowledge is no longer forwarded — the knowledge see-all entry
                    // died with the sub-tab split; param kept so the LearnNavGraph wiring
                    // stays untouched.
                    cachedDocIds = cachedDocIds,
                    onRefresherStart = onRefresherStart,
                    onShowRefresherQuiz = onShowRefresherQuiz,
                    onSeeAllRefreshers = onSeeAllRefreshers,
                    libraryAsGrid = true, // PO Learning Library shown as a 4-up grid
                    modifier = Modifier.fillMaxSize(),
                )
                else -> PODashboardTab(
                    chwId = chwId,
                    onOpenActiveSks = onOpenActiveSks,
                    onOpenChatbotUsage = onOpenChatbotUsage,
                    onOpenModulesCompleted = onOpenModulesCompleted,
                    onOpenSkDetail = onOpenSkDetail,
                    onOpenSearchedModule = onOpenSearchedModule,
                    onOpenSuggestion = onOpenSuggestion,
                    onOpenDocument = onOpenDocument,
                    onShowAllSection = onShowAllSection,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
