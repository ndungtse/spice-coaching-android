package com.medtroniclabs.microcoaching.ui.learn

import java.util.concurrent.TimeUnit

/**
 * **Temporary retry-window gate. Intentionally isolated so it's easy to
 * delete in one step when product moves on from this rule.**
 *
 * ## What this does
 *
 * Implements MED-1529 Req 1: closes the quiz-retry CTA once the reattempt
 * validity window — **Module Assignment Date + configured days** — has
 * elapsed AND the CHW has attempted every question at least once. While
 * closed, the "Try Again" button on [QuizResultScreen] is hidden (via
 * [LearnViewModel.canRetryActiveQuiz]); the read-only "Back to modules"
 * path already used for `status == "completed"` modules covers the rest.
 *
 * The window length is **admin-configurable** — synced from the backend
 * under [KEY_QUIZ_REATTEMPT_VALIDITY_DAYS] and resolved via
 * [resolveValidityDays] — falling back to [QUIZ_RETRY_WINDOW_DAYS] (7)
 * when unset/invalid. Within the window CHWs can keep retrying regardless
 * of how many attempts they've made. First-time attempts are always
 * allowed — a never-attempted module isn't a "retry".
 *
 * ## Why it's structured this way
 *
 * The whole feature lives behind a single boolean expression
 * ([isRetryWindowClosed]) used at one call site
 * ([LearnViewModel.canRetryActiveQuiz]). The two pieces of state outside
 * this file are [LearnModule.assignedAtMs] — the backend's per-assignment
 * `assigned_at`, joined onto the module in
 * [com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore.trainingModules]
 * — and the synced window days on [MicroCoachingSDK.quizReattemptValidityDays].
 * That tight surface is deliberate so the rule can be removed cleanly later.
 *
 * ## Rule semantics
 *
 * The gate closes when **all three** are true:
 *
 *  1. The module has a quiz (`inlineQuestions` non-empty).
 *  2. The CHW has attempted every question on this module's quiz at least
 *     once (cumulative across sessions —
 *     [LearnModule.attemptedQuestionCount] ≥
 *     [LearnModule.inlineQuestions].size).
 *  3. The module was assigned more than `windowDays` days ago
 *     ([LearnModule.assignedAtMs]).
 *
 * Modules with no quiz, no `assignedAtMs` (e.g. reached outside the
 * assigned-training list), no attempts at all, or incomplete attempts →
 * the gate stays open (CTA visible). The day boundary is **exclusive** —
 * at exactly the `windowDays` mark the window has just closed.
 *
 * **The gate does NOT read [LearnModule.status]** — completion is
 * orthogonal to retry eligibility. A passed module within the window stays
 * re-quizzable. Status only enters the picture through the cache-first
 * `attemptedCount` derivation in `CoachingModuleStore`: a
 * `completedAt`-stamped completion implies all questions were attempted,
 * which feeds rule #2 above.
 *
 * ## How to remove
 *
 * When this rule is no longer wanted:
 *
 *  1. Delete this file and `QuizRetryGateTest`.
 *  2. In [LearnViewModel] make `canRetryActiveQuiz` return `true`
 *     unconditionally (or delete it with the gate).
 *  3. In [LearnModule] remove the `assignedAtMs` field + its assignment in
 *     `CoachingModuleStore.trainingModules`.
 *  4. Optionally drop [MicroCoachingSDK.quizReattemptValidityDays].
 */
internal object QuizRetryGate {

    /**
     * Default reattempt window in days, used when the backend hasn't synced a
     * value (or synced an invalid one). Admins configure the real value on the
     * web app; it arrives via config sync under
     * [KEY_QUIZ_REATTEMPT_VALIDITY_DAYS] and is resolved by [resolveValidityDays].
     */
    const val QUIZ_RETRY_WINDOW_DAYS: Long = 7L

    /**
     * `config_threshold` key carrying the admin-configured reattempt window in
     * days (see the `GET /sync/config` `thresholds` map). Synced into the
     * `config_threshold` table by
     * [com.medtroniclabs.microcoaching.sync.SyncApi.pullConfig] like every other
     * threshold; read back via [resolveValidityDays].
     */
    const val KEY_QUIZ_REATTEMPT_VALIDITY_DAYS: String = "quiz_reattempt_validity_days"

    /**
     * Parse the synced [KEY_QUIZ_REATTEMPT_VALIDITY_DAYS] raw value into a usable
     * window.
     *
     * A value of **0 is valid and meaningful** (MED-1940 Req 1): it configures a
     * zero-day reattempt window — the CHW still gets their mandatory first
     * attempt (the never-/partly-attempted guard in [isRetryWindowClosed] keeps
     * the window open until every question has been attempted), but no reattempt
     * once the quiz has been fully attempted.
     *
     * Falls back to [QUIZ_RETRY_WINDOW_DAYS] only when the value is missing,
     * non-numeric, or negative — those are genuinely meaningless and must never
     * lock a CHW out on bad config.
     */
    fun resolveValidityDays(rawValue: String?): Long =
        rawValue?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: QUIZ_RETRY_WINDOW_DAYS

    /**
     * @param module The module the CHW is about to (re)open.
     * @param windowDays Reattempt window in days — pass the synced value from
     *   [resolveValidityDays]; defaults to [QUIZ_RETRY_WINDOW_DAYS] for callers
     *   (and tests) that don't thread config through.
     * @param nowMs Current millis-since-epoch. Parameterised so unit tests
     *   can pin a deterministic clock without injecting a Clock interface.
     * @return `true` when the CTA should be locked (window closed),
     *   `false` otherwise.
     */
    fun isRetryWindowClosed(
        module: LearnModule,
        windowDays: Long = QUIZ_RETRY_WINDOW_DAYS,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val total = module.inlineQuestions?.size ?: 0
        if (total == 0) return false
        val attempted = module.attemptedQuestionCount ?: 0
        if (attempted < total) return false
        val assignedAtMs = module.assignedAtMs ?: return false
        val windowMs = TimeUnit.DAYS.toMillis(windowDays)
        return (nowMs - assignedAtMs) >= windowMs
    }
}
