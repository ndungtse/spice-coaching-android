package com.medtroniclabs.microcoaching.domain.refresher

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.AssignedModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.sortedForDisplay
import com.medtroniclabs.microcoaching.data.localized.readLocalized
import com.medtroniclabs.microcoaching.data.localized.readLocalizedBody
import com.medtroniclabs.microcoaching.data.mapper.decodeIncompleteQuizIds
import com.medtroniclabs.microcoaching.data.repository.GapProfileRepositoryImpl
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.ActionGapLink
import com.medtroniclabs.microcoaching.progress.toReinforceQuestionIds
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.parseInlineQuiz
import com.medtroniclabs.microcoaching.ui.learn.modules.ModuleCategorizer
import com.medtroniclabs.microcoaching.ui.learn.modules.ModuleSections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Single, SDK-owned source of truth for the categorised module lists and the
 * **one** featured refresher pick that every surface shares.
 *
 * Why it lives in the SDK singleton (on `sdkScope`) rather than a ViewModel:
 * the home [com.medtroniclabs.microcoaching.ui.components.MorningCard] is hosted
 * by the SPICE host's Activity, while the modules screen
 * ([com.medtroniclabs.microcoaching.ui.learn.modules.ModulesScreen]) lives in the
 * SDK's `CoachingFlowActivity`. Those are different Android Activities, so the
 * only state both can observe is the process-singleton. Before this store the
 * home card derived its pick from the morning-API subset while the modules
 * screen categorised from the full module list — they could disagree. Now both
 * read [selectedMorningCard] / [refresherModules] from here.
 *
 * Pipeline (all reactive, all on [scope]):
 *  1. [allModules]    — every active module enriched into a [LearnModule]
 *                       (the former `LearnViewModel.mapModules`), recomputed on
 *                       any module / coaching_event change or [invalidate].
 *  2. [refresherModules] / [trainingModules] — [ModuleCategorizer.categorize]
 *                       partitions. Refresher membership is selector-authoritative
 *                       (every morning-card module surfaces; no mastery/completion
 *                       drop — a mastered card just sorts last).
 *  3. [selectedMorningCard] — the first non-skipped refresher that carries a
 *                       quiz; advances to the next as the CHW skips, hides only
 *                       when every refresher is skipped.
 *
 * [com.medtroniclabs.microcoaching.MicroCoachingSDK.morningModules] is left
 * untouched (the chat ScopeClassifier still consumes it); this store is purely
 * additive.
 */
