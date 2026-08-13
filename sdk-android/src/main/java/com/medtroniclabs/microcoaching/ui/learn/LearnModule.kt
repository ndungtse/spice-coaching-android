package com.medtroniclabs.microcoaching.ui.learn

/**
 * UI model for a learning module (backed by [ModuleEntity]).
 *
 * @param moduleFamilyId Stable cross-version family key matching [ModuleEntity.moduleFamilyId].
 * @param title Display title from bangla_card.title.
 * @param body Module description from bangla_card.body.
 * @param clinicalDomain Used for domain colour/chip display.
 * @param warningSigns List of Bangla warning sign strings.
 * @param nextStep CHW action text in Bangla.
 * @param referralDestination Referral facility name in Bangla, or null.
 * @param quizIds IDs of quiz questions linked to this module.
 * @param status Learning path status: assigned | in_progress | completed.
 */
data class LearnModule(
    val moduleFamilyId: String,
    val title: String,
    val body: String,
    val clinicalDomain: String,
    /**
     * Content-domain taxonomy from `ModuleEntity.contentDomain`: "clinical" |
     * "digital" | "operational". Drives the SK/PO content-domain tag on Learning
     * Library & Practice Zone cards (Med-I617). Distinct from [clinicalDomain] (a
     * clinical *topic* code) and [moduleType]. Null → rendered as "clinical", the
     * documented default (see [com.medtroniclabs.microcoaching.ui.learn.modules.components.ContentDomainTag]).
     */
    val contentDomain: String? = null,
    val warningSigns: List<String> = emptyList(),
    val nextStep: String = "",
    val referralDestination: String? = null,
    val quizIds: List<String> = emptyList(),
    val status: String = "assigned",
    /**
     * Quiz questions inlined with the module bundle (v3.3 module_cache origin).
     *
     * When non-null, [com.medtroniclabs.microcoaching.ui.learn.LearnViewModel.startQuiz]
     * uses these directly instead of querying [quizIds] against the legacy
     * `quiz_question_cache`. Modules from the v3.3 pipeline ship cards and quiz
     * together — there's no separate quiz table.
     */
    val inlineQuestions: List<QuizQuestion>? = null,
    /** Version-specific module UUID (Module.id). Null until backend exposes it in the sync bundle. */
    val moduleId: String? = null,
    /** Module content version at the time this module was synced. */
    val moduleVersion: Int? = null,
    /** card_family_id of the first (walkthrough) card. Used for card_shown telemetry. */
    val cardFamilyId: String? = null,
    /**
     * Backend `module_type` enum value: "refresher" | "content_update" |
     * "digital_proficiency". Drives section assignment on the v0.3.2 modules
     * screen and determines whether the tap path goes directly to quiz
     * (refresher) or through the lesson-content flow.
     */
    val moduleType: String = "refresher",
    /** Estimated minutes for non-refresher modules. Drives the Training-card meta line. */
    val estimatedMinutes: Int? = null,
    /** content_update fields — populated only when [moduleType] == "content_update". */
    val previousPracticeBn: String? = null,
    val currentPracticeBn: String? = null,
    val rationaleForChangeBn: String? = null,
    val nextActionBn: String? = null,
    /**
     * Gap ID carried from the morning-cards response when this module was surfaced because of
     * a behavioural gap. Forwarded into [TelemetryEventPayload.payloadJson] on
     * `module_quiz_attempted` events so the backend can resolve the gap state.
     * Null for modules opened outside the gap-driven morning surface.
     */
    val behaviouralGapId: String? = null,
    /**
     * Surface source from the morning-cards response: "gap" | "fallback" | null.
     * Used to display the GAP badge on the refresher tile.
     */
    val source: String? = null,
    /**
     * A single `module_quiz_question.id` this refresher targets, carried from the
     * morning-cards response (`morning_card_cache.quiz_id`, non-null only when
     * `source == "quiz"`). When set, the module is always shown as a **Quiz**
     * (even before the quiz blob is hydrated) and the refresher drill runs ONLY
     * this one question — see
     * [com.medtroniclabs.microcoaching.ui.learn.modules.QuickLearnViewModel.primeRefresherQuiz].
     * A stale/missing id (not present in the module's current quiz) falls back to
     * the normal to-reinforce set, same as every other refresher.
     */
    val targetQuizId: String? = null,
    /**
     * True when the morning-card selector emitted this module — it has a row in
     * `morning_card_cache` (backend `GET /morning/cards` OR the on-device
     * [com.medtroniclabs.microcoaching.domain.gaps.ondevice.OnDeviceMorningGenerator]).
     * This is the SOLE gate for refresher membership (see
     * [com.medtroniclabs.microcoaching.ui.learn.modules.ModuleCategorizer]): if a
     * selector surfaced it, it lands on the refresher list regardless of
     * completion/mastery.
     */
    val fromMorningCard: Boolean = false,
    /**
     * Default severity of the behavioural gap that surfaced this module:
     * "low" | "moderate" | "high" (from `behavioural_gap_cache.severity_default`).
     * Resolved in [com.medtroniclabs.microcoaching.ui.learn.LearnViewModel.mapModules]
     * via [behaviouralGapId]. Null when the module isn't gap-sourced — the
     * refresher tile then shows no severity chip.
     */
    val severity: String? = null,
    /**
     * True when this module is surfaced by an **action/compliance gap** — a
     * behavioural gap that carries a `detection_rule` and is driven by a
     * real-world mistake (e.g. a wrong facility referral), not by quiz history.
     * Such refreshers are exempt from the "all questions mastered → drop" filter
     * so a fresh field mistake re-engages the CHW even after a past perfect quiz.
     */
    val isActionGap: Boolean = false,
    /**
     * Raw `cards_json` from [ModuleEntity] — forwarded so [LessonPlayerScreen] and
     * [ModuleDetailScreen] can parse the card list without a DB round-trip.
     * Defaults to `"[]"` when not available.
     *
     * **Lazy-load note:** for LIST rendering this is intentionally left as `"[]"`
     * (see [CoachingModuleStore]'s slim mapping) — the heavy card blob is loaded
     * only when a module is opened (detail/lesson/quiz re-read the entity by id).
     * List tiles must use [cardCount]/[questionCount], never parse this field.
     */
    val cardsJson: String = "[]",
    /**
     * Number of lesson cards, always populated (even in the slim list mapping,
     * from the cheap `ModuleEntity.cardCount` count getter). Use this for list
     * tiles instead of parsing [cardsJson].
     */
    val cardCount: Int = 0,
    /**
     * Number of quiz questions, always populated (even in the slim list mapping,
     * from the cheap `ModuleEntity.questionCount` count getter). Use this for
     * list tiles instead of reading [inlineQuestions]?.size (which is null in the
     * slim mapping).
     */
    val questionCount: Int = 0,
    /**
     * Actual quiz score from the CHW's last attempt (0.0–1.0).
     * Seeded from [ChwModuleCompletionEntity.latestQuizScore] on every
     * `observeModules` call. Null when the module has never been attempted.
     * Drives the progress bar in [TrainingCard] instead of the coarse 3-step
     * status mapping.
     */
    val quizScorePct: Float? = null,
    /**
     * Number of questions whose **latest local attempt was wrong** — the CHW's
     * "local gap". A never-attempted module reports 0 here (not the question
     * total): the name means *answered wrong*, nothing else. Drives the
     * Refresher-section membership in [com.medtroniclabs.microcoaching.ui.learn.modules.ModuleCategorizer].
     *
     * NOTE: this is NOT the count to display on the tile — for that, use
     * [reinforceQuestionCount] (the drill size, which also counts never-answered
     * questions). See [LearnViewModel] mapModules for how the two are derived.
     *
     * Null when the count hasn't been computed yet (legacy callers).
     */
    val wrongQuestionCount: Int? = null,
    /**
     * Number of questions still "to reinforce" — either never answered or whose
     * latest answer was wrong. This equals the length of the refresher drill that
     * [com.medtroniclabs.microcoaching.ui.learn.modules.QuickLearnViewModel.primeRefresherQuiz]
     * presents, so it's the count shown on the refresher tile in [RefresherList].
     *
     * Null when not yet computed; callers fall back to [inlineQuestions]?.size.
     * Recomputed on every [LearnViewModel.observeModules] emission (incl. after
     * any `coaching_event` change) so the tile updates immediately.
     */
    val reinforceQuestionCount: Int? = null,
    /**
     * Number of distinct quiz questions the CHW has attempted at least once,
     * regardless of whether they got them right or wrong. Drives the training-card
     * progress bar per PM direction (DM.txt, 2026-06): the visual progress is
     * attempted / total — every attempt counts toward 100%, even all-wrong attempts.
     *
     * **Visual only.** Not linked to backend `chw_module_completion.completedAt`,
     * which still tracks the passing-attempt semantic.
     *
     * Null when the count hasn't been computed yet (legacy callers).
     */
    val attemptedQuestionCount: Int? = null,
    /**
     * Millis-since-epoch when this module was **assigned** to the current user —
     * the backend's `assigned_module_ids[].assigned_at`, carried on
     * [com.medtroniclabs.microcoaching.data.db.entity.AssignedModuleEntity.assignedAt]
     * and joined onto the module in
     * [com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore.trainingModules].
     * Null for modules reached outside the assigned-training list, or when the
     * backend didn't send an assignment date.
     *
     * **Used only by [com.medtroniclabs.microcoaching.ui.learn.QuizRetryGate].**
     * The gate closes the "Start Quiz" CTA once the reattempt window
     * (Assignment Date + configured days; default 7, admin-configurable via
     * config sync — MED-1529 Req 1) has elapsed AND the CHW has attempted every
     * question at least once. Within the window CHWs can keep retrying;
     * first-time attempts are always allowed (a never-attempted module is not a
     * retry). Null → gate stays open (fail-safe: never lock out on missing data).
     *
     * **To remove the retry-window feature**: delete [QuizRetryGate], remove
     * the gate call in [com.medtroniclabs.microcoaching.ui.learn.LearnViewModel]'s
     * `canRetryActiveQuiz`, and drop this field + its assignment in
     * `CoachingModuleStore.trainingModules`.
     */
    val assignedAtMs: Long? = null,
    /**
     * Cached presigned URL for the module thumbnail, carried from
     * [com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity.thumbnailUrl].
     * Null when the module has no thumbnail or sync hasn't resolved one yet —
     * the tile / detail header simply omits the image in that case.
     */
    val thumbnailUrl: String? = null,
    /**
     * Distinct cards of this module the CHW has read, from `module_card_viewed`
     * telemetry. Populated **only for modules with no quiz** — those have no other
     * progress signal, so reading is the progress. A module with questions is
     * measured by [attemptedQuestionCount] and leaves this null.
     *
     * Cards recorded before card ids were sent don't count, so a CHW mid-way
     * through such a module reads as further back than they are. It corrects
     * itself as they keep reading.
     */
    val viewedCardCount: Int? = null,
) {
    /**
     * Whether this module counts as **complete for progress/reminder purposes**:
     * the CHW has passed it ([status] == "completed"), OR has attempted every quiz
     * question at least once (pass or fail), OR — for a module with no quiz — has
     * read every card.
     *
     * The card clause keeps the ring and this flag agreeing. Without it a CHW who
     * read every card but backed out before the completion screen would see a full
     * ring on a module still counted as outstanding.
     *
     * This is the single definition shared by the All Modules progress ring
     * ([com.medtroniclabs.microcoaching.ui.learn.modules.components.progressFractionFor])
     * and the incomplete-module reminder count
     * ([com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore.incompleteAssignedCount])
     * so the two can never disagree. It is deliberately the "attempted-all"
     * semantic, distinct from the passing-only [status] == "completed", and it
     * equals exactly the condition under which the ring shows 100% (so the ring's
     * visible behaviour is unchanged).
     */
    val isProgressComplete: Boolean
        get() = status == "completed" ||
            (questionCount > 0 && (attemptedQuestionCount ?: 0) >= questionCount) ||
            (questionCount == 0 && cardCount > 0 && (viewedCardCount ?: 0) >= cardCount)
}

/**
 * UI model for a single quiz question (backed by [QuizQuestionCacheEntity]).
 *
 * Replaces [StubQuiz].
 *
 * @param id Stable question ID from the backend.
 * @param questionText The question shown to the CHW (Bangla).
 * @param answers List of 3–4 Bangla answer option strings.
 * @param correctIndex Zero-based index of the correct answer.
 * @param explanation Bangla explanation shown after the CHW answers.
 * @param pointValue Score points for a correct answer.
 * @param optionOriginalIndices Display→original option-index mapping produced when a
 *   fresh attempt shuffles this question's options (see [withShuffledOptions]).
 *   `optionOriginalIndices[displayIndex]` is the option's index in the authored/backend
 *   order. Empty for unshuffled questions ⇒ identity mapping. Used by
 *   [canonicalOptionIndex] so telemetry keeps reporting the backend's option coordinates
 *   even though the CHW sees a shuffled order.
 */
data class QuizQuestion(
    val id: String,
    val questionText: String,
    val answers: List<String>,
    val correctIndex: Int,
    val explanation: String = "",
    val caseSetup: String = "",
    val pointValue: Int = 10,
    val optionOriginalIndices: List<Int> = emptyList(),
)
