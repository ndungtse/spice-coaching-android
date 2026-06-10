package com.medtroniclabs.microcoaching.ui.learn.modules.components

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.LearnModule

private const val TAG = "RefresherList"

/**
 * Vertical list of refresher tiles driven by the morning-card cache.
 *
 * Caller ([ModulesScreen]) is responsible for filtering by [LearnModule.source];
 * this composable only sorts gap-first within the supplied list.
 *
 * @param modules Refresher modules (already filtered by source != null).
 * @param onSelect Invoked when the CHW taps a tile; opens [RefresherBottomSheet].
 */
@Composable
fun RefresherList(
    modules: List<LearnModule>,
    onSelect: (LearnModule) -> Unit,
    modifier: Modifier = Modifier,
) {
    val refreshers = modules
        .sortedWith(compareBy { if (it.source == "gap") 0 else 1 })

    Log.d(TAG, "input=${modules.size} " +
        "(gap=${refreshers.count { it.source == "gap" }} " +
        "fallback=${refreshers.count { it.source == "fallback" }})")

    if (refreshers.isEmpty()) return

    Column(modifier = modifier) {
        SectionHeader(title = stringResource(R.string.modules_section_refreshers))
        refreshers.forEach { module ->
            // Prefer the "to-reinforce" count (excludes already-mastered questions)
            // computed in LearnViewModel.observeModules. Falls back to total when not yet computed.
            val count = module.wrongQuestionCount ?: module.inlineQuestions?.size ?: 0
            RefresherTile(
                category = stringResource(categoryLabelFor(module)),
                title = module.title,
                meta = pluralStringResource(R.plurals.refresher_meta_quiz, count, count),
                isCritical = module.clinicalDomain.equals("emergency", ignoreCase = true),
                isGap = module.source == "gap",
                thumbnailUrl = module.thumbnailUrl,
                onClick = { onSelect(module) },
            )
        }
    }
}

private fun categoryLabelFor(module: LearnModule): Int = when {
    module.clinicalDomain.equals("spice_digital", ignoreCase = true) ->
        R.string.category_spice_app
    module.inlineQuestions != null && module.inlineQuestions.isNotEmpty() ->
        R.string.category_clinical_assessment
    else -> R.string.category_learning
}
