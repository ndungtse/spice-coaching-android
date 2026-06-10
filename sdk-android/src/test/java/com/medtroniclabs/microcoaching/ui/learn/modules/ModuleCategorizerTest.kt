package com.medtroniclabs.microcoaching.ui.learn.modules

import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Refresher / Knowledge / Training partition contract used by
 * [ModulesScreen]. The contract:
 *
 *  - **Training** = `moduleType == "initial_training"` (always — completed
 *    initial-training modules still render here).
 *  - **Knowledge** = `moduleType == "digital_proficiency"` (always).
 *  - **Refresher** = unchanged "active drilling" rule —
 *    `(source != null OR moduleType == "refresher") AND wrongCount > 0
 *    AND status != "completed"`.
 *  - **Drop** = anything matching none of the above.
 *
 * The sections may **overlap** by design — see the "overlap" test below.
 * The only invariant the suite still pins is order preservation + no
 * duplicates *within* a section + no module is in a section it shouldn't be.
 *
 * If a contributor changes a predicate without updating the tests below,
 * the rule-level cases fail and force a conscious decision.
 */
class ModuleCategorizerTest {

    private fun module(
        family: String,
        source: String? = null,
        status: String = "assigned",
        wrongQuestionCount: Int? = null,
        moduleType: String = "initial_training",
    ): LearnModule = LearnModule(
        moduleFamilyId = family,
        title = "title-$family",
        body = "body",
        clinicalDomain = "hypertension",
        status = status,
        source = source,
        wrongQuestionCount = wrongQuestionCount,
        moduleType = moduleType,
    )

    // ── Training rule (moduleType only) ───────────────────────────────────────

    @Test
    fun `Training when moduleType is initial_training assigned`() {
        val m = module("a", moduleType = "initial_training", status = "assigned")
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertEquals(listOf("a"), sections.training.map { it.moduleFamilyId })
        assertTrue(sections.knowledge.isEmpty())
        assertTrue(sections.refreshers.isEmpty())
    }

    @Test
    fun `Training keeps completed initial_training modules (previously Knowledge)`() {
        // Spec change: the "completed wins → Knowledge" rule was removed.
        // Completed initial-training stays in Training.
        val m = module("a", moduleType = "initial_training", status = "completed")
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertEquals(listOf("a"), sections.training.map { it.moduleFamilyId })
        assertTrue(sections.knowledge.isEmpty())
    }

    // ── Knowledge rule (moduleType only) ──────────────────────────────────────

    @Test
    fun `Knowledge when moduleType is digital_proficiency regardless of status`() {
        val rows = listOf(
            module("dp-assigned", moduleType = "digital_proficiency", status = "assigned"),
            module("dp-in-prog", moduleType = "digital_proficiency", status = "in_progress"),
            module("dp-completed", moduleType = "digital_proficiency", status = "completed"),
        )
        val sections = ModuleCategorizer.categorize(rows)
        assertEquals(
            listOf("dp-assigned", "dp-in-prog", "dp-completed"),
            sections.knowledge.map { it.moduleFamilyId },
        )
        assertTrue(sections.training.isEmpty())
        assertTrue(sections.refreshers.isEmpty())
    }

    @Test
    fun `Knowledge does NOT include completed initial_training anymore`() {
        // Explicit pin: completed initial-training no longer falls into Knowledge.
        val m = module("a", moduleType = "initial_training", status = "completed")
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertFalse(sections.knowledge.any { it.moduleFamilyId == "a" })
    }

    // ── Refresher rule (unchanged from previous iteration) ────────────────────

    @Test
    fun `Refresher when morning-card-sourced with wrong questions and not completed`() {
        val m = module(
            family = "a",
            source = "gap",
            status = "in_progress",
            wrongQuestionCount = 3,
            moduleType = "initial_training",
        )
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertTrue(sections.refreshers.any { it.moduleFamilyId == "a" })
    }

    @Test
    fun `Refresher when moduleType is refresher even without a morning-card source`() {
        val m = module(
            family = "a",
            source = null,
            status = "in_progress",
            wrongQuestionCount = 3,
            moduleType = "refresher",
        )
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertEquals(listOf("a"), sections.refreshers.map { it.moduleFamilyId })
    }

    @Test
    fun `Refresher excludes when wrongQuestionCount is zero`() {
        val m = module(
            family = "a",
            source = "gap",
            status = "in_progress",
            wrongQuestionCount = 0,
            moduleType = "refresher",
        )
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertTrue(sections.refreshers.isEmpty())
    }

    @Test
    fun `Refresher excludes completed modules`() {
        val m = module(
            family = "a",
            source = "gap",
            status = "completed",
            wrongQuestionCount = 3,
            moduleType = "refresher",
        )
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertTrue(sections.refreshers.isEmpty())
    }