internal class CoachingModuleStore(
    private val database: MicroCoachingDatabase,
    private val scope: CoroutineScope,
    private val chwIdProvider: () -> String?,
    private val langProvider: () -> String,
    private val skippedIds: StateFlow<Set<String>>,
    /**
     * `moduleFamilyId → real action-gap id` published by
     * [com.medtroniclabs.microcoaching.domain.gaps.ondevice.OnDeviceMorningGenerator].
     * Overlaid in [map] so an action/compliance module (e.g. a wrong-referral
     * refresher) is recognised by its true gap even when the cached morning-card
     * row carries only the backend's `module_primary_gap_*` placeholder. Empty
     * until the generator runs.
     */
    private val onDeviceActionGapLinks: StateFlow<Map<String, ActionGapLink>>,
) {

    /** Greppable logcat tracing for this store — see [ModuleStoreTracer]. */
    private val tracer = ModuleStoreTracer()

    /**
     * Enriches [ModuleEntity] rows into [LearnModule]s and owns the in-session status
     * overlay the mapping reads (see [LearnModuleMapper]).
     */
    private val mapper = LearnModuleMapper(database, onDeviceActionGapLinks, langProvider)

    /**
     * Bumped to force a recompute that a Room flow wouldn't otherwise trigger:
     * after a `/morning/cards` pull updates `morning_card_cache`, after a
     * [setInSessionStatus] overlay change, and once `currentCHWId` is known.
     */
    private val invalidateTick = MutableStateFlow(0L)

    /** Force the pipeline to recompute (see [invalidateTick]). */
    fun invalidate() = invalidateTick.update { it + 1 }

    /**
     * Apply an optimistic in-session status for [moduleFamilyId] and recompute,
     * so the UI reflects "in_progress"/"completed" before the DB write
     * propagates through the coaching_event flow. Called by the `LearnViewModel`
     * quiz/lesson flow.
     */
    fun setInSessionStatus(moduleFamilyId: String, status: String) {
        if (mapper.setInSessionStatus(moduleFamilyId, status)) invalidate()
    }

    /**
     * Every active module enriched into a [LearnModule] — the data behind every
     * downstream list. Recomputes on module-cache changes, coaching_event inserts
     * (so quiz-progress counts update live), **morning_card_cache changes**, and
     * [invalidate].
     *
     * Observing `morning_card_cache` is essential: [map] reads it for each module's
     * `source` / `behaviouralGapId` (→ `isActionGap`), so when [OnDeviceMorningGenerator]
     * rewrites the cache the store MUST recompute — otherwise it keeps a stale view
     * where a freshly gap-surfaced (and possibly completed/action-gap) module isn't a
     * refresher, and the featured pick flaps to an unrelated module. (`invalidate` alone
     * can't cover this: it fires before the generator's async write lands.)
     *
     * `Eagerly` so the featured pick stays consistent across the home ⇄ modules
     * Activities even when neither is actively collecting (mirrors
     * `skippedRefresherCount`). Emits an empty list until `currentCHWId` is set.
     */
    val allModules: StateFlow<List<LearnModule>> =
        combine(
            database.moduleDao().getAllActive().map { it.sortedForDisplay() },
            // COUNT(*) re-emits on ANY coaching_event invalidation — including
            // outbound sync's bulk markSynced, which changes neither the count
            // nor the quiz outcomes map() reads. distinctUntilChanged drops
            // those, so a 500-row sync no longer forces a full recompute.
            database.coachingEventDao().getEventCountFlow().distinctUntilChanged(),
            database.morningCardCacheDao().getAllOrdered().distinctUntilChanged(),
            onDeviceActionGapLinks,
            invalidateTick,
        ) { modules, _, _, _, _ -> modules }
            // map() below parses every module's cards+quiz JSON and issues
            // per-module DB queries — conflate so an invalidation burst
            // recomputes once with the latest inputs instead of once per write.
            .conflate()
            .map { modules ->
                val chw = chwIdProvider() ?: return@map emptyList<LearnModule>()
                mapper.map(modules, chw)
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Categorised partitions. Refresher membership is selector-authoritative
     * ([ModuleCategorizer.isRefresher] = a `morning_card_cache` row exists); there is
     * no mastery/completion drop — a fully-mastered selector card still surfaces.
     */
    private val sections: StateFlow<ModuleSections> =
        allModules
            .map { all ->
                val s = ModuleCategorizer.categorize(all)
                tracer.traceSections(all, s)
                s
            }
            .stateIn(scope, SharingStarted.Eagerly, EMPTY_SECTIONS)

    /**
     * Modules assigned to the current CHW (from the `assigned_module` join table,
     * populated by the "assigned" `/sync/modules` call). Drives the training-library
     * filter and the refresher-queue recency ordering below. `flatMapLatest`
     * re-subscribes when `currentCHWId` is set so the set isn't pinned to a
     * null/blank id at construction time.
     *
     * Declared before [refresherModules] because that (Eager) property combines it
     * in — Kotlin runs property initializers in declaration order.
     *
     * Initial value is **`null` = not loaded yet** (distinct from an empty list =
     * loaded, genuinely no assignments). This lets the Training UI show a loading
     * state before the first Room read instead of flashing a premature
     * "No modules assigned yet" for a CHW who actually has assignments — see
     * [trainingAssignmentsLoaded].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val assignedModules: StateFlow<List<AssignedModuleEntity>?> =
        invalidateTick
            .flatMapLatest {
                val chw = chwIdProvider()
                if (chw.isNullOrBlank()) flowOf(emptyList())
                else database.assignedModuleDao().getAssignedForUser(chw)
            }
            // WhileSubscribed(5s) is the floor, but [refresherModules] (Eagerly, the
            // MorningCard pick path) now combines this in to order the queue by
            // assignment recency — so in practice it stays subscribed for the SDK's
            // lifetime. The lightweight `assigned_module WHERE user_id` observer is
            // cheap to hold; the floor still covers any window where nothing collects.
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Active "drill this today" queue — shared by the banner and the list.
     *
     * Ordering (MED-1595): **action-gaps first**, then **most-recently-assigned
     * first**, then the existing clinical order (severity → active gap → status)
     * as a *stable* tiebreak — so a real-world wrong-referral re-drill still leads,
     * newly-assigned refreshers surface next, and equal-recency modules keep their
     * prior severity ordering. `selectedMorningCard` picks the first entry with a
     * quiz, so the featured morning-card / quick-question card follows this order
     * (newest-assigned featured, unless an action-gap re-drill is pending).
     *
     * Combined with [assignedModules] so each refresher carries its assignment date
     * (allModules — the chat/BM25 source — deliberately does not). `assignedModules`
     * is null until first loaded → treated as empty (no dates), so the queue is
     * severity-ordered for the brief moment before assignments load, then re-sorts.
     */
    val refresherModules: StateFlow<List<LearnModule>> =
        combine(sections, assignedModules) { s, assigned ->
            val assignedAtOf = assignedAtLookup(assigned.orEmpty())
            orderRefresherQueue(
                s.refreshers.map { it.copy(assignedAtMs = it.assignedAtMs ?: assignedAtOf(it)) },
            )
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * True once the CHW's assigned-module set has been read at least once
     * ([assignedModules] moved off its `null` not-loaded sentinel). The Training UI
     * gates its "No modules assigned yet" empty state on this so the message is
     * never shown before assignments have loaded.
     */
    val trainingAssignmentsLoaded: StateFlow<Boolean> =
        assignedModules.map { it != null }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * The Training screen's list: exactly the modules assigned to the current CHW
     * that have a learnable tile — i.e. ANY type except `content_update` (which has
     * no quiz/lesson and is excluded from Training/Refresher everywhere). This means
     * an **assigned `refresher`** shows here too, even when the morning-card selector
     * didn't surface it. A module matches by EITHER its `moduleId` (the backend's
     * assignment key, always present) or its `moduleFamilyId` (when the payload
     * carries it), so the filter still works if a future dedicated endpoint returns
     * only `module_id`.
     *
     * **No assignments → empty list** (the UI then shows a "No modules assigned yet"
     * message). We deliberately do NOT fall back to the full catalogue: a CHW with no
     * assignment should see none. Until the set has loaded ([trainingAssignmentsLoaded]
     * is false) the UI shows a loading state rather than a premature empty message.
     * The BM25 / chat-retrieval path reads `allModules` directly and is unaffected.
     */
    val trainingModules: StateFlow<List<LearnModule>> =
        combine(assignedModules, allModules) { assigned, allMapped ->
            val onScreen = if (assigned.isNullOrEmpty()) {
                emptyList()
            } else {
                val assignedModuleIds = assigned.map { it.moduleId }.toSet()
                val assignedFamilyIds = assigned.mapNotNull { it.moduleFamilyId }.toSet()
                // Assignment-date lookup — feeds LearnModule.assignedAtMs so
                // QuizRetryGate can measure the reattempt window from the assignment
                // date (MED-1529 Req 1) AND so the list can order newest-first below.
                val assignedAtOf = assignedAtLookup(assigned)
                allMapped
                    .filter {
                        (it.moduleId in assignedModuleIds || it.moduleFamilyId in assignedFamilyIds) &&
                            it.moduleType != "content_update"
                    }
                    .map { it.copy(assignedAtMs = assignedAtOf(it)) }
                    // Most-recently-assigned first (MED-1595); modules with no
                    // assignment date sort last. Stable — equal/undated modules keep
                    // the allModules clinical order (severity → gap → status).
                    .sortedWith(compareByDescending { it.assignedAtMs ?: Long.MIN_VALUE })
            }
            tracer.traceAssignedFilter(assigned.orEmpty(), allMapped, onScreen)
            onScreen
        }
            // WhileSubscribed: modules-screen-only branch (NOT in the MorningCard
            // pick path — that's refresherModules/selectedMorningCard, kept Eager).
            // Its upstream allModules stays Eager, so re-subscribe is cheap.
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Count of the current user's **assigned modules that are not yet complete**,
     * using the shared [LearnModule.isProgressComplete] definition (passed, OR
     * every quiz question attempted at least once). Drives the incomplete-module
     * reminder popup (MED-1940 Req 2). Using the same predicate as the All Modules
     * progress ring guarantees the reminder and the ring agree — a fully-attempted
     * (even if failed) module is "complete" in both, so it is no longer counted as
     * incomplete here (it still shows 100% on the ring — the QA mismatch was that the
     * reminder disagreed with that 100%). Derived from
     * [trainingModules] so it inherits the same assigned-only, non-`content_update`
     * scope; pair it with [trainingAssignmentsLoaded] to avoid acting on the initial
     * empty (not-yet-loaded) emission.
     */
    val incompleteAssignedCount: StateFlow<Int> =
        trainingModules
            .map { modules -> modules.count { !it.isProgressComplete } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Home-tile module indicator (MED-I629): true while any assigned Training
     * module is still incomplete (shared [LearnModule.isProgressComplete]
     * predicate). Kept `Eagerly` so the SPICE home tile never flashes a stale
     * `false` before a collector attaches — it maps the already-`Eagerly`
     * [allModules] output (a filter over the slim list, no blob parse).
     */
    val hasIncompleteTrainingModules: StateFlow<Boolean> =
        trainingModules
            .map { modules -> modules.any { !it.isProgressComplete } }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * The single featured pick shared by the home [MorningCard] and the modules
     * [QuizRefresherCard]: the first refresher whose family the CHW hasn't
     * skipped this session and that carries a quiz. Skipping advances to the
     * next; null only when every refresher is skipped (or none qualify).
     */
    val selectedMorningCard: StateFlow<LearnModule?> =
        combine(refresherModules, skippedIds) { refs, skip ->
            val pick = selectFeatured(refs, skip)
            tracer.traceSelected(pick, refs.size, skip)
            pick
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Build a `LearnModule → assigned_at (epoch millis)` lookup from the CHW's
     * [AssignedModuleEntity] rows. Matches by `moduleId` (the backend's unit of
     * assignment) first, then `moduleFamilyId`. Returns null for unassigned
     * modules (or an assignment carrying no `assigned_at`). Shared by the refresher
     * queue and the training list so both order by assignment recency consistently.
     */
    private fun assignedAtLookup(assigned: List<AssignedModuleEntity>): (LearnModule) -> Long? {
        val byModuleId = assigned.associate { it.moduleId to it.assignedAt }
        val byFamilyId = assigned
            .filter { it.moduleFamilyId != null }
            .associate { it.moduleFamilyId to it.assignedAt }
        return { m -> byModuleId[m.moduleId] ?: byFamilyId[m.moduleFamilyId] }
    }

    companion object {
        private val EMPTY_SECTIONS = ModuleSections(emptyList(), emptyList(), emptyList())
    }
}

/**
 * The refresher-queue ordering rule (MED-1595), extracted as a pure function so
 * it's unit-testable without the store's coroutine/Room harness. Assumes each
 * module's [LearnModule.assignedAtMs] is already populated.
 *
 * Order: **action-gaps first** (a real-world wrong-referral re-drill must lead),
 * then **most-recently-assigned first**, with a **stable** sort so equal-recency
 * (or undated) modules keep the caller's prior clinical order (severity → active
 * gap → status). [selectFeatured] takes the first entry with a quiz, so the
 * featured morning-card / quick-question card follows this same order.
 */
internal fun orderRefresherQueue(refreshers: List<LearnModule>): List<LearnModule> =
    refreshers.sortedWith(
        compareBy<LearnModule> { if (it.isActionGap) 0 else 1 }
            .thenByDescending { it.assignedAtMs ?: Long.MIN_VALUE },
    )

/**
 * The featured-pick rule, extracted as a pure function so it is unit-testable
 * without constructing the store (the test source set has no coroutine/Room
 * harness). The first refresher the CHW hasn't skipped this session that also
 * carries a quiz — mirrors the former `ModulesScreen.featured`. Skipping a
 * family advances to the next; null when every refresher is skipped.
 */
internal fun selectFeatured(refreshers: List<LearnModule>, skipped: Set<String>): LearnModule? =
    refreshers.firstOrNull { it.moduleFamilyId !in skipped && it.questionCount > 0 }

/**
 * Whether a module should be treated as a live action/compliance gap (and thus
 * bypass the mastery/completed drops), extracted as a pure function so the
 * re-drill gate is unit-testable without a DB.
 *
 * Semantics:
 *  - **On-device-linked gap** ([link] non-null — e.g. a wrong referral resolved to
 *    `referral_location_upazila`): the gap is "cleared" only once the CHW re-passes
 *    EVERY quiz question *after* the mistake. So it stays active while any question
 *    is not in [passedSinceMistake] (questions whose latest correct attempt is newer
 *    than [ActionGapLink.lastWrongReferralAt]). Fail-open when the mistake time is
 *    unknown or the module ships no quiz to re-pass — never silently hide a live
 *    mistake.
 *  - **No link**: plain catalogue check (a gap bound directly to the module's morning
 *    card, e.g. once `/sync/triggers` ships the binding) — ungated, prior behaviour.
 */
internal fun isActionGapStillActive(
    link: ActionGapLink?,
    cardGapId: String?,
    actionGapIds: Set<String>,
    questionIds: Set<String>,
    passedSinceMistake: Set<String>,
): Boolean = when {
    link != null -> when {
        link.lastWrongReferralAt == null -> true   // mistake time unknown → keep showing
        questionIds.isEmpty() -> true              // no quiz to re-pass → can't be drilled away
        else -> questionIds.any { it !in passedSinceMistake } // active until ALL passed since
    }
    else -> cardGapId != null && cardGapId in actionGapIds
}

