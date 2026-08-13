package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.LearnModule

/**
 * Responsive grid of training-type modules. Replaces the former horizontal
 * `TrainingRow`: lays cards out in a [ModuleGrid] whose column count adapts to
 * screen width (min 2 — see [moduleGridColumns]), each card filling its cell.
 *
 * Shows only the first [maxItems] modules; when there are more and [onSeeAll]
 * is supplied, a "See all" link in the [SectionHeader] opens the full
 * `AllModulesScreen`.
 *
 * Progress bar shows [LearnModule.quizScorePct] (the actual last quiz score,
 * 0.0–1.0) when available; falls back to 1.0 for completed and 0.0 otherwise.
 */
@Composable
fun TrainingGrid(
    modules: List<LearnModule>,
    onSelect: (LearnModule) -> Unit,
    onSeeAll: (() -> Unit)? = null,
    maxItems: Int = 6,
    modifier: Modifier = Modifier,
    // Null keeps the default "Training" section title; set to give the grid a
    // caller-specific heading (e.g. a sub-tab title) without duplicating this composable.
    title: String? = null,
    // moduleFamilyId of the single card that should render the NEW pill (see
    // TrainingCard.showNewBadge); null (the default) renders no NEW pill anywhere.
    newModuleFamilyId: String? = null,
) {
    if (modules.isEmpty()) return

    val showSeeAll = onSeeAll != null && modules.size > maxItems

    Column(modifier = modifier) {
        SectionHeader(
            title = title ?: stringResource(R.string.modules_section_training),
            seeAllLabel = if (showSeeAll) stringResource(R.string.modules_see_all) else null,
            onSeeAllClick = if (showSeeAll) onSeeAll else null,
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            ModuleGrid(
                items = modules.take(maxItems),
                columns = moduleGridColumns(maxWidth),
            ) { module ->
                TrainingCard(
                    title = module.title,
                    meta = stringResource(
                        R.string.training_meta_minutes_questions,
                        module.estimatedMinutes ?: 5,
                        module.questionCount,
                    ),
                    progressFraction = progressFractionFor(module),
                    thumbnailUrl = module.thumbnailUrl,
                    showNewBadge = module.moduleFamilyId == newModuleFamilyId,
                    contentDomain = module.contentDomain,
                    onClick = { onSelect(module) },
                )
            }
        }
    }
}

/**
 * Resolves the progress bar fraction (0.0–1.0) for a training card.
 *
 * Per new direction (2026-06): training-card progress is a **visual-only**
 * signal showing `attempted / total` — every attempt counts toward 100%, even
 * all-wrong attempts. Not linked to backend `chw_module_completion.completedAt`,
 * which still tracks the passing-attempt semantic and feeds [LearnModule.status].
 *
 * A module with **no quiz** is measured by reading instead: distinct cards read
 * over total cards, so it climbs through 40%, 60% … rather than jumping 0% → 100%.
 *
 * Edge cases:
 * - No quiz and no cards either → 0.0; there is nothing to measure.
 * - [LearnModule.attemptedQuestionCount] / [LearnModule.viewedCardCount] null
 *   (not applicable to this module) → 0.0.
 */
internal fun progressFractionFor(module: LearnModule): Float {
    // Full ring (100%) exactly when the module counts as complete — passed, every
    // question attempted, or every card read. Shared with the reminder count via
    // [LearnModule.isProgressComplete] so the ring and the "incomplete modules"
    // reminder can never disagree.
    if (module.isProgressComplete) return 1f

    val totalQuestions = module.questionCount
    if (totalQuestions > 0) {
        val attempted = module.attemptedQuestionCount ?: 0
        return (attempted.toFloat() / totalQuestions).coerceIn(0f, 1f)
    }

    val totalCards = module.cardCount
    if (totalCards == 0) return 0f
    val read = module.viewedCardCount ?: 0
    return (read.toFloat() / totalCards).coerceIn(0f, 1f)
}
