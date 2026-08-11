package com.medtroniclabs.microcoaching.sdk.morning

import com.medtroniclabs.microcoaching.data.db.dao.MorningCardCacheDao
import com.medtroniclabs.microcoaching.domain.morning.MorningModuleResolver
import com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns the morning-surface orchestration the facade used to repeat inline: the
 * `resolver.refresh(chwId)` → `store.invalidate()` triad, the "PO has no refreshers" no-op,
 * and the "clear cached cards on CHW switch" rule. The comment around the second invalidate
 * after home-refresh documented a prior path that forgot it and went stale — centralising the
 * order here removes that drift class.
 *
 * Extracted verbatim from `MicroCoachingSDK` (behaviour-preserving). Collaborators are passed as
 * providers so this never forces the facade's lazy service graph at construction; [flush] and
 * [onMorningResolved] are callbacks for the facade-owned telemetry flush and `_latestModule`
 * update the two paths still need.
 */
internal class MorningSurfaceCoordinator(
    private val scope: CoroutineScope,
    private val store: () -> CoachingModuleStore,
    private val resolver: () -> MorningModuleResolver,
    private val morningCardCacheDao: () -> MorningCardCacheDao,
    private val personaPolicy: PersonaPolicy,
    private val flush: suspend () -> Unit,
    private val onMorningResolved: () -> Unit,
) {
    /**
     * Home screen shown. Invalidate immediately so the first compute lands from the local cache;
     * then (unless PO) drop the previous CHW's cached cards on a user switch and re-resolve.
     */
    fun onHomeShown(chwId: String, switchedUser: Boolean) {
        // currentCHWId is now set — kick the store so its first real compute lands immediately
        // from the local cache (it emits an empty list until a chwId is known), before the
        // network refresh returns.
        store().invalidate()
        // PO has no refreshers / morning cards — skip morning resolution. The invalidate above
        // still loads training modules for the PO coaching tab.
        if (personaPolicy.suppressesRefreshers) return
        scope.launch {
            // On a user switch, drop the previous CHW's cached morning cards so a different user
            // never sees them before the re-sync repopulates. (morning_card_cache has no chw_id
            // column; clearing is the small, scalable isolation — the cards are re-derivable.)
            if (switchedUser) {
                morningCardCacheDao().clearAll()
            }
            resolver().refresh(chwId)
            // refresh() rewrote morning_card_cache; recompute AGAIN so the refresher list + featured
            // pick reflect the freshly-resolved cards (the early invalidate ran against the
            // pre-refresh cache, and Room-flow observation alone has proven unreliable here).
            store().invalidate()
        }
    }

    /** Morning routine opened — re-run resolution and update the facade's latest-module pick. */
    fun onMorningOpen(chwId: String) {
        if (personaPolicy.suppressesRefreshers) return // PO has no morning routine
        scope.launch {
            resolver().refresh(chwId)
            onMorningResolved()
            // morning_card_cache changed → re-read it in the store's mapping.
            store().invalidate()
        }
    }

    /**
     * Force a refresh of the morning refreshers (SPICE pull-to-refresh + quiz completion): push
     * pending telemetry so the backend can act on a just-finished quiz, re-resolve, recompute.
     */
    fun refreshAfterQuiz(chwId: String) {
        scope.launch {
            flush()
            resolver().refresh(chwId)
            store().invalidate()
        }
    }

    /** Fire-and-forget [refilter] on the SDK scope (for UI call-sites whose lifecycle ends first). */
    fun refilterAsync(chwId: String) {
        if (chwId.isBlank()) return
        scope.launch { refilter(chwId) }
    }

    /** Re-filter the morning list against freshly-synced progress, then recompute the store. */
    suspend fun refilter(chwId: String) {
        resolver().refilter(chwId)
        // morning_card_cache may have changed → re-read it so refresher list / featured pick
        // reflect freshly-synced progress.
        store().invalidate()
    }
}
