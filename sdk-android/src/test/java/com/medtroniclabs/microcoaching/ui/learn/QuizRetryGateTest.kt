package com.medtroniclabs.microcoaching.ui.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Pins the retry-window rule owned by [QuizRetryGate].
 *
 * When the feature is removed (see [QuizRetryGate]'s "How to remove"
 * section), delete this file alongside the helper.
 *
 * Test style mirrors the rest of the SDK suite: plain JUnit, no MockK, a
 * single hand-rolled [LearnModule] factory, and a pinned `NOW` constant so
 * the window maths is deterministic.
 */
class QuizRetryGateTest {

    private val NOW: Long = 1_700_000_000_000L  // fixed deterministic clock
    private val ONE_DAY_MS = TimeUnit.DAYS.toMillis(1)
    private val SEVEN_DAYS_MS = TimeUnit.DAYS.toMillis(QuizRetryGate.QUIZ_RETRY_WINDOW_DAYS)
    private val EIGHT_DAYS_MS = TimeUnit.DAYS.toMillis(8)
    private val THIRTY_DAYS_MS = TimeUnit.DAYS.toMillis(30)

    /** Builds a quiz module with [total] questions; defaults to a fresh CHW (no attempts). */
    private fun module(
        total: Int = 5,
        attempted: Int? = null,
        assignedAtMs: Long? = null,
    ): LearnModule {
        val questions = List(total) { idx ->
            QuizQuestion(
                id = "q-$idx",
                questionText = "q $idx",
                answers = listOf("a", "b"),
                correctIndex = 0,
            )
        }
        return LearnModule(
            moduleFamilyId = "fam-1",
            title = "title",
            body = "body",
            clinicalDomain = "hypertension",
            inlineQuestions = questions.takeIf { it.isNotEmpty() },
            attemptedQuestionCount = attempted,
            assignedAtMs = assignedAtMs,
        )
    }

    // ── Window-open cases (CTA stays visible) ─────────────────────────────────

