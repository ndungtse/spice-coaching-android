package com.medtroniclabs.microcoaching.progress

import com.medtroniclabs.microcoaching.data.db.entity.ChwModuleCompletionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Completion rules that outlive a single attempt, including the quiz-less path.
 *
 * A module with no quiz has no other way to reach `completedAt` — the quiz path is
 * otherwise the only writer — so it would sit at 0% forever and keep counting
 * toward the outstanding-training reminder.
 */
class ModuleCompletionBuilderTest {

    private fun build(
        previous: ChwModuleCompletionEntity? = null,
        scoreFraction: Float? = null,
        passed: Boolean,
        nowMillis: Long = 1_000_000L,
    ) = buildModuleCompletion(
        previous = previous,
        chwId = "chw-1",
        moduleFamilyId = "fam-1",
        moduleId = "mod-1",
        scoreFraction = scoreFraction,
        passed = passed,
        reinforcementDays = 30,
        nowMillis = nowMillis,
    )

    @Test
    fun `a quiz-less completion sets completedAt and leaves the score null`() {
        val row = build(scoreFraction = null, passed = true)

        assertEquals(1_000_000L, row.completedAt)
        assertEquals("mod-1", row.latestCompletedModuleId)
        // A synthetic 100% here would flow into score reporting as if it had been
        // answered, so the absence has to survive.
        assertNull(row.latestQuizScore)
        assertNotNull("the reinforcement clock still starts", row.reinforcementDueAt)
    }

    @Test
    fun `completedAt is sticky across a later failure`() {
        val completed = build(scoreFraction = 0.9f, passed = true, nowMillis = 1_000L)
        val thenFailed = build(previous = completed, scoreFraction = 0.2f, passed = false, nowMillis = 2_000L)

        assertEquals(1_000L, thenFailed.completedAt)
        assertEquals("mod-1", thenFailed.latestCompletedModuleId)
        assertTrue(thenFailed.attemptsSinceLastPass > 0)
    }

    @Test
    fun `a failed attempt never completes the module`() {
        val row = build(scoreFraction = 0.1f, passed = false)
        assertNull(row.completedAt)
        assertNull(row.reinforcementDueAt)
    }
}
