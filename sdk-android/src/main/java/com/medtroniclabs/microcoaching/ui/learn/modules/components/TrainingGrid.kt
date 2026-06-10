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
import android.util.Log

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
) {
    if (modules.isEmpty()) return

    val showSeeAll = onSeeAll != null && modules.size > maxItems

    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.modules_section_training),
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
                        module.inlineQuestions?.size ?: 0,
                    ),
                    progressFraction = progressFractionFor(module),
                    thumbnailUrl = module.thumbnailUrl,
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
 * Edge cases:
 * - Module with zero quiz questions → fall back to status (`completed` → 1.0, else 0.0).
 * - [LearnModule.attemptedQuestionCount] null (legacy callers / not yet wired) → 0.0.
 *
 * Old formula (correct/total — kept here in case product reverts to score-based
 * progress; see DM.txt for the rationale on the switch):
 * ```kotlin
 * // module.quizScorePct ?: if (module.status == "completed") 1f else 0f
 * ```
 */
internal fun progressFractionFor(module: LearnModule): Float {
    // log module.title, module.quizScorePct, module.inlineQuestions?.size, module.status and attemptedQuestionCount
    Log.d("TrainingGrid", "module.title: ${module.title}, module.quizScorePct: ${module.quizScorePct}, module.inlineQuestions?.size: ${module.inlineQuestions?.size}, module.status: ${module.status}, module.attemptedQuestionCount: ${module.attemptedQuestionCount}")

    // Completed wins, regardless of local attempt counts. Backfilled-from-backend
    // completions carry status="completed" but may have zero local
    // module_quiz_attempted rows on a fresh device / after a Room wipe /
    // after a destructive sync — so attemptedQuestionCount can legitimately
    // be 0 here. Honour the cached authoritative completion before applying
    // the attempted/total formula, otherwise the bar reads 0% for a passed module.
    if (module.status == "completed") return 1f

    val total = module.inlineQuestions?.size ?: 0
    if (total == 0) return 0f
    val attempted = module.attemptedQuestionCount ?: 0
    return (attempted.toFloat() / total).coerceIn(0f, 1f)
}
