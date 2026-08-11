package com.medtroniclabs.microcoaching.ui.learn

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
        publishedAtMs: Long? = null,
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
            publishedAtMs = publishedAtMs,
        )
    }

    // ── Window-open cases (CTA stays visible) ─────────────────────────────────

    @Test
    fun `window open when module has no quiz`() {
        val m = module(total = 0, attempted = 0, publishedAtMs = NOW - THIRTY_DAYS_MS)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window open when CHW has never attempted any question`() {
        // First-time attempts are always allowed — not a retry.
        val m = module(total = 5, attempted = 0, publishedAtMs = NOW - THIRTY_DAYS_MS)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window open when CHW has attempted only some questions`() {
        // Partial completion is still a "first attempt" in spirit — allow them
        // to finish.
        val m = module(total = 5, attempted = 3, publishedAtMs = NOW - THIRTY_DAYS_MS)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window open when publishedAtMs is null`() {
        // Safety default: if we don't know when the module was published, we
        // don't lock the CHW out. Retry stays available.
        val m = module(total = 5, attempted = 5, publishedAtMs = null)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window open when module was published one day ago and CHW attempted all`() {
        // Within the 7-day window from publication: retries always allowed.
        val m = module(total = 5, attempted = 5, publishedAtMs = NOW - ONE_DAY_MS)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window open at the day-six boundary`() {
        // Still inside the window (6 days < 7 days).
        val m = module(
            total = 5,
            attempted = 5,
            publishedAtMs = NOW - TimeUnit.DAYS.toMillis(6),
        )
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    // ── Window-closed cases (CTA locked) ──────────────────────────────────────

    @Test
    fun `window closed at exactly 7 days after publication when all attempted`() {
        // The window is half-open: [0, 7) days. At exactly 7 days the window
        // has closed (assuming all questions attempted).
        val m = module(total = 5, attempted = 5, publishedAtMs = NOW - SEVEN_DAYS_MS)
        assertTrue(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window closed when module is 8 days old and all attempted`() {
        val m = module(total = 5, attempted = 5, publishedAtMs = NOW - EIGHT_DAYS_MS)
        assertTrue(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window closed when module is 30 days old and all attempted`() {
        val m = module(total = 5, attempted = 5, publishedAtMs = NOW - THIRTY_DAYS_MS)
        assertTrue(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window stays open even on a 30-day-old module if not all attempted`() {
        // Even when the module is well past 7 days, a CHW who hasn't yet
        // completed a full attempt should still be able to do their first one.
        val m = module(total = 5, attempted = 4, publishedAtMs = NOW - THIRTY_DAYS_MS)
        assertFalse(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }

    @Test
    fun `window closed when attempted exceeds total (defensive over-count)`() {
        // mapModules clamps via intersect(questionIds) so this shouldn't happen
        // in practice, but the gate should still fire when attempted >= total.
        val m = module(total = 5, attempted = 7, publishedAtMs = NOW - EIGHT_DAYS_MS)
        assertTrue(QuizRetryGate.isRetryWindowClosed(m, nowMs = NOW))
    }
}
