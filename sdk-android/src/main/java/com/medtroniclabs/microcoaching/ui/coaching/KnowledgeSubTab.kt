package com.medtroniclabs.microcoaching.ui.coaching

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.EmptyState
import com.medtroniclabs.microcoaching.ui.common.ErrorState
import com.medtroniclabs.microcoaching.ui.common.NoticeBanner
import com.medtroniclabs.microcoaching.ui.common.SectionState
import com.medtroniclabs.microcoaching.ui.learn.KnowledgeDocument
import com.medtroniclabs.microcoaching.ui.learn.modules.components.KnowledgeDocGrid

/**
 * Knowledge sub-tab body of the Coaching tab. Renders the same document grid as
 * `AllModulesScreen`'s knowledge mode ([KnowledgeDocGrid]) — docs are already rank-ordered by
 * `KnowledgeDocController` — reserving bottom clearance for the chat FAB; taps reuse the
 * existing download/preview flow via [onDocSelect].
 *
 * [state] carries the `PUBLISHED_DOCS` sync outcome, so an empty grid distinguishes "the
 * refresh failed" (error + retry) from "nothing is assigned to this CHW" (a calm empty
 * state), and a failed refresh never hides documents already cached on the device.
 *
 * Every branch is its own scroll host — `KnowledgeDocGrid` is a `LazyVerticalGrid` (which
 * scrolls itself and must NOT be nested in a scrolling Column), the text states use a
 * scrolling Column. `CoachingTab` wraps this in a `PullToRefreshBox`, whose gesture only arms
 * over a scrollable child.
 */
@Composable
fun KnowledgeSubTab(
    state: SectionState<List<KnowledgeDocument>>,
    onDocSelect: (KnowledgeDocument) -> Unit,
    cachedDocIds: Set<String>,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    @Composable
    fun Grid(documents: List<KnowledgeDocument>, isLoading: Boolean) {
        KnowledgeDocGrid(
            documents = documents,
            onDocSelect = onDocSelect,
            cachedDocIds = cachedDocIds,
            isLoading = isLoading,
            modifier = modifier.fillMaxSize(),
            bottomPadding = 80.dp,
        )
    }

    @Composable
    fun TextState(content: @Composable () -> Unit) {
        Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            content()
        }
    }

    when (state) {
        // The grid renders its own shimmer placeholders while the first load is in flight.
        is SectionState.Loading -> Grid(documents = emptyList(), isLoading = true)

        is SectionState.Failed -> {
            val cached = state.cached
            if (cached.isNullOrEmpty()) {
                TextState { ErrorState(error = state.error, onRetry = onRetry) }
            } else {
                // Degraded, not blank: keep showing what's already on the device.
                Column(modifier = Modifier.fillMaxSize()) {
                    NoticeBanner(stringResource(R.string.common_couldnt_refresh))
                    Grid(documents = cached, isLoading = false)
                }
            }
        }

        is SectionState.Ready -> {
            if (state.data.isEmpty()) {
                TextState { EmptyState(stringResource(R.string.knowledge_empty_none)) }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (state.stale) NoticeBanner(stringResource(R.string.common_couldnt_refresh))
                    Grid(documents = state.data, isLoading = false)
                }
            }
        }
    }
}
