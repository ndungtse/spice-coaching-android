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
import com.medtroniclabs.microcoaching.ui.learn.canRetryActiveQuiz
import com.medtroniclabs.microcoaching.ui.learn.finishQuiz
import com.medtroniclabs.microcoaching.ui.learn.hasQuestion
import com.medtroniclabs.microcoaching.ui.learn.retryCourse
import com.medtroniclabs.microcoaching.ui.learn.selectAnswer
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

/** Quiz routes (question + result), extracted from CoachingNavGraph (behaviour-preserving). */
internal fun NavGraphBuilder.quizGraph(
    navController: NavHostController,
    learnVm: LearnViewModel,
    onFinish: () -> Unit,
) {
    composable(
        route = CoachingRoute.QuizQuestion.route,
        arguments = listOf(
            navArgument(CoachingRoute.QuizQuestion.ARG_QUESTION_INDEX) {
                type = NavType.IntType
            }
        ),
    ) { backStack ->
        val index = backStack.arguments?.getInt(CoachingRoute.QuizQuestion.ARG_QUESTION_INDEX) ?: 0
        // See LessonContent above — recover instead of blanking when the quiz
        // route is restored without its questions (the screen itself would
        // `return` to nothing for this index).
        val hasBackingState = remember(index) {
            (learnVm.uiState.value as? LearnUiState.QuizInProgress)
                ?.questions?.getOrNull(index) != null
        }
        if (!hasBackingState) {
            RecoverToModulesHome(navController)
            return@composable
        }
        val uiState by learnVm.uiState.collectAsState()
        QuizQuestionScreen(
            uiState = uiState,
            questionIndex = index,
            onAnswerSelected = { answerIndex -> learnVm.selectAnswer(index, answerIndex) },
            onNext = {
                // whenSettled: a double-tap on Next otherwise stacks a
                // duplicate question entry — or runs finishQuiz() twice on
                // the last question (double telemetry + racing navigations).
                navController.whenSettled {
                    val nextIndex = index + 1
                    if (learnVm.hasQuestion(nextIndex)) {
                        navController.navigate(CoachingRoute.QuizQuestion.routeFor(nextIndex))
                    } else {
                        learnVm.finishQuiz()
                        navController.navigate(CoachingRoute.QuizResult.route) {
                            popUpTo(CoachingRoute.ModuleReady.route)
                        }
                    }
                }
            },
            onBack = {
                // Each QuizQuestion(N) was pushed via navigate(routeFor(N)),
                // so the back stack already has QuizQuestion(N-1) underneath.
                // popBackStack walks it without nuking the QuizInProgress state.
                // At question 0 we leave the quiz cleanly back to LessonContent.
                navController.whenSettled {
                    if (index > 0) {
                        navController.popOrFinish(onFinish)
                    } else {
                        learnVm.restoreModuleDetail()
                        navController.popToOrHome(CoachingRoute.LessonContent.route)
                    }
                }
            },
            onHome = onFinish,
        )
    }

    composable(CoachingRoute.QuizResult.route) {
        // See LessonContent above — recover instead of blanking when the
        // result route is restored without its computed QuizResult state.
        val hasBackingState = remember {
            learnVm.uiState.value is LearnUiState.QuizResult
        }
        if (!hasBackingState) {
            RecoverToModulesHome(navController)
            return@composable
        }
        val uiState by learnVm.uiState.collectAsState()
        QuizResultScreen(
            uiState = uiState,
            isRefresherQuiz = learnVm.startedViaRefresher,
            onNextModule = {
                // Lightweight restore — popToModuleList() reuses the cached
                // module list, then we pop to the existing ModuleReady entry
                // instead of pushing a fresh one (avoids stacking duplicates
                // and the Loading flash a fresh re-init would cause).
                navController.whenSettled {
                    learnVm.popToModuleList()
                    navController.popToHome()
                }
            },
            onBackToSpice = onFinish,
            // "Try Again" is now driven by the retry-window gate
            // (canRetry). We always wire onTryAgain so the screen has a
            // valid retry path regardless of entry — refresher path
            // included — and let `canRetry` control whether the button
            // is actually surfaced. retryCourse() resets quiz counters
            // and pushes LessonContent → LessonPlayer; for refresher
            // entries the CHW gets to re-read the lesson cards before
            // retaking the quiz, which is a reasonable UX.
            canRetry = learnVm.canRetryActiveQuiz(),
            onTryAgain = {
                // Gate the whole handler (not the individual navigate calls):
                // the second navigate is an intentional same-frame chain that
                // must run; the gate only dedupes the user's tap.
                navController.whenSettled {
                    learnVm.retryCourse()
                    navController.navigate(CoachingRoute.LessonContent.route) {
                        popUpTo(CoachingRoute.QuizResult.route) { inclusive = true }
                    }
                    navController.navigate(CoachingRoute.LessonPlayer.route)
                }
            },
        )
    }
}
