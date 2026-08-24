package com.medtroniclabs.microcoaching.domain.refresher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the one rule that decides a refresher's type and whether it stays on the list.
 *
 * The failures this guards are silent by nature: a tile promising a quiz that opens on
 * lesson cards, a mastered refresher that never leaves the Practice Zone, or — the one that
 * would hurt most — a wrong-referral re-drill quietly dropped because the CHW happened to
 * have answered every question correctly before making the mistake.
 */
class RefresherKindTest {

    private val outstanding = setOf("q1", "q2", "q3")

    private fun kindOf(
        source: String? = null,
        targetQuizId: String? = null,
        toReinforce: Set<String> = emptySet(),
        hasQuestions: Boolean = true,
        hasCards: Boolean = true,
        cardsRead: Boolean = false,
        isActionGap: Boolean = false,
    ) = refresherKindOf(
        source = source,
        targetQuizId = targetQuizId,
        toReinforce = toReinforce,
        hasQuestions = hasQuestions,
        hasCards = hasCards,
        cardsRead = cardsRead,
        isActionGap = isActionGap,
    )

    // ── Targeted question ────────────────────────────────────────────────────────

    @Test
    fun `a targeted question that is still outstanding is a quiz`() {
        assertEquals(
            RefresherKind.QUIZ,
            kindOf(source = "quiz", targetQuizId = "q2", toReinforce = outstanding),
        )
    }

    @Test
    fun `a stale targeted id falls through to the remaining outstanding set`() {
        // The mapper nulls an id the module no longer carries. The card still drills —
        // it stays a Quiz rather than becoming Microcoaching, because a quiz-sourced card
        // exists to drill, not to be read.
        assertEquals(
            RefresherKind.QUIZ,
            kindOf(source = "quiz", targetQuizId = null, toReinforce = outstanding),
        )
    }

    // ── A quiz-sourced card is over when there is nothing to drill ───────────────

    @Test
    fun `a quiz card with nothing left to drill is dropped`() {
        assertNull(kindOf(source = "quiz", toReinforce = emptySet()))
    }

    @Test
    fun `a quiz card with nothing left does NOT decay into a reading card`() {
        // Its reason for existing was the failed question; lesson cards are not a
        // substitute reason to keep showing it.
        assertNull(kindOf(source = "quiz", toReinforce = emptySet(), hasCards = true, cardsRead = false))
    }

    @Test
    fun `a quiz id on a non-quiz source is treated as quiz intent`() {
        assertNull(kindOf(source = "gap", targetQuizId = "gone", toReinforce = emptySet()))
    }

    // ── Action gaps outlive mastery ──────────────────────────────────────────────

    @Test
    fun `an active action gap survives an empty to-reinforce set`() {
        // Every question mastered BEFORE the wrong referral, so nothing is outstanding —
        // but the gap clears only on a passing re-drill since the mistake.
        assertEquals(
            RefresherKind.QUIZ,
            kindOf(source = "gap", toReinforce = emptySet(), isActionGap = true),
        )
    }

    @Test
    fun `an action gap on a module with no quiz cannot be re-drilled`() {
        assertNull(
            kindOf(source = "gap", toReinforce = emptySet(), hasQuestions = false, hasCards = false, isActionGap = true),
        )
    }

    // ── Content shape decides the rest ───────────────────────────────────────────

    @Test
    fun `outstanding questions plus cards is microcoaching`() {
        assertEquals(RefresherKind.MICROCOACHING, kindOf(source = "fallback", toReinforce = outstanding))
    }

    @Test
    fun `outstanding questions without cards is a quiz`() {
        assertEquals(
            RefresherKind.QUIZ,
            kindOf(source = "fallback", toReinforce = outstanding, hasCards = false),
        )
    }

    @Test
    fun `cards with nothing outstanding is a learning card until it is read`() {
        assertEquals(
            RefresherKind.LEARNING,
            kindOf(source = "fallback", toReinforce = emptySet(), hasQuestions = false),
        )
    }

    @Test
    fun `a learning card is dropped once its cards are read`() {
        // Reading them is the only way a cards-only refresher can ever be finished.
        assertNull(
            kindOf(source = "fallback", toReinforce = emptySet(), hasQuestions = false, cardsRead = true),
        )
    }

    @Test
    fun `nothing to drill and nothing to read is dropped`() {
        assertNull(kindOf(source = "fallback", toReinforce = emptySet(), hasQuestions = false, hasCards = false))
    }
}