    // ── Overlap — sections may share modules by design ────────────────────────

    @Test
    fun `morning-card-sourced initial_training with wrong questions lands in BOTH Refresher and Training`() {
        // Documented overlap: the Refresher row preserves "drill this today"
        // prominence even when the underlying module is also a Training-type
        // (initial_training). The same module appears in both lists.
        val m = module(
            family = "a",
            source = "gap",
            status = "in_progress",
            wrongQuestionCount = 3,
            moduleType = "initial_training",
        )
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertEquals(listOf("a"), sections.refreshers.map { it.moduleFamilyId })
        assertEquals(listOf("a"), sections.training.map { it.moduleFamilyId })
        assertTrue(sections.knowledge.isEmpty())
    }

    @Test
    fun `morning-card-sourced digital_proficiency with wrong questions lands in BOTH Refresher and Knowledge`() {
        // Same overlap shape for digital_proficiency.
        val m = module(
            family = "a",
            source = "fallback",
            status = "in_progress",
            wrongQuestionCount = 2,
            moduleType = "digital_proficiency",
        )
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertEquals(listOf("a"), sections.refreshers.map { it.moduleFamilyId })
        assertEquals(listOf("a"), sections.knowledge.map { it.moduleFamilyId })
        assertTrue(sections.training.isEmpty())
    }

    // ── Drop rule (no section matches) ────────────────────────────────────────

    @Test
    fun `content_update modules are dropped (no section matches)`() {
        // content_update isn't initial_training and isn't digital_proficiency.
        // No morning-card source, so it's not a refresher either. → dropped.
        val rows = listOf(
            module("cu-assigned", moduleType = "content_update", status = "assigned"),
            module("cu-completed", moduleType = "content_update", status = "completed"),
            module(
                "cu-in-prog",
                moduleType = "content_update",
                status = "in_progress",
                wrongQuestionCount = 2,
            ),
        )
        val sections = ModuleCategorizer.categorize(rows)
        assertTrue(sections.training.isEmpty())
        assertTrue(sections.knowledge.isEmpty())
        assertTrue(sections.refreshers.isEmpty())
    }

    @Test
    fun `stale refresher-type with no source and zero wrong questions is dropped`() {
        // Refresher requires wrongCount > 0; nothing else picks it up either.
        val m = module(
            family = "a",
            source = null,
            status = "assigned",
            wrongQuestionCount = 0,
            moduleType = "refresher",
        )
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertTrue(sections.refreshers.isEmpty())
        assertTrue(sections.knowledge.isEmpty())
        assertTrue(sections.training.isEmpty())
    }

    @Test
    fun `completed refresher-type module is dropped`() {
        // Refresher excludes completed; refresher isn't initial_training or
        // digital_proficiency → no home → dropped.
        val m = module(
            family = "a",
            moduleType = "refresher",
            status = "completed",
            wrongQuestionCount = 0,
        )
        val sections = ModuleCategorizer.categorize(listOf(m))
        assertTrue(sections.refreshers.isEmpty())
        assertTrue(sections.knowledge.isEmpty())
        assertTrue(sections.training.isEmpty())
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `Empty input yields three empty sections`() {
        val sections = ModuleCategorizer.categorize(emptyList())
        assertTrue(sections.refreshers.isEmpty())
        assertTrue(sections.knowledge.isEmpty())
        assertTrue(sections.training.isEmpty())
    }

    // ── Order preservation ────────────────────────────────────────────────────

    @Test
    fun `input order is preserved within each section`() {
        val catalogue = listOf(
            module("T-first", moduleType = "initial_training", status = "assigned"),
            module("K-first", moduleType = "digital_proficiency", status = "assigned"),
            module(
                "R-first",
                moduleType = "refresher",
                source = null,
                status = "in_progress",
                wrongQuestionCount = 2,
            ),
            module(
                "T-second",
                moduleType = "initial_training",
                status = "in_progress",
                wrongQuestionCount = 5,
            ),
            module(
                "K-second",
                moduleType = "digital_proficiency",
                status = "completed",
            ),
            module(
                "R-second",
                source = "fallback",
                moduleType = "refresher",
                status = "in_progress",
                wrongQuestionCount = 1,
            ),
        )
        val sections = ModuleCategorizer.categorize(catalogue)
        assertEquals(listOf("T-first", "T-second"), sections.training.map { it.moduleFamilyId })
        assertEquals(listOf("K-first", "K-second"), sections.knowledge.map { it.moduleFamilyId })
        assertEquals(listOf("R-first", "R-second"), sections.refreshers.map { it.moduleFamilyId })
    }
}