    @Test
    fun `window open when module has no quiz`() {
        val m = module(total = 0, attempted = 0, assignedAtMs = NOW - THIRTY_DAYS_MS)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window open when CHW has never attempted any question`() {
        // First-time attempts are always allowed — not a retry.
        val m = module(total = 5, attempted = 0, assignedAtMs = NOW - THIRTY_DAYS_MS)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window open when CHW has attempted only some questions`() {
        // Partial completion is still a "first attempt" in spirit — allow them
        // to finish.
        val m = module(total = 5, attempted = 3, assignedAtMs = NOW - THIRTY_DAYS_MS)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window open when assignedAtMs is null`() {
        // Safety default: if we don't know when the module was assigned, we
        // don't lock the CHW out. Retry stays available.
        val m = module(total = 5, attempted = 5, assignedAtMs = null)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window open when module was assigned one day ago and CHW attempted all`() {
        // Within the 7-day window from assignment: retries always allowed.
        val m = module(total = 5, attempted = 5, assignedAtMs = NOW - ONE_DAY_MS)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window open at the day-six boundary`() {
        // Still inside the window (6 days < 7 days).
        val m = module(
            total = 5,
            attempted = 5,
            assignedAtMs = NOW - TimeUnit.DAYS.toMillis(6),
        )
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    // ── Window-closed cases (CTA locked) ──────────────────────────────────────

    @Test
    fun `window closed at exactly 7 days after assignment when all attempted`() {
        // The window is half-open: [0, 7) days. At exactly 7 days the window
        // has closed (assuming all questions attempted).
        val m = module(total = 5, attempted = 5, assignedAtMs = NOW - SEVEN_DAYS_MS)
        assertTrue(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window closed when module is 8 days old and all attempted`() {
        val m = module(total = 5, attempted = 5, assignedAtMs = NOW - EIGHT_DAYS_MS)
        assertTrue(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window closed when module is 30 days old and all attempted`() {
        val m = module(total = 5, attempted = 5, assignedAtMs = NOW - THIRTY_DAYS_MS)
        assertTrue(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window stays open even on a 30-day-old module if not all attempted`() {
        // Even when the module is well past 7 days, a CHW who hasn't yet
        // completed a full attempt should still be able to do their first one.
        val m = module(total = 5, attempted = 4, assignedAtMs = NOW - THIRTY_DAYS_MS)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window closed when attempted exceeds total (defensive over-count)`() {
        // mapModules clamps via intersect(questionIds) so this shouldn't happen
        // in practice, but the gate should still fire when attempted >= total.
        val m = module(total = 5, attempted = 7, assignedAtMs = NOW - EIGHT_DAYS_MS)
        assertTrue(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    // ── Configurable validity window (synced quiz_reattempt_validity_days) ─────

    @Test
    fun `configured 14-day window keeps an 8-day-old module open`() {
        // An 8-day-old module would be closed under the default 7, but a synced
        // 14-day validity keeps retries open.
        val m = module(total = 5, attempted = 5, assignedAtMs = NOW - EIGHT_DAYS_MS)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, windowDays = 14L, nowMs = NOW))
    }

    @Test
    fun `configured 3-day window closes a 5-day-old module`() {
        // A 5-day-old module is open under the default 7 but closed under a
        // synced 3-day validity.
        val m = module(total = 5, attempted = 5, assignedAtMs = NOW - TimeUnit.DAYS.toMillis(5))
        assertTrue(QuizRetryGate.isRetryWindowClosed(m, windowDays = 3L, nowMs = NOW))
    }

    @Test
    fun `configured window still always allows a first attempt`() {
        // Even with a tiny 1-day window on an old module, a never-attempted quiz
        // is a first attempt, not a retry — stays open.
        val m = module(total = 5, attempted = 0, assignedAtMs = NOW - THIRTY_DAYS_MS)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, windowDays = 1L, nowMs = NOW))
    }

    @Test
    fun `zero-day window still allows a never-attempted first attempt`() {
        // A zero-day validity must not block the mandatory first attempt.
        val m = module(total = 5, attempted = 0, assignedAtMs = NOW - THIRTY_DAYS_MS)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, windowDays = 0L, nowMs = NOW))
    }

    @Test
    fun `zero-day window closes as soon as all questions are attempted`() {
        // Assigned today, all attempted → no reattempt under a zero-day window.
        val m = module(total = 5, attempted = 5, assignedAtMs = NOW)
        assertTrue(QuizRetryGate.isRetryWindowClosed(m, windowDays = 0L, nowMs = NOW))
    }

    // ── resolveValidityDays: parse of the synced config value ─────────────────

    @Test
    fun `resolveValidityDays falls back to default when null`() {
        assertEquals(QuizRetryGate.QUIZ_RETRY_WINDOW_DAYS, QuizRetryGate.resolveValidityDays(null))
    }

    @Test
    fun `resolveValidityDays parses a valid integer`() {
        assertEquals(14L, QuizRetryGate.resolveValidityDays("14"))
    }

    @Test
    fun `resolveValidityDays trims whitespace`() {
        assertEquals(10L, QuizRetryGate.resolveValidityDays(" 10 "))
    }

    @Test
    fun `resolveValidityDays treats zero as a valid zero-day window`() {
        // MED-1940 Req 1: 0 means "one attempt, no reattempt" — a real zero-day
        // window, NOT invalid config. It must not fall back to the default.
        assertEquals(0L, QuizRetryGate.resolveValidityDays("0"))
    }

    @Test
    fun `resolveValidityDays falls back on negative and non-numeric`() {
        assertEquals(QuizRetryGate.QUIZ_RETRY_WINDOW_DAYS, QuizRetryGate.resolveValidityDays("-3"))
        assertEquals(QuizRetryGate.QUIZ_RETRY_WINDOW_DAYS, QuizRetryGate.resolveValidityDays("abc"))
    }
}
