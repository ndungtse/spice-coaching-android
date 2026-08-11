package com.medtroniclabs.microcoaching.domain.refresher

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.localized.readLocalized
import com.medtroniclabs.microcoaching.data.localized.readLocalizedBody
import com.medtroniclabs.microcoaching.data.mapper.decodeIncompleteQuizIds
import com.medtroniclabs.microcoaching.data.repository.GapProfileRepositoryImpl
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.ActionGapLink
import com.medtroniclabs.microcoaching.progress.toReinforceQuestionIds
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.parseInlineQuiz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Enriches cached [ModuleEntity] rows into fully-populated [LearnModule]s: per-module JSON
 * parsing, DB progress reads (completions / partials / per-question outcomes), morning-card
 * + action-gap resolution, severity/status ranking, and the list-slim copy.
 *
 * Extracted verbatim from [CoachingModuleStore] (which now keeps only the Flow wiring). This
 * class owns the in-session status overlay it reads, so `setInSessionStatus` writes here and
 * the store just re-triggers its pipeline.
 */
internal class LearnModuleMapper(
    private val database: MicroCoachingDatabase,
    private val onDeviceActionGapLinks: StateFlow<Map<String, ActionGapLink>>,
    private val langProvider: () -> String,
) {
    private val gapRepo = GapProfileRepositoryImpl(database.chwGapProfileDao())

    /**
     * In-session module-status overlay (moduleFamilyId → "in_progress"/"completed"), moved
     * here from `LearnViewModel` so the mapping that reads it and the quiz flow that writes it
     * share one map. The persisted truth still lives in `chw_module_completion`; this is the
     * optimistic overlay applied on top.
     */
    private val statusByModule = ConcurrentHashMap<String, String>()

    /** CHW the [statusByModule] overlay belongs to — cleared on switch (see [map]). */
    @Volatile
    private var statusOverlayChwId: String? = null

    /**
     * Apply an optimistic in-session status for [moduleFamilyId]. Returns true when the value
     * actually changed, so the store knows to recompute (the DB write propagates later via the
     * coaching_event flow).
     */
    fun setInSessionStatus(moduleFamilyId: String, status: String): Boolean {
        if (statusByModule[moduleFamilyId] == status) return false
        statusByModule[moduleFamilyId] = status
        return true
    }

    suspend fun map(
        modules: List<ModuleEntity>,
        chwId: String,
    ): List<LearnModule> = withContext(Dispatchers.Default) {
        // Reset the in-session status overlay when the CHW changes — it is
        // keyed per module family, not per user, so a stale overlay leaked one
        // user's optimistic "completed"/"in_progress" states into the next
        // login on shared devices.
        if (statusOverlayChwId != chwId) {
            statusByModule.clear()
            statusOverlayChwId = chwId
        }
        // Runs off the main thread: per-module JSON parsing (cards + quiz) and
        // several DB reads.
        val gapEntries = gapRepo.getAllForChw(chwId)
        val activeGapKeys = gapEntries.filter { it.gapActive }.map { it.behaviouralGapId }.toSet()

        // Morning-card enrichment (source / gap id for refresher list + telemetry).
        val morningCardsByModuleId = database.morningCardCacheDao()
            .getAllOrderedOnce()
            .associateBy { it.moduleId }

        // gapId → severity_default ("low"|"moderate"|"high") for the refresher
        // tile's severity chip. Resolved once per emission.
        val gapSeverityById = database.behaviouralGapDao()
            .getAllActiveOnce()
            .associate { it.gapId to it.severityDefault }

        // Action/compliance gaps (those carrying a detection_rule) drive refreshers
        // from real-world mistakes, so their modules are exempt from the
        // fully-mastered drop. Resolved once per emission.
        val actionGapIds = database.behaviouralGapDao()
            .getActiveWithRules()
            .map { it.gapId }
            .toSet()

        // Family → real action-gap id resolved on-device (see [onDeviceActionGapLinks]).
        // Lets a module's true action gap win over the backend's placeholder gap.
        val actionGapLinks = onDeviceActionGapLinks.value

        // Seed progress from persisted chw_module_completion + partial-completion
        // rows. In-session statusByModule always takes priority; DB rows fill the
        // gaps on first open after a restart. Partials are server-authoritative
        // for cross-device recovery.
        val completions = database.chwModuleCompletionDao()
            .getAllForChw(chwId)
            .associateBy { it.moduleFamilyId }
        val partials = database.chwModulePartialCompletionDao()
            .getAllForChw(chwId)
            .associateBy { it.moduleFamilyId }

        val mapped = modules.mapNotNull { entity ->
            val completion = completions[entity.moduleFamilyId]
            val partial = partials[entity.moduleFamilyId]
            // "completed" is sticky: `completedAt` is stamped once the CHW passes
            // and carried forward across later fails. A completion row with
            // `completedAt == null` means attempted-but-never-passed → in_progress.
            val persistedStatus = when {
                completion?.completedAt != null -> "completed"
                completion != null || partial != null -> "in_progress"
                else -> "assigned"
            }
            // Prefer in-session value; fall back to DB-derived status.
            val status = statusByModule[entity.moduleFamilyId] ?: persistedStatus
            // Sync in-memory map so future recomputes are consistent.
            if (!statusByModule.containsKey(entity.moduleFamilyId) && persistedStatus != "assigned") {
                statusByModule[entity.moduleFamilyId] = persistedStatus
            }

            val card = morningCardsByModuleId[entity.moduleId]
            // Prefer the on-device-resolved REAL action gap over the cached card's
            // gap. The backend's morning card for a referral module often carries
            // only its module_primary_gap_* placeholder (empty detection rule),
            // which would make a completed referral look like an ordinary mastered
            // module and get dropped at the "completed" check. The resolved link
            // (e.g. referral_location_upazila) keeps isActionGap true and surfaces
            // the real gap's (higher) severity.
            val actionGapLink = actionGapLinks[entity.moduleFamilyId]
            val resolvedGapId = actionGapLink?.gapId ?: card?.behaviouralGapId

            // Build the module shell first so we can use its inlineQuestions to
            // compute both wrongQuestionCount and the merged quizScorePct.
            val shell = entity.toLearnModule(
                status = status,
                gapCode = null,
                behaviouralGapId = resolvedGapId,
                source = card?.source ?: resolvedGapId?.let { "gap" },
                quizScorePct = null,
            ) ?: run {
                // Fail loud: a module with no resolvable title is a content/data bug.
                // We can't render a titleless card, but it must never vanish silently —
                // FIX AT SOURCE (module content), don't paper over it.
                Log.e(
                    TAG,
                    "dropTitleless: module=${entity.moduleId} family=${entity.moduleFamilyId} " +
                        "fromMorningCard=${card != null} — no resolvable title, FIX AT SOURCE",
                )
                return@mapNotNull null
            }

            val totalQ = shell.inlineQuestions?.size ?: 0
            val questionIds = shell.inlineQuestions?.map { it.id }?.toSet().orEmpty()

            // Local attempt history, fetched ONCE per module: the latest-attempt-
            // per-question outcome from coaching_event, bounded by [questionIds].
            val dao = database.coachingEventDao()
            val localCorrectIds: Set<String> = if (totalQ == 0) emptySet()
                else dao.getLatestCorrectQuestionIds(chwId, entity.moduleFamilyId).toSet().intersect(questionIds)
            val localWrongIds: Set<String> = if (totalQ == 0) emptySet()
                else dao.getLatestWrongQuestionIds(chwId, entity.moduleFamilyId).toSet().intersect(questionIds)

            // "To reinforce" = questions not yet mastered, INCLUDING never-attempted
            // ones — the right notion for the progress bar. Computed via the pure
            // overload so it adds NO extra per-module DB queries.
            val toReinforceCount: Int = if (totalQ == 0) {
                0
            } else {
                toReinforceQuestionIds(
                    allQuestionIds = questionIds,
                    localCorrect = localCorrectIds,
                    localWrong = localWrongIds,
                    serverIncomplete = partial?.decodeIncompleteQuizIds()?.toSet(),
                ).size
            }

            // wrongQuestionCount = questions whose LATEST local attempt was wrong —
            // the CHW's local gap. A never-attempted module reports 0.
            val wrongCount = localWrongIds.size

            // Distinct quiz questions ever attempted (right OR wrong). Cache-first:
            // a backend completion (`completedAt != null`) clamps to "all attempted"
            // even when the local coaching_event log is empty (fresh device / wipe).
            val attemptedCount: Int = when {
                totalQ == 0 -> 0
                completion?.completedAt != null -> totalQ
                else -> (localCorrectIds + localWrongIds).size
            }

            // Progress prioritization:
            //   passed-completion > partial > failed-completion > assigned
            val quizScore: Float? = when {
                completion?.completedAt != null -> 1f
                partial != null && totalQ > 0 ->
                    ((totalQ - toReinforceCount).coerceAtLeast(0)).toFloat() / totalQ
                completion != null -> completion.latestQuizScore
                else -> null
            }

            // Action/compliance gaps re-engage the CHW on a real-world mistake, but a
            // wrong-referral refresher must be CLEARED BY A PASSING RE-DRILL (see
            // [isActionGapStillActive]). Only query the per-question "passed since the
            // mistake" set when it can actually matter — an on-device-linked gap with a
            // known mistake time and a quiz to re-pass.
            val lastWrong = actionGapLink?.lastWrongReferralAt
            val passedSinceMistake: Set<String> =
                if (lastWrong != null && questionIds.isNotEmpty()) {
                    dao.getLatestCorrectQuestionIdsSince(chwId, entity.moduleFamilyId, lastWrong)
                        .toSet()
                } else {
                    emptySet()
                }
            val isActionGap = isActionGapStillActive(
                link = actionGapLink,
                cardGapId = card?.behaviouralGapId,
                actionGapIds = actionGapIds,
                questionIds = questionIds,
                passedSinceMistake = passedSinceMistake,
            )

            shell.copy(
                quizScorePct = quizScore,
                wrongQuestionCount = wrongCount,
                reinforceQuestionCount = toReinforceCount,
                attemptedQuestionCount = attemptedCount,
                severity = shell.behaviouralGapId?.let { gapSeverityById[it] },
                // Backend "quiz"-source cards target ONE question; carried so the
                // tile renders as a Quiz and the drill runs only that question.
                targetQuizId = card?.quizId,
                isActionGap = isActionGap,
                // Selector provenance: a morning_card_cache row (backend or on-device)
                // exists for this exact module version → it's a refresher, full stop.
                fromMorningCard = card != null,
                // Lazy-load (MEM-08): this eagerly-held list must NOT retain the heavy
                // card/quiz blobs. cardCount/questionCount are already populated on
                // `shell`, so list tiles render fully; detail/lesson/quiz re-read the
                // blobs by id on tap (LearnViewModel.hydrate). selectFeatured and all
                // list count-reads use questionCount/cardCount, never these fields.
                cardsJson = "[]",
                inlineQuestions = null,
            )
        }.sortedWith(
            compareBy(
                // Severity takes the top, when present: high → moderate → low, then
                // any module with no resolved severity. Drives the featured pick
                // (selectFeatured takes the first refresher with a quiz), so a
                // higher-severity gap surfaces ahead of a lower one.
                {
                    when (it.severity?.lowercase()) {
                        "high" -> 0
                        "moderate" -> 1
                        "low" -> 2
                        else -> 3 // unknown / no severity → after every ranked one
                    }
                },
                // Then the prior ordering: active gaps first, …
                { if (it.behaviouralGapId != null && activeGapKeys.contains(it.behaviouralGapId)) 0 else 1 },
                // … then by progress status.
                {
                    when (it.status) {
                        "in_progress" -> 0
                        "assigned" -> 1
                        "completed" -> 2
                        else -> 3
                    }
                },
            ),
        )

        mapped
    }

    private fun ModuleEntity.toLearnModule(
        status: String,
        gapCode: String?,
        behaviouralGapId: String? = null,
        source: String? = null,
        quizScorePct: Float? = null,
    ): LearnModule? {
        val l = langProvider()
        val cardsArray = try {
            Json.parseToJsonElement(cardsJson).jsonArray
        } catch (_: Exception) { null }
        val firstCard = cardsArray?.firstOrNull()?.jsonObject
        val cardCount = cardsArray?.size ?: 0

        // Prefer the module-level title — the first-card title is only a fallback
        // for legacy modules that don't carry their own title.
        val moduleTitle = title.forLang(l)
        val title = moduleTitle
            ?: firstCard?.readLocalized("title")?.forLang(l)
            ?: return null
        // `body_*` in the v3.5+ schema may be a JSON array of rich-content blocks,
        // not a string; fall through to module-level description in that case.
        val body = firstCard?.readLocalizedBody("body", l)
            ?: firstCard?.readLocalizedBody("body", "bn")
            ?: description.forLang(l).orEmpty()
        val nextStep = firstCard?.readLocalized("next_action")?.forLang(l).orEmpty()

        val inlineQuestions = parseInlineQuiz(quizJson, l)
        val quizIds = inlineQuestions.map { it.id }

        val firstCardFamilyId = firstCard?.primitiveOrNull("card_family_id")

        // content_update fields — present only on content_update modules.
        val previousPracticeBn = firstCard?.readLocalized("previous_practice")?.bn
        val currentPracticeBn = firstCard?.readLocalized("current_practice")?.bn
        val rationaleForChangeBn = firstCard?.readLocalized("rationale_for_change")?.bn
        val nextActionBn = firstCard?.readLocalized("next_action")?.bn

        return LearnModule(
            moduleFamilyId = moduleFamilyId,
            title = title,
            body = body,
            clinicalDomain = gapCode ?: domain ?: "general",
            contentDomain = contentDomain,
            warningSigns = emptyList(),
            nextStep = nextStep,
            referralDestination = null,
            quizIds = quizIds,
            status = status,
            inlineQuestions = inlineQuestions.takeIf { it.isNotEmpty() },
            moduleId = moduleId,
            moduleVersion = version,
            cardFamilyId = firstCardFamilyId,
            moduleType = moduleType,
            estimatedMinutes = estimatedMinutes,
            previousPracticeBn = previousPracticeBn,
            currentPracticeBn = currentPracticeBn,
            rationaleForChangeBn = rationaleForChangeBn,
            nextActionBn = nextActionBn,
            behaviouralGapId = behaviouralGapId,
            source = source,
            cardsJson = cardsJson,
            // Counts are always populated (survive the list-slim copy in map()),
            // so list tiles never need to parse the heavy blobs.
            cardCount = cardCount,
            questionCount = inlineQuestions.size,
            quizScorePct = quizScorePct,
            thumbnailUrl = thumbnailUrl,
        )
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is JsonNull) null else content

    private fun JsonObject.primitiveOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNullSafe()

    private companion object {
        private const val TAG = "ModuleStoreTrace"
    }
}
