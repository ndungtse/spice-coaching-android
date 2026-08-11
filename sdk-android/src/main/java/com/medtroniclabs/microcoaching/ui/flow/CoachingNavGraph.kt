package com.medtroniclabs.microcoaching.ui.flow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.document.DocumentPreviewActivity
import com.medtroniclabs.microcoaching.ui.learn.DocEvent
import com.medtroniclabs.microcoaching.ui.learn.modules.ALL_MODULES_TYPE_KNOWLEDGE
import kotlinx.coroutines.launch
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.medtroniclabs.microcoaching.ui.chat.ChatLaunchController
import com.medtroniclabs.microcoaching.ui.components.DraggableChatFab
import com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet.QuickLearnBottomSheet
import com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet.RefresherBottomSheet
import com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet.RefresherQuizBottomSheet
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.LearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.LearnUiState
import com.medtroniclabs.microcoaching.ui.coaching.CoachingHomeHost
import com.medtroniclabs.microcoaching.ui.podashboard.drilldown.ActiveSksScreen
import com.medtroniclabs.microcoaching.ui.podashboard.drilldown.ChatbotUsageScreen
import com.medtroniclabs.microcoaching.ui.podashboard.drilldown.ModulesCompletedScreen
import com.medtroniclabs.microcoaching.ui.podashboard.drilldown.SkDetailScreen
import com.medtroniclabs.microcoaching.ui.learn.modules.ALL_MODULES_TYPE_REFRESHER
import com.medtroniclabs.microcoaching.ui.learn.modules.ALL_MODULES_TYPE_TRAINING
import com.medtroniclabs.microcoaching.ui.learn.modules.AllModulesScreen
import com.medtroniclabs.microcoaching.ui.learn.modules.RefreshersScreen
import com.medtroniclabs.microcoaching.ui.learn.modules.components.KnowledgeDownloadBar
import com.medtroniclabs.microcoaching.ui.learn.LessonCompleteScreen
import com.medtroniclabs.microcoaching.ui.learn.LessonPlayerScreen
import com.medtroniclabs.microcoaching.ui.learn.QuizRetryGate
import com.medtroniclabs.microcoaching.ui.learn.ModuleDetailScreen
import com.medtroniclabs.microcoaching.ui.quiz.QuizQuestionScreen
import com.medtroniclabs.microcoaching.ui.quiz.QuizResultScreen
import com.medtroniclabs.microcoaching.ui.onboarding.CoachMarkScreen
import com.medtroniclabs.microcoaching.ui.onboarding.OnboardingSlideScreen
import com.medtroniclabs.microcoaching.ui.onboarding.OnboardingViewModel
import com.medtroniclabs.microcoaching.CoachingPersona
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.trainingrequest.TrainingRequestFormScreen
import com.medtroniclabs.microcoaching.ui.trainingrequest.TrainingRequestsScreen

/**
 * Routes that should host the persistent chat FAB.
 *
 * Hidden on onboarding (one-off taps, FAB would compete with the primary CTA)
 * and on the quiz question screen (reduce distraction while the CHW is answering).
 */
private val ROUTES_WITH_CHAT_FAB = setOf(
    CoachingRoute.ModuleReady.route,
    CoachingRoute.AllModules.route,
    CoachingRoute.LessonContent.route,
    CoachingRoute.LessonPlayer.route,
    CoachingRoute.LessonComplete.route,
    CoachingRoute.QuizResult.route,
)

/**
 * The full coaching flow navigation graph.
 *
 * Hosted inside [CoachingFlowActivity]. All navigation is internal.
 *
 * @param navController The controller for this graph.
 * @param startRoute The first route to display.
 * @param chwId The CHW identifier — used by [LearnViewModel] for personalisation.
 * @param onFinish Called when the user taps "Back to HOME" on the result screen.
 */
