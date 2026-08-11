package com.medtroniclabs.microcoaching.sdk.morning

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Owns the per-CHW "skipped refresher" set — the family-ids the CHW dismissed this home
 * session (Skip button / swipe-away). Persisted per CHW so it survives restart; a skip is
 * cleared only when the CHW actually completes that refresher (see `RefresherContent`), and
 * [retainActive] prunes ids no longer in the active pool.
 *
 * Extracted verbatim from `MicroCoachingSDK` (behaviour-preserving). [prefs] is a provider,
 * not the instance, so this never forces the facade's lazy `chwPrefs` at construction (the
 * facade's init-order landmine).
 */
internal class SkippedRefresherStore(
    scope: CoroutineScope,
    private val prefs: () -> SharedPreferences,
    private val currentChwId: () -> String?,
) {
    private val _familyIds = MutableStateFlow<Set<String>>(emptySet())

    /** The skipped-refresher family-id set (used by `QuickLearnViewModel` to hide skipped banners). */
    val familyIds: StateFlow<Set<String>> = _familyIds.asStateFlow()

    /**
     * Number of refreshers the CHW skipped this home session. The SPICE host observes this to
     * render a count badge on the "Coaching" home-grid tile.
     */
    val count: StateFlow<Int> =
        _familyIds
            .map { it.size }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Record that the CHW skipped a refresher (Skip button or swipe-away). Persisted per CHW. */
    fun markSkipped(moduleFamilyId: String) {
        if (moduleFamilyId.isBlank()) return
        _familyIds.update { it + moduleFamilyId }
        persist()
    }

    /** Drop a refresher from the skipped set once the CHW has completed it. */
    fun clearSkipped(moduleFamilyId: String) {
        if (moduleFamilyId.isBlank()) return
        if (moduleFamilyId !in _familyIds.value) return
        _familyIds.update { it - moduleFamilyId }
        persist()
    }

    /**
     * Keep only the skipped ids that are still **active refreshers**. Called with the current
     * refresher pool so the badge counts unique, still-pending skipped refreshers — completed/
     * mastered ones that left the pool stop counting.
     */
    fun retainActive(activeFamilyIds: Set<String>) {
        val before = _familyIds.value
        val after = before intersect activeFamilyIds
        if (after != before) {
            _familyIds.value = after
            persist()
        }
    }

    /** Load [chwId]'s persisted skipped set into the in-memory flow (empty if none/undecodable). */
    fun load(chwId: String) {
        val json = prefs().getString(key(chwId), null)
        _familyIds.value = if (json == null) {
            emptySet()
        } else {
            runCatching { Json.decodeFromString<Set<String>>(json) }.getOrDefault(emptySet())
        }
    }

    /** Persist the skipped set for the current CHW (survives restart; scoped per user). */
    private fun persist() {
        val chwId = currentChwId() ?: return
        prefs().edit()
            .putString(key(chwId), Json.encodeToString(_familyIds.value))
            .apply()
    }

    private fun key(chwId: String) = "skipped_refreshers_$chwId"
}
