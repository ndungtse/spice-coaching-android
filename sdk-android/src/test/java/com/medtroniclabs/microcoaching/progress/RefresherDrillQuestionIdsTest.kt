package com.medtroniclabs.microcoaching.progress

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the rule that keeps a refresher tile's question count equal to what its sheet
 * drills. The failure it guards is silent and was reported from the field twice: a tile
 * advertising "Quiz · 4 questions" that opens on "Question 1 / 1", because only one of
 * the two paths applied the morning card's targeted question.
 */
class RefresherDrillQuestionIdsTest {

    private val outstanding = setOf("q1", "q2", "q3", "q4")

    @Test
    fun `a targeted question that is still outstanding is the whole drill`() {
        assertEquals(setOf("q2"), refresherDrillQuestionIds(outstanding, "q2"))
    }

    @Test
    fun `no targeted question leaves the outstanding set untouched`() {
        assertEquals(outstanding, refresherDrillQuestionIds(outstanding, null))
    }

    @Test
    fun `a targeted question already mastered falls back to the remainder`() {
        // "q9" is absent because the CHW answered it correctly, so it is no longer
        // outstanding. Drilling it anyway would leave the card unclearable.
        assertEquals(outstanding, refresherDrillQuestionIds(outstanding, "q9"))
    }

    @Test
    fun `a stale targeted id falls back to the remainder`() {
        // A newer module version dropped the question the card names.
        assertEquals(outstanding, refresherDrillQuestionIds(outstanding, "removed-in-v3"))
    }

    @Test
    fun `an empty outstanding set stays empty whatever is targeted`() {
        // Emptiness is what refresher membership reads, so the narrowing must never
        // create work for a fully-mastered module.
        assertEquals(emptySet<String>(), refresherDrillQuestionIds(emptySet(), "q2"))
        assertEquals(emptySet<String>(), refresherDrillQuestionIds(emptySet(), null))
    }
}
