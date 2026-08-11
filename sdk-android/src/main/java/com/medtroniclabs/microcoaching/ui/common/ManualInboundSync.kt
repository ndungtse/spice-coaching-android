package com.medtroniclabs.microcoaching.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.sync.InboundRunSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives a pull-to-refresh that forces a full-catalogue inbound sync.
 *
 * [refresh] triggers [MicroCoachingSDK.triggerFullInboundSync] (which clears the modules
 * watermark so newly-assigned modules are surfaced) and holds [isRefreshing] until *this*
 * run reports back, via [com.medtroniclabs.microcoaching.sync.SyncStatusStore.runs].
 *
 * It deliberately does NOT wait on `lastInboundSyncAt`: the worker advances that timestamp
 * whether or not the individual pulls succeeded, so a refresh that failed still looked like
 * a success. [lastResult] now carries the run's real per-domain verdict, letting callers
 * surface a partial-failure notice.
 */
class ManualInboundSyncState internal constructor(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {
    var isRefreshing by mutableStateOf(false)
        private set

    /** Verdict of the last completed refresh; null if none finished (or it timed out). */
    var lastResult by mutableStateOf<InboundRunSummary?>(null)
        private set

    fun refresh() {
        if (isRefreshing) return
        if (!MicroCoachingSDK.isInitialized()) return

        val sdk = MicroCoachingSDK.getInstance()
        val startedAt = System.currentTimeMillis()
        isRefreshing = true
        sdk.triggerFullInboundSync()

        scope.launch {
            try {
                // `runs` replays the previous run, so match only a run that started at or
                // after this pull — otherwise a stale summary resolves the spinner instantly.
                lastResult = withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                    sdk.syncStatus.runs.first { it.startedAtMs >= startedAt }
                }
            } finally {
                isRefreshing = false
            }
        }
    }

    companion object {
        /** Stop the spinner even if the sync is deferred (e.g. offline) or stalls. */
        internal const val REFRESH_TIMEOUT_MS = 30_000L
    }
}

/** Remembers a [ManualInboundSyncState] scoped to the current composition. */
@Composable
fun rememberManualInboundSyncState(): ManualInboundSyncState {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(appContext) { ManualInboundSyncState(appContext, scope) }
}
