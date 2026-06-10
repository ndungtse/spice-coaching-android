package com.medtroniclabs.microcoaching.ui.flow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
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
import com.medtroniclabs.microcoaching.ui.learn.ModuleReadyScreen
import com.medtroniclabs.microcoaching.ui.learn.modules.ALL_MODULES_TYPE_TRAINING
import com.medtroniclabs.microcoaching.ui.learn.modules.AllModulesScreen
import com.medtroniclabs.microcoaching.ui.learn.LessonCompleteScreen
import com.medtroniclabs.microcoaching.ui.learn.LessonPlayerScreen
import com.medtroniclabs.microcoaching.ui.learn.QuizRetryGate
import com.medtroniclabs.microcoaching.ui.learn.ModuleDetailScreen
import com.medtroniclabs.microcoaching.ui.quiz.QuizQuestionScreen
import com.medtroniclabs.microcoaching.ui.quiz.QuizResultScreen
import com.medtroniclabs.microcoaching.ui.onboarding.CoachMarkScreen
import com.medtroniclabs.microcoaching.ui.onboarding.OnboardingSlideScreen
import com.medtroniclabs.microcoaching.ui.onboarding.OnboardingViewModel

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
    var lastRefresherModuleFamilyId: String? by remember { mutableStateOf(null) }

    Box(modifier = Modifier.fillMaxSize()) {

    NavHost(navController = navController, startDestination = startRoute) {

        // ── Onboarding ─────────────────────────────────────────────────────────

        composable(CoachingRoute.CoachMark.route) {
            CoachMarkScreen(
                onDismiss = {
                    onboardingVm.markOnboarded()
                    navController.navigate(CoachingRoute.OnboardingSlides.route) {
                        popUpTo(CoachingRoute.CoachMark.route) { inclusive = true }
                    }
                }
            )
        }

        composable(CoachingRoute.OnboardingSlides.route) {
            val uiState by onboardingVm.uiState.collectAsState()
            OnboardingSlideScreen(
                uiState = uiState,
                onNext = onboardingVm::nextSlide,
                onSkip = {
                    onboardingVm.markSlideDone()
                    navController.navigate(CoachingRoute.ModuleReady.route) {
                        popUpTo(CoachingRoute.OnboardingSlides.route) { inclusive = true }
                    }
                },
                onDone = {
                    onboardingVm.markSlideDone()
                    navController.navigate(CoachingRoute.ModuleReady.route) {
                        popUpTo(CoachingRoute.OnboardingSlides.route) { inclusive = true }
                    }
                },
            )
        }

        // ── Learn ──────────────────────────────────────────────────────────────

        composable(CoachingRoute.ModuleReady.route) {
            val uiState by learnVm.uiState.collectAsState()
            ModuleReadyScreen(
                uiState = uiState,
                onModuleSelected = { module ->
                    // Fix 1: skip FocusedModuleContent — go straight to ModuleDetailScreen.
                    learnVm.selectModule(module)
                    learnVm.startLesson()
                    navController.navigate(CoachingRoute.LessonContent.route)
                },
                onStartLearning = { /* no-op — skipped by Fix 1 */ },
                onClose = onFinish,
                chwId = chwId,
                onRefresherStart = { module ->
                    // Capture the tapped module's family id so it can be forwarded
                    // to RefresherBottomSheet via `targetModuleFamilyId`.
                    //
                    // Do NOT call `learnVm.selectModuleForQuiz(module)` here:
                    // RefresherBottomSheet creates its own fragment-scoped
                    // LearnViewModel via QuickLearnViewModel.factory, so flipping
                    // the activity-scoped VM into QuizInProgress does nothing for
                    // the sheet. Worse, it gates `observeModules` from emitting
                    // updates to the activity VM (see [LearnViewModel.observeModules]
                    // `isListState` check), which is why the refresher tile used to
                    // show stale wrong-counts after dismiss. The activity VM stays
                    // in ModuleList state, the Flow drives normal updates, and the
                    // dismiss handler does a fresh DB read for belt-and-braces.
                    lastRefresherModuleFamilyId = module.moduleFamilyId
                },
                onKnowledgeSelect = { module ->
                    learnVm.selectModule(module)
                    learnVm.startLesson()
                    navController.navigate(CoachingRoute.LessonContent.route)
                },
                onShowQuickLearn = { moduleFamilyId ->
                    android.util.Log.d("CoachingNavGraph", "Showing RefresherBottomSheet (question-first) target=$moduleFamilyId")
                    RefresherBottomSheet.show(
                        fragmentManager, chwId,
                        fromHomeScreen = false,
                        targetModuleFamilyId = moduleFamilyId,
                    )
                },
                onShowRefresherQuiz = {
                    android.util.Log.d("CoachingNavGraph", "Showing RefresherBottomSheet for tile=${lastRefresherModuleFamilyId}")
                    RefresherBottomSheet.show(
                        fragmentManager, chwId,
                        fromHomeScreen = false,
                        targetModuleFamilyId = lastRefresherModuleFamilyId,
                    )
                },
                onRetrySync = learnVm::retrySync,
                onSeeAllTraining = {
                    navController.navigate(
                        CoachingRoute.AllModules.routeFor(ALL_MODULES_TYPE_TRAINING),
                    )
                },
            )
        }

        composable(CoachingRoute.AllModules.route) { backStackEntry ->
            val moduleType = backStackEntry.arguments
                ?.getString(CoachingRoute.AllModules.ARG_MODULE_TYPE)
                ?: ALL_MODULES_TYPE_TRAINING
            val uiState by learnVm.uiState.collectAsState()
            // Source the list from the current ModuleList state; fall back to the
            // last non-empty list so a transient non-list state (e.g. while a
            // tapped module pushes LessonContent) doesn't blank the grid.
            val lastList = remember { mutableStateOf<List<LearnModule>>(emptyList()) }
            (uiState as? LearnUiState.ModuleList)?.let { lastList.value = it.modules }
            AllModulesScreen(
                modules = lastList.value,
                moduleType = moduleType,
                onSelect = { module ->
                    // Identical to the ModulesScreen tap path so the detail/quiz
                    // flow and back-navigation behave the same from either entry.
                    learnVm.selectModule(module)
                    learnVm.startLesson()
                    navController.navigate(CoachingRoute.LessonContent.route)
                },
                onBack = { navController.popBackStack() },
                onHome = onFinish,
            )
        }

        composable(CoachingRoute.LessonContent.route) {
            val uiState by learnVm.uiState.collectAsState()
            // System back / header back both restore ModuleList state from the
            // cached module list and pop the LessonContent entry off the stack.
            val handleBack: () -> Unit = {
                learnVm.popToModuleList()
                navController.popBackStack()
            }
            BackHandler(onBack = handleBack)
            val autoSpeak by learnVm.autoSpeakEnabled.collectAsState()
            ModuleDetailScreen(
                uiState = uiState,
                onContinueToQuiz = {
                    learnVm.startQuiz()
                    navController.navigate(CoachingRoute.QuizQuestion.routeFor(0))
                },
                onStartCourse = {
                    learnVm.startCourse()
                    navController.navigate(CoachingRoute.LessonPlayer.route)
                },
                onReadAgain = {
                    // Revisit a completed module: open the lesson player in
                    // read-only mode. `startCourse()` is intentionally NOT
                    // called — its in-memory "in_progress" flip is guarded
                    // for completed modules anyway, but keeping the call out
                    // makes the read-only intent explicit at the call site.
                    navController.navigate(CoachingRoute.LessonPlayer.route)
                },
                onBack = handleBack,
                autoSpeakEnabled = autoSpeak,
                onToggleAutoSpeak = learnVm::toggleAutoSpeak,
                onHome = onFinish,
            )
        }

        composable(CoachingRoute.LessonPlayer.route) {
            val uiState by learnVm.uiState.collectAsState()
            val module = (uiState as? LearnUiState.LessonContent)?.module
            val autoSpeak by learnVm.autoSpeakEnabled.collectAsState()
            if (module != null) {
                // The module overload reads SDK language internally.
                //
                // Read-only mode (last-card CTA → "Back to modules", no quiz
                // path) is now driven *purely* by the retry-window gate
                // (see [QuizRetryGate]):
                //
                //   - Within 7 days of publication: gate open → CHW can
                //     (re)take the quiz, even after passing. This matches
                //     PM direction — completed modules stay re-quizzable
                //     during the initial window.
                //   - After 7 days AND every question already attempted:
                //     gate closed → "Back to modules".
                //
                // When removing the retry-window feature: delete the call,
                // the readOnly local goes back to `false`, and completed
                // modules become indefinitely re-quizzable. If product
                // wants the previous "completed → read-only" behaviour
                // restored without the gate, OR `module.status == "completed"`
                // back in here.
                val readOnly = QuizRetryGate.isRetryWindowClosed(module)
                LessonPlayerScreen(
                    module = module,
                    onBack = {
                        // Pop back to the existing LessonContent entry. Using
                        // navigate + popUpTo here would push a *new* entry on top
                        // of the original, leaving two LessonContent entries on
                        // the back stack and causing a blank-screen regression
                        // on the next back press.
                        learnVm.restoreModuleDetail()
                        navController.popBackStack(
                            route = CoachingRoute.LessonContent.route,
                            inclusive = false,
                        )
                    },
                    onStartQuiz = {
                        learnVm.startQuiz()
                        navController.navigate(CoachingRoute.QuizQuestion.routeFor(0))
                    },
                    readOnly = readOnly,
                    onFinishReading = {
                        // "Back to modules" — exit revisit mode all the way
                        // to the modules list. Uses the existing
                        // popToModuleList helper so active state (activeModule,
                        // active questions) is cleared in lockstep with the
                        // back-stack pop.
                        learnVm.popToModuleList()
                        navController.popBackStack(
                            route = CoachingRoute.ModuleReady.route,
                            inclusive = false,
                        )
                    },
                    onCardShown = { idx: Int -> learnVm.recordCardShown(idx) },
                    autoSpeakEnabled = autoSpeak,
                    onToggleAutoSpeak = learnVm::toggleAutoSpeak,
                    onSpeak = { text, onDone -> learnVm.speakAloud(text, onDone) },
                    onStopSpeak = learnVm::stopSpeaking,
                    onHome = onFinish,
                )
            }
        }

        composable(CoachingRoute.LessonComplete.route) {
            LessonCompleteScreen(
                onBack = {
                    navController.popBackStack(
                        route = CoachingRoute.ModuleReady.route,
                        inclusive = false,
                    )
                }
            )
        }

        composable(
            route = CoachingRoute.QuizQuestion.route,
            arguments = listOf(
                navArgument(CoachingRoute.QuizQuestion.ARG_QUESTION_INDEX) {
                    type = NavType.IntType
                }
            ),
        ) { backStack ->
            val index = backStack.arguments?.getInt(CoachingRoute.QuizQuestion.ARG_QUESTION_INDEX) ?: 0
            val uiState by learnVm.uiState.collectAsState()
            QuizQuestionScreen(
                uiState = uiState,
                questionIndex = index,
                onAnswerSelected = { answerIndex -> learnVm.selectAnswer(index, answerIndex) },
                onNext = {
                    val nextIndex = index + 1
                    if (learnVm.hasQuestion(nextIndex)) {
                        navController.navigate(CoachingRoute.QuizQuestion.routeFor(nextIndex))
                    } else {
                        learnVm.finishQuiz()
                        navController.navigate(CoachingRoute.QuizResult.route) {
                            popUpTo(CoachingRoute.ModuleReady.route)
                        }
                    }
                },
                onBack = {
                    // Each QuizQuestion(N) was pushed via navigate(routeFor(N)),
                    // so the back stack already has QuizQuestion(N-1) underneath.
                    // popBackStack walks it without nuking the QuizInProgress state.
                    // At question 0 we leave the quiz cleanly back to LessonContent.
                    if (index > 0) {
                        navController.popBackStack()
                    } else {
                        learnVm.restoreModuleDetail()
                        navController.popBackStack(
                            route = CoachingRoute.LessonContent.route,
                            inclusive = false,
                        )
                    }
                },
                onHome = onFinish,
            )
        }

        composable(CoachingRoute.QuizResult.route) {
            val uiState by learnVm.uiState.collectAsState()
            QuizResultScreen(
                uiState = uiState,
                isRefresherQuiz = learnVm.startedViaRefresher,
                onNextModule = {
                    // Lightweight restore — popToModuleList() reuses the cached
                    // module list, then we pop to the existing ModuleReady entry
                    // instead of pushing a fresh one (avoids stacking duplicates
                    // and the Loading flash a fresh re-init would cause).
                    learnVm.popToModuleList()
                    navController.popBackStack(
                        route = CoachingRoute.ModuleReady.route,
                        inclusive = false,
                    )
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
                    learnVm.retryCourse()
                    navController.navigate(CoachingRoute.LessonContent.route) {
                        popUpTo(CoachingRoute.QuizResult.route) { inclusive = true }
                    }
                    navController.navigate(CoachingRoute.LessonPlayer.route)
                },
            )
        }
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
        val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
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
    }
}
