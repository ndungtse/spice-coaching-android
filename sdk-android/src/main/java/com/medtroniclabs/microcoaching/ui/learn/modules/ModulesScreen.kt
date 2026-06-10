package com.medtroniclabs.microcoaching.ui.learn.modules

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.modules.components.KnowledgeRow
import com.medtroniclabs.microcoaching.ui.learn.modules.components.QuizRefresherCard
import com.medtroniclabs.microcoaching.ui.learn.modules.components.RefresherList
import com.medtroniclabs.microcoaching.ui.learn.modules.components.TrainingGrid

/**
 * Orchestrator composable for the v0.3.2 modules screen. Renders, top to
 * bottom: [QuickLearnCard] · [RefresherList] · [TrainingGrid] · [KnowledgeRow].
 *
 * Sheet launches are hoisted out — the caller (`CoachingNavGraph`) holds the
 * activity reference needed for `FragmentManager` and passes lambdas down.
 * This keeps `ModulesScreen` free of any Compose context-walking and
 * eliminates the "FragmentManager is null" failure mode reported in pilot.
 *
 * @param modules Full module list from [LearnViewModel].
 * @param chwId Forwarded into the [QuickLearnViewModel] factory.
 * @param onShowQuickLearn Open the Quick learn bottom sheet (no-op when no
 *   morning module is surfaced; the banner hides automatically in that case).
 * @param onShowRefresherQuiz Open the Refresher quiz bottom sheet. The caller
 *   must have already primed the LearnViewModel via `selectModuleForQuiz`.
 * @param onTrainingSelect Tap target for `digital_proficiency` cards — host
 *   routes through the existing `ModuleReady → LessonContent → Quiz` chain.
 * @param onKnowledgeSelect Tap target for `content_update` cards.
 * @param onRefresherStart Fired *before* [onShowRefresherQuiz] so the host
 *   can prime `LearnViewModel.selectModuleForQuiz(module)`.
 * @param onSeeAllTraining Opens the full training-module grid (`AllModulesScreen`).
 *   Only surfaced by [TrainingGrid] when there are more than its display cap.
 */
@Composable
fun ModulesScreen(
    modules: List<LearnModule>,
    chwId: String,
    onShowQuickLearn: (moduleFamilyId: String?) -> Unit,
    onShowRefresherQuiz: () -> Unit,
    onTrainingSelect: (LearnModule) -> Unit,
    onKnowledgeSelect: (LearnModule) -> Unit,
    onRefresherStart: (LearnModule) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    onSeeAllTraining: () -> Unit = {},
) {
    val context = LocalContext.current
    val quickLearnViewModel: QuickLearnViewModel = viewModel(
        factory = QuickLearnViewModel.factory(context.applicationContext, chwId),
    )
    val quickQuestion by quickLearnViewModel.quickQuestion.collectAsState()

    // Partition modules into the three sections — exhaustive, by state.
    // Rules and invariants are owned by [ModuleCategorizer] (and pinned by
    // ModuleCategorizerTest) so a future change to the rule has to be
    // conscious — silently breaking the partition fails the build.
    val (refreshers, knowledge, training) = ModuleCategorizer.categorize(modules)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // Slim indeterminate progress bar — fades in while modules are still
        // loading, fades out the moment they arrive. Lives on the same surface
        // as the modules list so there's no route-transition flicker between
        // a separate Loading screen and ModulesScreen.
        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        quickQuestion?.let { qq ->
            QuizRefresherCard(
                questionText = qq.question.questionText,
                participantCount = 12, // Placeholder — backend count endpoint deferred.
                xpReward = qq.question.pointValue,
                // Pass the exact module the banner picked so the sheet opens
                // the same one — otherwise the sheet defaults to the top
                // morning module which may have been fully mastered, giving
                // an empty refresher list.
                onClick = { onShowQuickLearn(qq.module.moduleFamilyId) },
            )
        }

        RefresherList(
            modules = refreshers,
            onSelect = { module ->
                onRefresherStart(module)
                onShowRefresherQuiz()
            },
        )

        TrainingGrid(
            modules = training,
            onSelect = onTrainingSelect,
            onSeeAll = onSeeAllTraining,
        )

        KnowledgeRow(
            modules = knowledge,
            onSelect = onKnowledgeSelect,
        )

        Spacer(Modifier.height(80.dp)) // Breathing room for FABs
    }
}
