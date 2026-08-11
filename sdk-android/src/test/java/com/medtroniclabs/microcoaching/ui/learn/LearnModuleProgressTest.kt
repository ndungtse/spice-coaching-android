package com.medtroniclabs.microcoaching.ui.learn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [LearnModule.isProgressComplete] — the single "completed" definition
 * shared by the All Modules progress ring and the incomplete-module reminder
 * count (MED-1940 Req 2). "Complete" = passed OR every question attempted.
 */
class LearnModuleProgressTest {

    private fun module(
        status: String = "assigned",
        questionCount: Int = 5,
        attempted: Int? = null,
    ): LearnModule = LearnModule(
        moduleFamilyId = "fam-1",
        title = "t",
        body = "b",
        clinicalDomain = "hypertension",
        status = status,
        questionCount = questionCount,
        attemptedQuestionCount = attempted,
    )

    @Test
    fun `passed module is complete`() {
        assertTrue(module(status = "completed", attempted = 0).isProgressComplete)
    }

    @Test
    fun `fully attempted but not passed module is complete`() {
        assertTrue(module(status = "in_progress", questionCount = 5, attempted = 5).isProgressComplete)
    }

    @Test
    fun `partially attempted module is incomplete`() {
        assertFalse(module(status = "in_progress", questionCount = 5, attempted = 3).isProgressComplete)
    }

    @Test
    fun `never attempted module is incomplete`() {
        assertFalse(module(status = "assigned", questionCount = 5, attempted = null).isProgressComplete)
    }

    @Test
    fun `no-quiz module is incomplete unless passed`() {
        assertFalse(module(status = "assigned", questionCount = 0, attempted = null).isProgressComplete)
        assertTrue(module(status = "completed", questionCount = 0, attempted = null).isProgressComplete)
    }
}