@Composable
fun CoachingNavGraph(
    navController: NavHostController,
    startRoute: String,
    chwId: String,
    fragmentManager: FragmentManager,
    viewModelStoreOwner: ViewModelStoreOwner,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val onboardingVm: OnboardingViewModel = viewModel()
    // Bind the LearnViewModel to the hosting Activity so out-of-graph callers
    // — RefresherQuizBottomSheet most importantly — resolve the SAME instance
    // via ViewModelProvider(requireActivity(), …). The activity is passed in
    // explicitly because `SdkLocaleHelper.wrap()` produces a detached
    // ContextImpl that can't be walked back to the activity.
    val learnVm: LearnViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        factory = LearnViewModel.factory(context, chwId),
    )
    android.util.Log.d(
        "CoachingNavGraph",
        "init: chwId=$chwId fragmentManager=${fragmentManager.javaClass.simpleName} " +
            "vmOwner=${viewModelStoreOwner.javaClass.simpleName}",
    )

    // Captures the last module tapped in RefresherList so its familyId can be
    // forwarded to RefresherBottomSheet (which runs in a separate Fragment scope).
    // Held as a MutableState so [learnGraph] can read AND write it across routes.
    val lastRefresherFamilyId = remember { mutableStateOf<String?>(null) }

    // Knowledge documents (deduped source docs) + the download → preview flow.
    // The docs/cachedDocIds StateFlows are collected inside learnGraph's route blocks;
    // here we only need the download-progress + doc-event side of the flow.
    val snackbarHostState = remember { SnackbarHostState() }
    val docScope = rememberCoroutineScope()
    // Live download progress drives a bottom progress surface (the Material
    // snackbar can't live-update its text). Null = nothing downloading.
    val downloadProgress by learnVm.downloadProgress.collectAsState()
    LaunchedEffect(Unit) {
        learnVm.docEvents.collect { event ->
            when (event) {
                is DocEvent.Ready ->
                    DocumentPreviewActivity.start(
                        context,
                        event.sourceDocumentId,
                        event.title,
                        originalFilename = event.fileName,
                        // Library opens are what document-usage analytics measure;
                        // chat citation chips reach the same screen without this.
                        recordView = true,
                    )
                DocEvent.Unavailable -> docScope.launch {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.knowledge_doc_unavailable_offline),
                        duration = SnackbarDuration.Short,
                    )
                }
                DocEvent.StorageFull -> docScope.launch {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.doc_error_storage_full),
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

    NavHost(navController = navController, startDestination = startRoute) {

        onboardingGraph(navController, onboardingVm)

        learnGraph(navController, learnVm, chwId, fragmentManager, onFinish, lastRefresherFamilyId)

        quizGraph(navController, learnVm, onFinish)

        trainingRequestGraph(navController, chwId, onFinish, snackbarHostState, docScope, context)

        poDashboardGraph(navController, chwId, onFinish)
    }

        // ── Persistent chat FAB overlay ───────────────────────────────────────
        // Resolves the current route from the back stack and shows the FAB only on
        // the screens defined in [ROUTES_WITH_CHAT_FAB]. Onboarding and the active
        // quiz question screen stay clean.
        //
        // The FAB is draggable so the CHW can move it out of the way when it
        // overlaps lesson / quiz content. Resting position uses a generous bottom
        // padding (96.dp) so the FAB clears Previous / Next button bars on
        // LessonPlayer and LessonContent without the user having to drag every time.
        val currentEntry = navController.currentBackStackEntryAsState().value
        val currentRoute = currentEntry?.destination?.route

        // Last-resort backstop for the blank-screen class: an empty back stack
        // composes NOTHING (a permanent white NavHost). The gated handlers above
        // make that unreachable from SDK code, but if any future path (or a
        // framework edge case) ever pops the last entry, exit the coaching flow
        // gracefully instead of stranding the CHW on a white screen.
        var hadEntry by remember { mutableStateOf(false) }
        LaunchedEffect(currentEntry) {
            if (currentEntry != null) {
                hadEntry = true
            } else if (hadEntry) {
                android.util.Log.w("CoachingNavGraph", "Back stack emptied — finishing coaching flow instead of a blank host.")
                onFinish()
            }
        }

        if (currentRoute in ROUTES_WITH_CHAT_FAB) {
            DraggableChatFab(
                onClick = {
                    // viewModelStoreOwner is the hosting CoachingFlowActivity, which extends
                    // FragmentActivity. ChatLaunchController needs the activity for the
                    // default "download started" toast fallback.
                    val activity = viewModelStoreOwner as? FragmentActivity
                    if (activity != null) {
                        ChatLaunchController.launchOrPromptDownload(
                            activity = activity,
                            fragmentManager = fragmentManager,
                        )
                    }
                },
            )
        }

        // Knowledge document download feedback. Live % progress shows in a bottom
        // bar (snackbar text can't update); the snackbar is used only for the
        // "not available offline" error. Driven by learnVm above.
        downloadProgress?.let { progress ->
            KnowledgeDownloadBar(
                progress = progress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }
}
