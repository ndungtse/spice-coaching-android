package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.LearnModule

/**
 * Horizontal row of `content_update` modules. Tap → opens a read-only
 * preview ([com.medtroniclabs.microcoaching.ui.learn.ModuleDetailScreen] in
 * read-only mode rendering previous/current/rationale/next_action fields).
 *
 * The preview screen wiring uses the existing [LearnViewModel.selectModule]
 * + [com.medtroniclabs.microcoaching.ui.flow.CoachingRoute.LessonContent]
 * route as a stand-in until a dedicated `KnowledgePreview` route lands.
 */
@Composable
fun KnowledgeRow(
    modules: List<LearnModule>,
    onSelect: (LearnModule) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (modules.isEmpty()) return

    Column(modifier = modifier) {
        SectionHeader(title = stringResource(R.string.modules_section_knowledge))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = modules, key = { it.moduleFamilyId }) { module ->
                KnowledgeCard(
                    title = module.title,
                    excerpt = excerptFor(module),
                    thumbnailUrl = module.thumbnailUrl,
                    onClick = { onSelect(module) },
                )
            }
        }
    }
}

private fun excerptFor(module: LearnModule): String = when {
    !module.rationaleForChangeBn.isNullOrBlank() -> module.rationaleForChangeBn
    !module.currentPracticeBn.isNullOrBlank() -> module.currentPracticeBn
    else -> module.body
}
