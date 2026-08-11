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
import com.medtroniclabs.microcoaching.ui.learn.startQuiz
import com.medtroniclabs.microcoaching.ui.learn.canTakeQuiz
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
import androidx.navigation.NavGraphBuilder
import androidx.compose.runtime.MutableState

/** Learn routes (home / all-modules / lesson / player / complete), extracted from CoachingNavGraph. */
internal fun NavGraphBuilder.learnGraph(
    navController: NavHostController,
    learnVm: LearnViewModel,
    chwId: String,
    fragmentManager: FragmentManager,
    onFinish: () -> Unit,
    lastRefresherFamilyId: MutableState<String?>,
) {
    // ── Learn ──────────────────────────────────────────────────────────────

    composable(CoachingRoute.ModuleReady.route) {
        val uiState by learnVm.uiState.collectAsState()
        val knowledgeDocs by learnVm.knowledgeDocuments.collectAsState()
        val knowledgeState by learnVm.knowledgeState.collectAsState()
        val cachedDocIds by learnVm.cachedDocIds.collectAsState()
        CoachingHomeHost(
            uiState = uiState,
            onModuleSelected = { module ->
                // Fix 1: skip FocusedModuleContent — go straight to ModuleDetailScreen.
                // whenSettled: a double-tap otherwise stacks two LessonContent
                // entries (VM flip + navigate ×2) — extra back presses + the
                // double-pop white-screen vector.
                navController.whenSettled {
                    learnVm.selectModule(module)
                    learnVm.startLesson()
                    navController.navigate(CoachingRoute.LessonContent.route)
                }
            },
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
                lastRefresherFamilyId.value = module.moduleFamilyId
            },
            onShowQuickLearn = { moduleFamilyId, queueFamilyIds ->
                android.util.Log.d("CoachingNavGraph", "Showing RefresherBottomSheet (cards-first) target=$moduleFamilyId queue=${queueFamilyIds.size}")
                // Every refresher entry — including the QuizRefresherCard banner —
                // opens cards-first (lesson cards → quiz).
                RefresherBottomSheet.show(
                    fragmentManager, chwId,
                    fromHomeScreen = false,
                    entryMode = RefresherBottomSheet.EntryMode.CARDS_FIRST,
                    targetModuleFamilyId = moduleFamilyId,
                    queueFamilyIds = queueFamilyIds,
                )
            },
            onShowRefresherQuiz = { queueFamilyIds ->
                android.util.Log.d("CoachingNavGraph", "Showing RefresherBottomSheet (cards-first) for tile=${lastRefresherFamilyId.value} queue=${queueFamilyIds.size}")
                // RefresherList entries open cards-first (lesson cards → quiz),
                // same as every other refresher entry point.
                RefresherBottomSheet.show(
                    fragmentManager, chwId,
                    fromHomeScreen = false,
                    entryMode = RefresherBottomSheet.EntryMode.CARDS_FIRST,
                    targetModuleFamilyId = lastRefresherFamilyId.value,
                    queueFamilyIds = queueFamilyIds,
                )
            },
            onRetrySync = learnVm::retrySync,
            onSeeAllTraining = {
                navController.whenSettled {
                    navController.navigate(
                        CoachingRoute.AllModules.routeFor(ALL_MODULES_TYPE_TRAINING),
                    )
                }
            },
            onSeeAllRefreshers = {
                navController.whenSettled {
                    navController.navigate(
                        CoachingRoute.AllModules.routeFor(ALL_MODULES_TYPE_REFRESHER),
                    )
                }
            },
            knowledgeState = knowledgeState,
            cachedDocIds = cachedDocIds,
            onKnowledgeDocSelect = { doc -> learnVm.openKnowledgeDocument(doc) },
            onSeeAllKnowledge = {
                navController.whenSettled {
                    navController.navigate(
                        CoachingRoute.AllModules.routeFor(ALL_MODULES_TYPE_KNOWLEDGE),
                    )
                }
            },
            onOpenActiveSks = {
                navController.whenSettled { navController.navigate(CoachingRoute.ActiveSks.route) }
            },
            onOpenChatbotUsage = {
                navController.whenSettled { navController.navigate(CoachingRoute.ChatbotUsage.route) }
            },
            onOpenModulesCompleted = {
                navController.whenSettled { navController.navigate(CoachingRoute.ModulesCompleted.route) }
            },
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
            onShowAllSection = { section, range ->
                navController.whenSettled {
                    navController.navigate(
                        CoachingRoute.SectionAll.routeFor(section.name, range.fromMillis, range.toMillis),
                    )
                }
            },
            onOpenTrainingRequests = {
                navController.whenSettled { navController.navigate(CoachingRoute.TrainingRequests.route) }
            },
        )
    }

    composable(CoachingRoute.AllModules.route) { backStackEntry ->
        val knowledgeDocs by learnVm.knowledgeDocuments.collectAsState()
        val knowledgeState by learnVm.knowledgeState.collectAsState()
        val cachedDocIds by learnVm.cachedDocIds.collectAsState()
        val moduleType = backStackEntry.arguments
            ?.getString(CoachingRoute.AllModules.ARG_MODULE_TYPE)
            ?: ALL_MODULES_TYPE_TRAINING

        // Refreshers get their own full-screen list (tiles are list-shaped,
        // not grid cells) sourced from the shared store. Tapping a tile runs
        // the IDENTICAL path to the home Refresher list: capture the tapped
        // module's family id, then open RefresherBottomSheet cards-first.
        if (moduleType == ALL_MODULES_TYPE_REFRESHER) {
            RefreshersScreen(
                onRefresherStart = { module ->
                    lastRefresherFamilyId.value = module.moduleFamilyId
                },
                onShowRefresherQuiz = { queueFamilyIds ->
                    android.util.Log.d("CoachingNavGraph", "RefreshersScreen → RefresherBottomSheet (cards-first) for tile=$lastRefresherFamilyId.value queue=${queueFamilyIds.size}")
                    RefresherBottomSheet.show(
                        fragmentManager, chwId,
                        fromHomeScreen = false,
                        entryMode = RefresherBottomSheet.EntryMode.CARDS_FIRST,
                        targetModuleFamilyId = lastRefresherFamilyId.value,
                        queueFamilyIds = queueFamilyIds,
                    )
                },
                onBack = { navController.whenSettled { navController.popOrFinish(onFinish) } },
                onHome = onFinish,
            )
            return@composable
        }

        val uiState by learnVm.uiState.collectAsState()
        // Source the list from the current ModuleList state; fall back to the
        // last non-empty list so a transient non-list state (e.g. while a
        // tapped module pushes LessonContent) doesn't blank the grid.
        // Snapshot write lives in a LaunchedEffect — writing state directly
        // during composition is a side effect Compose may re-run or discard.
        val liveList = (uiState as? LearnUiState.ModuleList)?.modules
        val lastList = remember { mutableStateOf<List<LearnModule>>(emptyList()) }
        LaunchedEffect(liveList) {
            if (liveList != null) lastList.value = liveList
        }
        // The Training "see all" must honour the same assignment filter as the
        // home screen's inline TrainingRow: both read the store's already-
        // filtered trainingModules. `uiState.modules` is the FULL mapped
        // catalogue (assignment-agnostic, shared with chat scope logic), so
        // using it here would leak unassigned modules into the grid. Other
        // types fall back to the full list (knowledge ignores `modules`).
        val assignedTraining by learnVm.trainingModules.collectAsState()
        val gridModules = if (moduleType == ALL_MODULES_TYPE_TRAINING) {
            assignedTraining
        } else {
            liveList ?: lastList.value
        }
        AllModulesScreen(
            modules = gridModules,
            moduleType = moduleType,
            // Show shimmer tiles (not a blank grid) while the first list load
            // is still in flight — i.e. the VM is Loading and we have nothing
            // cached to show yet.
            isLoading = uiState is LearnUiState.Loading && gridModules.isEmpty(),
            onSelect = { module ->
                // Identical to the ModulesScreen tap path so the detail/quiz
                // flow and back-navigation behave the same from either entry.
                navController.whenSettled {
                    learnVm.selectModule(module)
                    learnVm.startLesson()
                    navController.navigate(CoachingRoute.LessonContent.route)
                }
            },
            onBack = { navController.whenSettled { navController.popOrFinish(onFinish) } },
            onHome = onFinish,
            knowledgeDocuments = knowledgeDocs,
            onDocSelect = { doc -> learnVm.openKnowledgeDocument(doc) },
            cachedDocIds = cachedDocIds,
        )
    }

    composable(CoachingRoute.LessonContent.route) {
        // Restored onto this route after process death (back stack survives,
        // VM state doesn't) → no module to show. Bounce home instead of
        // rendering blank. Captured once at entry so it can't misfire during
        // the back-navigation state flip. See [RecoverToModulesHome].
        val hasBackingState = remember {
            learnVm.uiState.value is LearnUiState.LessonContent
        }
        if (!hasBackingState) {
            RecoverToModulesHome(navController)
            return@composable
        }
        val uiState by learnVm.uiState.collectAsState()
        // System back / header back both restore ModuleList state from the
        // cached module list and pop the LessonContent entry off the stack.
        // whenSettled: this handler stays live while the pop animates out, so
        // a spammed second back press re-fired it and double-popped — the
        // second pop removed ModuleReady and blanked the NavHost (the QA
        // stress-test white screen). Gated, the duplicate press is dropped;
        // popOrFinish guarantees the stack can never empty even if reached.
        val handleBack: () -> Unit = {
            navController.whenSettled {
                learnVm.popToModuleList()
                navController.popOrFinish(onFinish)
            }
        }
        BackHandler(onBack = handleBack)
        val autoSpeak by learnVm.autoSpeakEnabled.collectAsState()
        // Disable "Do a Quiz" once the reattempt window has closed for this
        // module (MED-1940 Req 1). Never-/partly-attempted modules stay enabled
        // (guaranteed first attempt); default to enabled while state is settling.
        val quizEnabled = (uiState as? LearnUiState.LessonContent)
            ?.module
            ?.let { learnVm.canTakeQuiz(it) }
            ?: true
        ModuleDetailScreen(
            uiState = uiState,
            onContinueToQuiz = {
                navController.whenSettled {
                    learnVm.startQuiz()
                    navController.navigate(CoachingRoute.QuizQuestion.routeFor(0))
                }
            },
            onStartCourse = {
                navController.whenSettled {
                    learnVm.startCourse()
                    navController.navigate(CoachingRoute.LessonPlayer.route)
                }
            },
            onReadAgain = {
                // Revisit a completed module: open the lesson player in
                // read-only mode. `startCourse()` is intentionally NOT
                // called — its in-memory "in_progress" flip is guarded
                // for completed modules anyway, but keeping the call out
                // makes the read-only intent explicit at the call site.
                navController.whenSettled {
                    navController.navigate(CoachingRoute.LessonPlayer.route)
                }
            },
            onBack = handleBack,
            autoSpeakEnabled = autoSpeak,
            onToggleAutoSpeak = learnVm::toggleAutoSpeak,
            onHome = onFinish,
            quizEnabled = quizEnabled,
        )
    }

    composable(CoachingRoute.LessonPlayer.route) {
        // See LessonContent above — recover instead of blanking when the
        // lesson-player route is restored without its backing module.
        val hasBackingState = remember {
            learnVm.uiState.value is LearnUiState.LessonContent
        }
        if (!hasBackingState) {
            RecoverToModulesHome(navController)
            return@composable
        }
        val uiState by learnVm.uiState.collectAsState()
        // Keep the last-known module while the exit transition animates:
        // onBack/onFinishReading flip the shared VM state BEFORE the pop,
        // and the outgoing player stays composed through the animation —
        // without this cache it composed nothing (white) for the whole
        // transition, and stayed white permanently when the pop was
        // dropped. Same pattern as ModuleDetailScreen's cachedModule.
        val liveModule = (uiState as? LearnUiState.LessonContent)?.module
        var cachedModule by remember { mutableStateOf<LearnModule?>(null) }
        LaunchedEffect(liveModule) {
            if (liveModule != null) cachedModule = liveModule
        }
        val module = liveModule ?: cachedModule
        val autoSpeak by learnVm.autoSpeakEnabled.collectAsState()
        if (module != null) {
            // The module overload reads SDK language internally.
            LessonPlayerScreen(
                module = module,
                // Close the last-card quiz path once the reattempt window has
                // closed (MED-1940 Req 1) — the CTA becomes "More modules"
                // instead of "Start Quiz". Reading the course stays allowed;
                // only the quiz entry is gated. Never-/partly-attempted modules
                // stay open (canTakeQuiz → true → readOnly false).
                readOnly = !learnVm.canTakeQuiz(module),
                onBack = {
                    // Pop back to the existing LessonContent entry. Using
                    // navigate + popUpTo here would push a *new* entry on top
                    // of the original, leaving two LessonContent entries on
                    // the back stack and causing a blank-screen regression
                    // on the next back press. popToOrHome: if LessonContent
                    // is somehow gone, recover to home instead of a dead pop.
                    navController.whenSettled {
                        learnVm.restoreModuleDetail()
                        navController.popToOrHome(CoachingRoute.LessonContent.route)
                    }
                },
                // Quiz-less modules (0 questions) end at the cards-completion
                // screen instead of routing into an empty quiz. Modules WITH a
                // quiz keep the direct onStartQuiz path below — no bridge screen.
                hasQuiz = module.questionCount > 0,
                onFinishCards = {
                    navController.whenSettled {
                        navController.navigate(CoachingRoute.LessonComplete.route)
                    }
                },
                onStartQuiz = {
                    navController.whenSettled {
                        learnVm.startQuiz()
                        navController.navigate(CoachingRoute.QuizQuestion.routeFor(0))
                    }
                },
                onFinishReading = {
                    // "Back to modules" — exit revisit mode all the way
                    // to the modules list. Uses the existing
                    // popToModuleList helper so active state (activeModule,
                    // active questions) is cleared in lockstep with the
                    // back-stack pop.
                    navController.whenSettled {
                        learnVm.popToModuleList()
                        navController.popToHome()
                    }
                },
                onCardShown = { idx: Int -> learnVm.recordCardShown(idx) },
                autoSpeakEnabled = autoSpeak,
                onToggleAutoSpeak = learnVm::toggleAutoSpeak,
                onSpeak = { text, onDone -> learnVm.speakAloud(text, onDone) },
                onStopSpeak = learnVm::stopSpeaking,
                onHome = onFinish,
            )
        } else {
            // No live OR cached module (fresh entry with unexpected state):
            // show a spinner instead of composing nothing — a white screen
            // here reads as a hang and the entry guard above can't cover
            // post-entry flips.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    composable(CoachingRoute.LessonComplete.route) {
        // Cards-completion celebration for quiz-less modules. activeModule is
        // still the module the CHW just finished (finish-cards navigates here
        // without clearing it), so we can offer "Continue" to the next module.
        val completed = learnVm.activeModule
        val next = remember(completed?.moduleFamilyId) {
            completed?.let { learnVm.nextModuleAfter(it) }
        }
        LessonCompleteScreen(
            onContinue = next?.let { nextModule ->
                {
                    // Open the next module fresh, exactly like a tap from the
                    // modules list (selectModule → startLesson → LessonContent).
                    // popToHome first clears the finished module's lesson stack so
                    // we never leave duplicate LessonContent/LessonPlayer entries.
                    navController.whenSettled {
                        navController.popToHome()
                        learnVm.selectModule(nextModule)
                        learnVm.startLesson()
                        navController.navigate(CoachingRoute.LessonContent.route)
                    }
                }
            },
            onBack = {
                navController.whenSettled {
                    learnVm.popToModuleList()
                    navController.popToHome()
                }
            },
        )
    }
}
