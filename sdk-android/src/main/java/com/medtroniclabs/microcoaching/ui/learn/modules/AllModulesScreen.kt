package com.medtroniclabs.microcoaching.ui.learn.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.modules.components.ModuleTile
import com.medtroniclabs.microcoaching.ui.learn.modules.components.ModuleTileVariant
import com.medtroniclabs.microcoaching.ui.learn.modules.components.moduleTileColumns
import com.medtroniclabs.microcoaching.ui.learn.modules.components.progressFractionFor
import com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme
import com.medtroniclabs.microcoaching.ui.theme.SurfaceBackground

/** Module-type tokens carried in the [AllModules] route argument. */
const val ALL_MODULES_TYPE_TRAINING = "training"
const val ALL_MODULES_TYPE_KNOWLEDGE = "knowledge"

/**
 * Full-screen, scrollable grid of every module of one [moduleType] — reached
 * from the "See all" link on the modules screen's Training section. Built
 * type-generic so the Knowledge section can reuse it later.
 *
 * The catalogue can be large, so this uses a real [LazyVerticalGrid] (unlike
 * the inline `TrainingGrid`, which is bounded to the first few modules and
 * lives inside a scrolling Column). Column count tracks the same breakpoints
 * via [moduleGridColumns].
 *
 * Tapping a tile runs the exact same selection path as the modules screen
 * (wired by [com.medtroniclabs.microcoaching.ui.flow.CoachingNavGraph]), so the
 * detail/quiz flow and back-navigation are unchanged.
 */
@Composable
fun AllModulesScreen(
    modules: List<LearnModule>,
    moduleType: String,
    onSelect: (LearnModule) -> Unit,
    onBack: () -> Unit,
    onHome: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sections = ModuleCategorizer.categorize(modules)
    val items = when (moduleType) {
        ALL_MODULES_TYPE_KNOWLEDGE -> sections.knowledge
        else -> sections.training
    }
    val titleRes = when (moduleType) {
        ALL_MODULES_TYPE_KNOWLEDGE -> R.string.modules_section_knowledge
        else -> R.string.modules_section_training
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBackground),
    ) {
        SdkScreenHeader(
            title = stringResource(titleRes),
            onBack = onBack,
            onHome = onHome,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val columns = moduleTileColumns(maxWidth)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxWidth(),
                // Outer screen margin; inter-tile spacing is the spacedBy() below.
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = items, key = { it.moduleFamilyId }) { module ->
                    ModuleTile(
                        title = module.title,
                        subtitle = stringResource(
                            R.string.training_meta_minutes_questions,
                            module.estimatedMinutes ?: 5,
                            module.inlineQuestions?.size ?: 0,
                        ),
                        variant = ModuleTileVariant.TRAINING,
                        progress = progressFractionFor(module),
                        onClick = { onSelect(module) },
                        thumbnailUrl = module.thumbnailUrl,
                    )
                }
            }
        }
    }
}


@Preview(showBackground = true, widthDp = 412)
@Composable
fun AllModulesScreenPreview() {
    // moduleType must be "initial_training" so ModuleCategorizer keeps these in
    // the training partition — otherwise the grid renders empty.
    val modules = listOf(
        LearnModule("1", "Hypertension Screening", "How to use a digital BP monitor.", "hypertension", status = "in_progress", moduleType = "initial_training", estimatedMinutes = 5),
        LearnModule("2", "Maternal Danger Signs", "Identifying pre-eclampsia.", "maternal_health", status = "assigned", moduleType = "initial_training", estimatedMinutes = 6),
        LearnModule("3", "Diabetes Referral", "When to refer.", "diabetes", status = "completed", moduleType = "initial_training", estimatedMinutes = 4),
    )
    MicroCoachingTheme {
        AllModulesScreen(
            modules = modules,
            moduleType = ALL_MODULES_TYPE_TRAINING,
            onSelect = {},
            onBack = {},
        )
    }
}