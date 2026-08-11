package com.medtroniclabs.microcoaching.domain.refresher

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.entity.AssignedModuleEntity
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.modules.ModuleSections

/**
 * Greppable logcat tracing for [CoachingModuleStore], extracted verbatim so the store's
 * flow orchestration reads without ~90 lines of string-building interleaved.
 *
 * Logcat: `adb logcat -s ModuleStoreTrace:I AssignedModuleTrace:I`
 */
internal class ModuleStoreTracer {

    @Volatile private var lastFeaturedId: String? = null

    fun traceSections(all: List<LearnModule>, s: ModuleSections) {
        val sourceGap = all.count { it.source == "gap" }
        val sourceFallback = all.count { it.source == "fallback" }
        val sourceNull = all.count { it.source == null }
        Log.i(
            TAG,
            "counts: total=${all.size} refreshers=${s.refreshers.size} training=${s.training.size} " +
                "| source: gap=$sourceGap fallback=$sourceFallback null=$sourceNull",
        )
        Log.i(TAG, "pool: refreshers=${s.refreshers.map { it.moduleFamilyId }}")
        // Visibility: modules that land in no section. A non-selector, non-training
        // module legitimately has no home here. A `content_update` that arrived via
        // the selector is a contract violation — flag it loudly (FIX AT SOURCE) rather
        // than dropping a selector-provided module silently.
        all.filter {
            !it.fromMorningCard &&
                it.moduleType != "initial_training" &&
                it.moduleType != "digital_proficiency"
        }.forEach {
            Log.i(TAG, "noSection: family=${it.moduleFamilyId} type=${it.moduleType} (not selector-surfaced, not training)")
        }
        all.filter { it.fromMorningCard && it.moduleType == "content_update" }.forEach {
            Log.e(TAG, "contractViolation: content_update ${it.moduleFamilyId} arrived as a morning card — FIX AT SOURCE")
        }
    }

    /**
     * Greppable trace of the assigned-module → training-tile route, so a "missing"
     * assigned module can be pinned to its drop point:
     *   A. not in module_cache (unsynced / pruned / titleless-dropped in [CoachingModuleStore.map])
     *   B. in cache but moduleType == content_update (no learnable tile)
     *   C. cached & eligible but the assignment-key filter still excluded it
     *
     * Logcat: `adb logcat -s AssignedModuleTrace:I`
     */
    fun traceAssignedFilter(
        assigned: List<AssignedModuleEntity>,
        allMapped: List<LearnModule>,
        onScreen: List<LearnModule>,
    ) {
        fun short(s: String?) = s?.take(8) ?: "null"
        // 1. Raw rows straight from the DB (assigned_module).
        Log.i(
            TAG_ASSIGNED,
            "assignedFromDb: count=${assigned.size} " +
                "rows=${assigned.map { "mid=${short(it.moduleId)} fam=${short(it.moduleFamilyId)}" }}",
        )
        // 2. The on-screen result AFTER the assignment filter.
        Log.i(
            TAG_ASSIGNED,
            "onScreen(post-filter): count=${onScreen.size} " +
                "families=${onScreen.map { short(it.moduleFamilyId) }}",
        )
        if (assigned.isEmpty()) {
            Log.i(TAG_ASSIGNED, "no assigned rows for this CHW → Training shows the 'no modules assigned' empty state")
            return
        }
        // 3. Per-assigned verdict + drop reason (the route each row took).
        val onScreenKeys = onScreen.flatMap { listOfNotNull(it.moduleId, it.moduleFamilyId) }.toSet()
        for (a in assigned) {
            val cached = allMapped.firstOrNull { it.moduleId == a.moduleId || it.moduleFamilyId == a.moduleFamilyId }
            val shown = a.moduleId in onScreenKeys || (a.moduleFamilyId != null && a.moduleFamilyId in onScreenKeys)
            val verdict = when {
                shown -> "ON_SCREEN"
                cached == null -> "DROPPED(A): not in module_cache (unsynced/pruned/titleless)"
                cached.moduleType == "content_update" ->
                    "DROPPED(B): content_update (no learnable tile on Training)"
                else -> "DROPPED(C): cached & eligible but assignment-key filter excluded it (key mismatch — investigate)"
            }
            Log.i(
                TAG_ASSIGNED,
                "assigned mid=${short(a.moduleId)} fam=${short(a.moduleFamilyId)} " +
                    "title=${cached?.title ?: "<not-cached>"} type=${cached?.moduleType ?: "?"} → $verdict",
            )
        }
    }

    fun traceSelected(pick: LearnModule?, poolSize: Int, skipped: Set<String>) {
        val prev = lastFeaturedId
        val now = pick?.moduleFamilyId
        if (prev != null && prev != now && prev in skipped) {
            Log.i(TAG, "skip→advance: skipped=$prev nextFeatured=${now ?: "null"} remaining=$poolSize")
        }
        lastFeaturedId = now
        Log.i(
            TAG,
            "selectedCard: familyId=${now ?: "null"} source=${pick?.source} " +
                "reason=${if (pick != null) "topNonSkippedWithQuiz" else "allSkippedOrNoQuiz"}",
        )
    }

    private companion object {
        const val TAG = "ModuleStoreTrace"
        const val TAG_ASSIGNED = "AssignedModuleTrace"
    }
}
