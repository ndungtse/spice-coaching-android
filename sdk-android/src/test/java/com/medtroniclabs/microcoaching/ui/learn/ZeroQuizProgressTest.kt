package com.medtroniclabs.microcoaching.ui.learn

import com.medtroniclabs.microcoaching.ui.learn.modules.components.progressFractionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress for a module with no quiz, measured by how much of it has been read.
 *
 * Such a module has no attempts to count, so before this it sat at 0% until it
 * completed and then jumped straight to 100%. The ring and
 * [LearnModule.isProgressComplete] have to agree at every point, or a module can
 * show a full ring while still counting toward the outstanding-training reminder.
 */
class ZeroQuizProgressTest {

    private fun module(
        questionCount: Int = 0,
        cardCount: Int = 0,
        viewedCardCount: Int? = null,
        attemptedQuestionCount: Int? = null,
        status: String = "assigned",
    ) = LearnModule(
        moduleFamilyId = "fam-1",
        title = "Title",
        body = "Body",
        clinicalDomain = "maternal",
        status = status,
        questionCount = questionCount,
        cardCount = cardCount,
        viewedCardCount = viewedCardCount,
        attemptedQuestionCount = attemptedQuestionCount,
    )

    @Test
    fun `reading progresses through intermediate fractions`() {
        val m = module(cardCount = 5, viewedCardCount = 2)
        assertEquals(0.4f, progressFractionFor(m), 0.0001f)
        assertFalse("two of five read is not complete", m.isProgressComplete)
    }

    @Test
    fun `an unread module is zero rather than complete`() {
        assertEquals(0f, progressFractionFor(module(cardCount = 5, viewedCardCount = 0)), 0.0001f)
        assertEquals(0f, progressFractionFor(module(cardCount = 5, viewedCardCount = null)), 0.0001f)
    }

    @Test
    fun `reading every card completes the module`() {
        val m = module(cardCount = 4, viewedCardCount = 4)
        assertTrue(m.isProgressComplete)
        assertEquals(1f, progressFractionFor(m), 0.0001f)
    }

    @Test
    fun `a recorded completion shows full even if the read count lags`() {
        // Fresh device: the completion synced but the local view log did not.
        val m = module(cardCount = 6, viewedCardCount = 0, status = "completed")
        assertTrue(m.isProgressComplete)
        assertEquals(1f, progressFractionFor(m), 0.0001f)
    }

    @Test
    fun `a stray extra view cannot exceed full`() {
        // Defensive: a card id that outlives a version bump could over-count.
        val m = module(cardCount = 3, viewedCardCount = 5)
        assertEquals(1f, progressFractionFor(m), 0.0001f)
    }

    @Test
    fun `a module with questions still measures attempts, not reading`() {
        // Reading every card must not complete a module that has a quiz to answer.
        val m = module(questionCount = 4, cardCount = 3, viewedCardCount = 3, attemptedQuestionCount = 1)
        assertEquals(0.25f, progressFractionFor(m), 0.0001f)
        assertFalse("cards read must not substitute for answering", m.isProgressComplete)
    }

    @Test
    fun `a module with neither questions nor cards is zero`() {
        val m = module(cardCount = 0, viewedCardCount = 0)
        assertFalse(m.isProgressComplete)
        assertEquals(0f, progressFractionFor(m), 0.0001f)
    }
}
