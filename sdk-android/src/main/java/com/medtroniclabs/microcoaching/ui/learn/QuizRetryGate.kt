package com.medtroniclabs.microcoaching.ui.learn

import java.util.concurrent.TimeUnit

/**
 * **Temporary retry-window gate (MED-1529 Req 1). Deliberately isolated so it can be
 * deleted in one step when product moves on from this rule.**
 *
 * Closes the "Try Again" CTA on [QuizResultScreen] — via
 * [LearnViewModel.canRetryActiveQuiz] — once a module's reattempt window has elapsed.
 * [isRetryWindowClosed] returns `true` only when all three hold:
 *
 *  1. the module has a quiz (`inlineQuestions` non-empty),
 *  2. the CHW has attempted every question at least once, cumulatively
 *     ([LearnModule.attemptedQuestionCount] ≥ [LearnModule.inlineQuestions].size), and
 *  3. at least `windowDays` have passed since [LearnModule.assignedAtMs].
 *
 * Anything else — no quiz, no `assignedAtMs`, a partly-attempted quiz — leaves the gate
 * open, so a first attempt is never blocked. The window length is admin-configurable,
 * synced under [KEY_QUIZ_REATTEMPT_VALIDITY_DAYS] and read by [resolveValidityDays],
 * falling back to [QUIZ_RETRY_WINDOW_DAYS].
 *
 * The gate does **not** read [LearnModule.status]: completion is orthogonal to retry
 * eligibility, so a passed module inside the window stays re-quizzable. Status reaches
 * rule 2 only indirectly, because `CoachingModuleStore` treats a `completedAt`-stamped
 * completion as implying every question was attempted.
 *
 * To remove the rule: delete this file and `QuizRetryGateTest`, make
 * `canRetryActiveQuiz` return `true`, and drop [LearnModule.assignedAtMs] (set in
 * `CoachingModuleStore.trainingModules`) and [MicroCoachingSDK.quizReattemptValidityDays].
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
     * @return `true` when the CTA should be locked — the window is closed once the
     *   elapsed time reaches `windowDays`, not after it.
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
