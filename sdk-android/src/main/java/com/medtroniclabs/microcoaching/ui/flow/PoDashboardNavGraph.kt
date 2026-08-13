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
import androidx.navigation.NavGraphBuilder
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
import com.medtroniclabs.microcoaching.ui.podashboard.DateRange
import com.medtroniclabs.microcoaching.ui.podashboard.PoDashboardSection
import com.medtroniclabs.microcoaching.ui.podashboard.SkStatus
import com.medtroniclabs.microcoaching.ui.podashboard.drilldown.ActiveSksScreen
import com.medtroniclabs.microcoaching.ui.podashboard.drilldown.ChatbotUsageScreen
import com.medtroniclabs.microcoaching.ui.podashboard.drilldown.ModulesCompletedScreen
import com.medtroniclabs.microcoaching.ui.podashboard.drilldown.PoSectionListScreen
import com.medtroniclabs.microcoaching.ui.podashboard.drilldown.DocumentUsageDetailScreen
import com.medtroniclabs.microcoaching.ui.podashboard.drilldown.SearchedModuleDetailScreen
import com.medtroniclabs.microcoaching.ui.podashboard.drilldown.SkDetailScreen
import com.medtroniclabs.microcoaching.ui.podashboard.drilldown.SuggestionDetailScreen
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

/** PO-dashboard drill-down routes, extracted from CoachingNavGraph (behaviour-preserving). */
internal fun NavGraphBuilder.poDashboardGraph(
    navController: NavHostController,
    chwId: String,
    onFinish: () -> Unit,
) {
    // ── PO dashboard drill-downs ───────────────────────────────────────────
    composable(
        route = CoachingRoute.ActiveSks.route,
        arguments = listOf(navArgument(CoachingRoute.ActiveSks.ARG_STATUS) { type = NavType.StringType }),
    ) { backStack ->
        val status = runCatching {
            SkStatus.valueOf(backStack.arguments?.getString(CoachingRoute.ActiveSks.ARG_STATUS).orEmpty())
        }.getOrDefault(SkStatus.ACTIVE)
        ActiveSksScreen(
            chwId = chwId,
            status = status,
            onBack = { navController.whenSettled { navController.popOrFinish(onFinish) } },
            onHome = onFinish,
            onOpenSkDetail = { skId ->
                navController.whenSettled { navController.navigate(CoachingRoute.SkDetail.routeFor(skId)) }
            },
        )
    }
    composable(CoachingRoute.ChatbotUsage.route) {
        ChatbotUsageScreen(
            chwId = chwId,
            onBack = { navController.whenSettled { navController.popOrFinish(onFinish) } },
            onHome = onFinish,
        )
    }
    composable(CoachingRoute.ModulesCompleted.route) {
        ModulesCompletedScreen(
            chwId = chwId,
            onBack = { navController.whenSettled { navController.popOrFinish(onFinish) } },
            onHome = onFinish,
        )
    }
    composable(
        route = CoachingRoute.SkDetail.route,
        arguments = listOf(navArgument(CoachingRoute.SkDetail.ARG_SK_ID) { type = NavType.StringType }),
    ) { backStack ->
        val skId = backStack.arguments?.getString(CoachingRoute.SkDetail.ARG_SK_ID).orEmpty()
        SkDetailScreen(
            skId = skId,
            onBack = { navController.whenSettled { navController.popOrFinish(onFinish) } },
            onHome = onFinish,
        )
    }
    composable(
        route = CoachingRoute.SearchedModuleDetail.route,
        arguments = listOf(navArgument(CoachingRoute.SearchedModuleDetail.ARG_MODULE_ID) { type = NavType.StringType }),
    ) { backStack ->
        val moduleId = backStack.arguments?.getString(CoachingRoute.SearchedModuleDetail.ARG_MODULE_ID).orEmpty()
        SearchedModuleDetailScreen(
            moduleId = moduleId,
            onBack = { navController.whenSettled { navController.popOrFinish(onFinish) } },
            onHome = onFinish,
        )
    }
    composable(
        route = CoachingRoute.DocumentUsageDetail.route,
        arguments = listOf(navArgument(CoachingRoute.DocumentUsageDetail.ARG_DOCUMENT_ID) { type = NavType.StringType }),
    ) { backStack ->
        val documentId = backStack.arguments?.getString(CoachingRoute.DocumentUsageDetail.ARG_DOCUMENT_ID).orEmpty()
        DocumentUsageDetailScreen(
            documentId = documentId,
            onBack = { navController.whenSettled { navController.popOrFinish(onFinish) } },
            onHome = onFinish,
        )
    }
    composable(
        route = CoachingRoute.SuggestionDetail.route,
        arguments = listOf(navArgument(CoachingRoute.SuggestionDetail.ARG_SUGGESTION_ID) { type = NavType.StringType }),
    ) { backStack ->
        val suggestionId = backStack.arguments?.getString(CoachingRoute.SuggestionDetail.ARG_SUGGESTION_ID).orEmpty()
        SuggestionDetailScreen(
            suggestionId = suggestionId,
            onBack = { navController.whenSettled { navController.popOrFinish(onFinish) } },
            onHome = onFinish,
        )
    }
    composable(
        route = CoachingRoute.SectionAll.route,
        arguments = listOf(
            navArgument(CoachingRoute.SectionAll.ARG_SECTION) { type = NavType.StringType },
            navArgument(CoachingRoute.SectionAll.ARG_FROM) { type = NavType.LongType },
            navArgument(CoachingRoute.SectionAll.ARG_TO) { type = NavType.LongType },
        ),
    ) { backStack ->
        val args = backStack.arguments
        val section = runCatching {
            PoDashboardSection.valueOf(args?.getString(CoachingRoute.SectionAll.ARG_SECTION).orEmpty())
        }.getOrDefault(PoDashboardSection.MY_SKS)
        val range = DateRange(
            args?.getLong(CoachingRoute.SectionAll.ARG_FROM) ?: 0L,
            args?.getLong(CoachingRoute.SectionAll.ARG_TO) ?: 0L,
        )
        PoSectionListScreen(
            section = section,
            range = range,
            chwId = chwId,
            onBack = { navController.whenSettled { navController.popOrFinish(onFinish) } },
            onHome = onFinish,
            onOpenSkDetail = { skId ->
                navController.whenSettled { navController.navigate(CoachingRoute.SkDetail.routeFor(skId)) }
            },
            onOpenSearchedModule = { moduleId ->
                navController.whenSettled { navController.navigate(CoachingRoute.SearchedModuleDetail.routeFor(moduleId)) }
            },
            onOpenSuggestion = { suggestionId ->
                navController.whenSettled { navController.navigate(CoachingRoute.SuggestionDetail.routeFor(suggestionId)) }
            },
            onOpenDocument = { documentId ->
                navController.whenSettled { navController.navigate(CoachingRoute.DocumentUsageDetail.routeFor(documentId)) }
            },
        )
    }
}
