package com.medtroniclabs.microcoaching.domain.triggers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildModuleCompletionTest {

    private val nowMillis = 1_700_000_000_000L
    private val reinforcementDays = 90
    private val reinforcementMillis = reinforcementDays.toLong() * 24L * 60L * 60L * 1000L

    @Test
    fun `first pass sets completedAt and zeroes failure count`() {
        val row = buildModuleCompletion(
            previous = null,
            chwId = "chw-1",
            moduleFamilyId = "fam-1",
            moduleId = "ver-1",
            scoreFraction = 0.9f,
            passed = true,
            reinforcementDays = reinforcementDays,
            nowMillis = nowMillis,
        )
        assertEquals(0, row.attemptsSinceLastPass)
        assertEquals(nowMillis, row.completedAt)
        assertEquals("ver-1", row.latestCompletedModuleId)
        assertEquals(nowMillis + reinforcementMillis, row.reinforcementDueAt)
        assertTrue(row.latestAttemptPassed)
    }

    @Test
    fun `first failure increments counter and leaves completedAt null`() {
        val row = buildModuleCompletion(
            previous = null,
            chwId = "chw-1",
            moduleFamilyId = "fam-1",
            moduleId = "ver-1",
            scoreFraction = 0.3f,
            passed = false,
            reinforcementDays = reinforcementDays,
            nowMillis = nowMillis,
        )
        assertEquals(1, row.attemptsSinceLastPass)
        assertNull(row.completedAt)
        assertNull(row.latestCompletedModuleId)
        assertNull(row.reinforcementDueAt)
    }

    @Test
    fun `subsequent failure increments existing counter`() {
        val previous = buildModuleCompletion(
            previous = null,
            chwId = "chw-1",
            moduleFamilyId = "fam-1",
            moduleId = "ver-1",
            scoreFraction = 0.3f,
            passed = false,
            reinforcementDays = reinforcementDays,
            nowMillis = nowMillis,
        )
        val row = buildModuleCompletion(
            previous = previous,
            chwId = "chw-1",
            moduleFamilyId = "fam-1",
            moduleId = "ver-1",
            scoreFraction = 0.4f,
            passed = false,
            reinforcementDays = reinforcementDays,
            nowMillis = nowMillis + 1000,
        )
        assertEquals(2, row.attemptsSinceLastPass)
    }

    @Test
    fun `pass after failures resets counter and refreshes due date`() {
        val previous = buildModuleCompletion(
            previous = null,
            chwId = "chw-1",
            moduleFamilyId = "fam-1",
            moduleId = "ver-1",
            scoreFraction = 0.3f,
            passed = false,
            reinforcementDays = reinforcementDays,
            nowMillis = nowMillis,
        )
        val row = buildModuleCompletion(
            previous = previous,
            chwId = "chw-1",
            moduleFamilyId = "fam-1",
            moduleId = "ver-2",
            scoreFraction = 0.85f,
            passed = true,
            reinforcementDays = reinforcementDays,
            nowMillis = nowMillis + 5000,
        )
        assertEquals(0, row.attemptsSinceLastPass)
        assertEquals("ver-2", row.latestCompletedModuleId)
        assertNotNull(row.completedAt)
        assertEquals((nowMillis + 5000) + reinforcementMillis, row.reinforcementDueAt)
    }
}
