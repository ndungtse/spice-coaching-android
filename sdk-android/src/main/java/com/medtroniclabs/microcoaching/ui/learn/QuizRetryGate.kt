package com.medtroniclabs.microcoaching.ui.learn

import java.util.concurrent.TimeUnit

/**
 * **Temporary retry-window gate. Intentionally isolated so it's easy to
 * delete in one step when product moves on from this rule.**
 *
 * ## What this does
 *
 * Closes the "Start Quiz" CTA on [LessonPlayerScreen] once a module is
 * older than [QUIZ_RETRY_WINDOW_DAYS] **from its publication date** AND
 * the CHW has attempted every question at least once. While the gate is
 * closed, the lesson player renders its existing read-only path
 * (last-card CTA becomes "Back to modules" — same flow already used for
 * `status == "completed"` modules), so no UI changes are required.
 *
 * Within the retry window (module age < 7 days) CHWs can keep retrying
 * regardless of how many attempts they've made. First-time attempts are
 * always allowed — a never-attempted module isn't a "retry".
 *
 * ## Why it's structured this way
 *
 * The whole feature lives behind a single boolean expression
 * ([isRetryWindowClosed]) used at one call site
 * ([com.medtroniclabs.microcoaching.ui.flow.CoachingNavGraph]'s `readOnly`
 * computation). The only piece of state outside this file is
 * [LearnModule.publishedAtMs], populated in
 * [LearnViewModel.mapModules] from `ModuleEntity.publishedAtIso`. That
 * tight surface is deliberate so the rule can be removed cleanly later
 * (see "How to remove" below).
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
 *  3. The module was published more than [QUIZ_RETRY_WINDOW_DAYS] days ago
 *     ([LearnModule.publishedAtMs], sourced from
 *     `ModuleEntity.publishedAtIso`).
 *
 * Modules with no quiz, no `publishedAtMs`, no attempts at all, or
 * incomplete attempts → the gate stays open (CTA visible). The 7-day
 * boundary is **exclusive** — at exactly the 7-day mark the window has
 * just closed.
 *
 * **The gate does NOT read [LearnModule.status]** — completion is
 * orthogonal to retry eligibility. A passed module within the 7-day
 * window stays re-quizzable (per PM, DM 2026-06: "if the completion rate
 * 100% can chw be able to do a quiz again if it's still in the first 7
 * days? Yes"). Status only enters the picture through the cache-first
 * `attemptedCount` derivation in `LearnViewModel.mapModules`: a
 * `completedAt`-stamped completion implies all questions were attempted,
 * which feeds rule #2 above.
 *
 * ## How to remove
 *
 * When this rule is no longer wanted:
 *
 *  1. Delete this file.
 *  2. In
 *     [com.medtroniclabs.microcoaching.ui.flow.CoachingNavGraph]
 *     remove the `|| QuizRetryGate.isRetryWindowClosed(module)` clause
 *     from the `readOnly` computation — leave the rest of that block
 *     as-is.
 *  3. In [LearnModule] remove the `publishedAtMs` field.
 *  4. In [LearnViewModel.mapModules] remove the `publishedAtMs` local
 *     + its assignment in `shell.copy(...)`. The `parseIsoMillis` import
 *     can come out at the same time.
 *  5. Delete `QuizRetryGateTest`.
 *
 * No other touchpoints. The existing "completed" path that drives the
 * read-only "Back to modules" CTA stays intact; only the second OR clause
 * needs to come out.
 */
internal object QuizRetryGate {

    /**
     * Days after publication during which the CHW may retry the quiz.
     * After this window the "Start Quiz" CTA is locked (provided every
     * question has been attempted at least once). Set in the spec by
     * product (DM, 2026-06).
     */
    const val QUIZ_RETRY_WINDOW_DAYS: Long = 7L

    /**
     * @param module The module the CHW is about to (re)open.
     * @param nowMs Current millis-since-epoch. Parameterised so unit tests
     *   can pin a deterministic clock without injecting a Clock interface.
     * @return `true` when the CTA should be locked (window closed),
     *   `false` otherwise.
     */
    fun isRetryWindowClosed(
        module: LearnModule,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val total = module.inlineQuestions?.size ?: 0
        if (total == 0) return false
        val attempted = module.attemptedQuestionCount ?: 0
        if (attempted < total) return false
        val publishedAtMs = module.publishedAtMs ?: return false
        val windowMs = TimeUnit.DAYS.toMillis(QUIZ_RETRY_WINDOW_DAYS)
        return (nowMs - publishedAtMs) >= windowMs
    }
}
