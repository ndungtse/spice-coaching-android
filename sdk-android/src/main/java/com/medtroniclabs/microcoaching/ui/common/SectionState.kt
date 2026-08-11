package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.sync.SyncOutcome

/**
 * The Compose analogue of a React error boundary's *scope*: a failure is a value carried by
 * the subtree that consumes this data, not an exception that escapes upward. (Compose has no
 * way to catch a composition exception from a parent, so isolation has to happen here, at the
 * state edge.)
 *
 * Deliberately has **no `Empty` case** — emptiness is a property of the loaded `data`, decided
 * by the renderer. Conflating "empty" with "failed" is exactly what blanked the Coaching tab
 * when a CHW simply had no assigned modules.
 */
sealed interface SectionState<out T> {

    data object Loading : SectionState<Nothing>

    /** Load failed. [cached] is last-known-good, if any — render it under a stale notice. */
    data class Failed<out T>(val error: CoachingError, val cached: T? = null) : SectionState<T>

    /** Loaded. [stale] marks content served from cache after a failed refresh. */
    data class Ready<out T>(val data: T, val stale: Boolean = false) : SectionState<T>
}

/**
 * Renders the three states, collapsing the `when (state) { Loading → …; Error → …; Ready → … }`
 * block otherwise repeated across every screen.
 *
 * Note the caller owns any scroll container: sub-tabs inside a `PullToRefreshBox` must stay
 * scrollable in *every* state or the pull gesture stops arming, so hoist the scrolling
 * `Column` outside this call rather than wrapping each branch.
 */
@Composable
fun <T> SectionContent(
    state: SectionState<T>,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    density: StatusDensity = StatusDensity.Section,
    loading: @Composable () -> Unit = { CenterProgress() },
    content: @Composable (T) -> Unit,
) {
    Column(modifier) {
        when (state) {
            is SectionState.Loading -> loading()

            is SectionState.Failed -> {
                val cached = state.cached
                if (cached != null) {
                    // Degraded, not blank: last-known-good content under an honest notice.
                    NoticeBanner(stringResource(R.string.common_couldnt_refresh))
                    content(cached)
                } else {
                    ErrorState(error = state.error, onRetry = onRetry, density = density)
                }
            }

            is SectionState.Ready -> {
                if (state.stale) NoticeBanner(stringResource(R.string.common_couldnt_refresh))
                content(state.data)
            }
        }
    }
}

/**
 * The empty-vs-error decision, in one pure place, for every section backed by a Room table
 * that inbound sync fills.
 *
 * Rows win: cached content is always shown (marked stale if the last refresh failed). With no
 * rows, the sync verdict decides — a failure is an error, a success means the backend
 * genuinely published nothing, and [SyncOutcome.Unknown] (nothing attempted yet this process)
 * is still loading rather than a spurious failure.
 */
fun <T> sectionStateFor(
    rows: List<T>,
    outcome: SyncOutcome,
    offline: Boolean,
): SectionState<List<T>> = when {
    rows.isNotEmpty() -> SectionState.Ready(rows, stale = outcome is SyncOutcome.Failed)
    outcome is SyncOutcome.Failed -> SectionState.Failed(CoachingError.from(outcome.kind, offline))
    outcome is SyncOutcome.Succeeded -> SectionState.Ready(emptyList())
    else -> SectionState.Loading
}
