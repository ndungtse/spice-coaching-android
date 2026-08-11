package com.medtroniclabs.microcoaching.ui.coaching

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.medtroniclabs.microcoaching.CoachingPersona
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.domain.reminder.IncompleteModuleReminder
import com.medtroniclabs.microcoaching.domain.reminder.ReminderPrefs
import com.medtroniclabs.microcoaching.domain.reminder.ReminderWindow
import com.medtroniclabs.microcoaching.ui.common.SectionState
import com.medtroniclabs.microcoaching.ui.learn.KnowledgeDocument
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.LearnUiState
import java.time.ZoneId

/**
 * Coaching home entry: branches on the SDK persona to render the SK or PO parent
 * screen. [CoachingPersona.UNKNOWN] falls back to the SK experience. Hosted by the
 * `ModuleReady` route so the chat FAB / back-stack recovery wiring is unchanged.
 */
@Composable
fun CoachingHomeHost(
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
    onOpenActiveSks: () -> Unit = {},
    onOpenChatbotUsage: () -> Unit = {},
    onOpenModulesCompleted: () -> Unit = {},
    onOpenSkDetail: (String) -> Unit = {},
    onOpenSearchedModule: (String) -> Unit = {},
    onOpenSuggestion: (String) -> Unit = {},
    onOpenDocument: (String) -> Unit = {},
    onShowAllSection: (com.medtroniclabs.microcoaching.ui.podashboard.PoDashboardSection, com.medtroniclabs.microcoaching.ui.podashboard.DateRange) -> Unit = { _, _ -> },
    cachedDocIds: Set<String> = emptySet(),
    onOpenTrainingRequests: (() -> Unit)? = null,
) {
    val sdk = MicroCoachingSDK.getInstance()
    val persona by sdk.persona.collectAsState()

    IncompleteModuleReminderHost(chwId = chwId)

    when (persona) {
        CoachingPersona.PO -> POCoachingScreen(
            uiState = uiState,
            chwId = chwId,
            onModuleSelected = onModuleSelected,
            onClose = onClose,
            onRetrySync = onRetrySync,
            onSeeAllTraining = onSeeAllTraining,
            onRefresherStart = onRefresherStart,
            onShowRefresherQuiz = onShowRefresherQuiz,
            onSeeAllRefreshers = onSeeAllRefreshers,
            knowledgeState = knowledgeState,
            onKnowledgeDocSelect = onKnowledgeDocSelect,
            onSeeAllKnowledge = onSeeAllKnowledge,
            onOpenActiveSks = onOpenActiveSks,
            onOpenChatbotUsage = onOpenChatbotUsage,
            onOpenModulesCompleted = onOpenModulesCompleted,
            onOpenSkDetail = onOpenSkDetail,
            onOpenSearchedModule = onOpenSearchedModule,
            onOpenSuggestion = onOpenSuggestion,
            onOpenDocument = onOpenDocument,
            onShowAllSection = onShowAllSection,
            cachedDocIds = cachedDocIds,
        )
        else -> SKCoachingScreen( // SK + UNKNOWN
            uiState = uiState,
            chwId = chwId,
            onModuleSelected = onModuleSelected,
            onRefresherStart = onRefresherStart,
            onShowQuickLearn = onShowQuickLearn,
            onShowRefresherQuiz = onShowRefresherQuiz,
            onClose = onClose,
            onRetrySync = onRetrySync,
            onSeeAllTraining = onSeeAllTraining,
            onSeeAllRefreshers = onSeeAllRefreshers,
            knowledgeState = knowledgeState,
            onKnowledgeDocSelect = onKnowledgeDocSelect,
            onSeeAllKnowledge = onSeeAllKnowledge,
            cachedDocIds = cachedDocIds,
            // Training requests are a CHW/SK surface — the PO branch above
            // deliberately doesn't forward this, so PO never sees the entry.
            onOpenTrainingRequests = onOpenTrainingRequests,
        )
    }
}

/**
 * Incomplete-assigned-modules reminder popup (MED-1529 Req 2), shared by both
 * personas since it hangs off [CoachingHomeHost].
 *
 * Reads the assigned-but-incomplete count from the SDK's module store and asks
 * the pure [IncompleteModuleReminder] gate whether to surface the popup for the
 * current morning / afternoon window. The window is marked **when shown** (via
 * [ReminderPrefs]) so it fires at most twice per local day and never re-shows in
 * a window already seen. Gated by [MicroCoachingConfig.enableIncompleteModuleReminder].
 */
@Composable
private fun IncompleteModuleReminderHost(chwId: String) {
    val sdk = MicroCoachingSDK.getInstance()
    if (!sdk.config.enableIncompleteModuleReminder) return
    if (chwId.isBlank()) return

    val context = LocalContext.current
    val store = sdk.coachingModuleStore
    val count by store.incompleteAssignedCount.collectAsState()
    val loaded by store.trainingAssignmentsLoaded.collectAsState()

    var showDialog by remember { mutableStateOf(false) }
    var dialogCount by remember { mutableStateOf(0) }

    // Re-evaluate when the assigned set loads or the count settles; the mark-on-
    // show write + the per-window date guard make repeat runs idempotent, so a
    // count change during the session never double-shows within the same window.
    LaunchedEffect(chwId, loaded, count) {
        val window = IncompleteModuleReminder.shouldShow(
            incompleteCount = count,
            loaded = loaded,
            lastShownMorningDate = ReminderPrefs.lastShownDate(context, chwId, ReminderWindow.MORNING),
            lastShownAfternoonDate = ReminderPrefs.lastShownDate(context, chwId, ReminderWindow.AFTERNOON),
            nowMillis = System.currentTimeMillis(),
            zone = ZoneId.systemDefault(),
        )
        if (window != null) {
            ReminderPrefs.markShown(
                context,
                chwId,
                window,
                IncompleteModuleReminder.todayKey(System.currentTimeMillis(), ZoneId.systemDefault()),
            )
            dialogCount = count
            showDialog = true
        }
    }

    if (showDialog) {
        IncompleteModuleReminderSheet(count = dialogCount) { showDialog = false }
    }
}
