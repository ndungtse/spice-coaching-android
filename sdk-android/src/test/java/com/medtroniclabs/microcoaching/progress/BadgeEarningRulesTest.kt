package com.medtroniclabs.microcoaching.progress

import com.medtroniclabs.microcoaching.data.db.entity.BadgeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device earning rule.
 *
 * A badge names a module **version** while completions are recorded per **family**,
 * so the two only meet through a translation. Both directions have to work: the
 * version the CHW completed outright, and an older version the badge names whose
 * family the CHW has since completed. Getting this wrong doesn't fail loudly — the
 * badge simply never ticks.
 */
class BadgeEarningRulesTest {

    private fun badge(
        id: String,
        moduleIds: List<String>? = null,
        earnedAt: String? = null,
        locallyEarnedAt: String? = null,
    ) = BadgeEntity(
        badgeId = id,
        chwId = "chw-1",
        name = id,
        moduleIds = moduleIds?.joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
        earnedAt = earnedAt,
        locallyEarnedAt = locallyEarnedAt,
    )

    /** Versions v1/v2 belong to family A, v3 to family B. v9 is known to nothing. */
    private val families = mapOf("v1" to "famA", "v2" to "famA", "v3" to "famB")

    private fun earned(
        badges: List<BadgeEntity>,
        completedModuleIds: Set<String> = emptySet(),
        completedFamilyIds: Set<String> = emptySet(),
    ) = badgesNewlyEarned(badges, completedModuleIds, completedFamilyIds, families::get)
        .map { it.badgeId }

    @Test
    fun `the completed version satisfies its badge directly`() {
        val b = badge("b1", moduleIds = listOf("v1"))
        assertEquals(listOf("b1"), earned(listOf(b), completedModuleIds = setOf("v1")))
    }

    @Test
    fun `a badge naming an older version is satisfied by completing the family`() {
        // The regression that made badges look unearnable: the CHW completed v2, the
        // badge names v1, and v1 has aged out of module_cache. Family bridges them.
        val b = badge("b1", moduleIds = listOf("v1"))
        assertEquals(
            listOf("b1"),
            earned(listOf(b), completedModuleIds = setOf("v2"), completedFamilyIds = setOf("famA")),
        )
    }

    @Test
    fun `version ids are never compared against family ids directly`() {
        val b = badge("b1", moduleIds = listOf("v1", "v3"))
        assertEquals(
            listOf("b1"),
            earned(listOf(b), completedFamilyIds = setOf("famA", "famB")),
        )
        assertTrue(
            "raw version ids in the family set must not award",
            earned(listOf(b), completedFamilyIds = setOf("v1", "v3")).isEmpty(),
        )
    }

    @Test
    fun `a partially completed badge is not awarded`() {
        val b = badge("b1", moduleIds = listOf("v1", "v3"))
        assertTrue(earned(listOf(b), completedFamilyIds = setOf("famA")).isEmpty())
    }

    @Test
    fun `two versions of the same family count as one requirement`() {
        val b = badge("b1", moduleIds = listOf("v1", "v2"))
        assertEquals(listOf("b1"), earned(listOf(b), completedFamilyIds = setOf("famA")))
    }

    @Test
    fun `an unjudgeable module id is passed over rather than blocking`() {
        // v9 resolves to no family and was never completed, so this device can't say.
        // Blocking on it would cost the CHW a tick they earned.
        val b = badge("b1", moduleIds = listOf("v1", "v9"))
        assertEquals(listOf("b1"), earned(listOf(b), completedFamilyIds = setOf("famA")))
    }

    @Test
    fun `a badge with nothing judgeable is not awarded`() {
        // No evidence either way — awarding here would tick every badge on a device
        // whose caches haven't synced.
        assertTrue(earned(listOf(badge("b1", listOf("v9"))), completedFamilyIds = setOf("famA")).isEmpty())
        assertTrue(earned(listOf(badge("b2", emptyList())), completedFamilyIds = setOf("famA")).isEmpty())
        assertTrue(earned(listOf(badge("b3", null)), completedFamilyIds = setOf("famA")).isEmpty())
    }

    @Test
    fun `already-earned badges are not re-awarded`() {
        val ids = listOf("v1")
        val done = setOf("famA")
        assertTrue(earned(listOf(badge("s", ids, earnedAt = "2026-08-01T00:00:00Z")), completedFamilyIds = done).isEmpty())
        assertTrue(
            earned(listOf(badge("l", ids, locallyEarnedAt = "2026-08-01T00:00:00Z")), completedFamilyIds = done).isEmpty(),
        )
    }

    @Test
    fun `malformed module_ids json does not throw`() {
        val b = BadgeEntity(badgeId = "b1", chwId = "chw-1", moduleIds = "not json")
        assertTrue(earned(listOf(b), completedFamilyIds = setOf("famA")).isEmpty())
        assertTrue(b.moduleIdList().isEmpty())
    }

    @Test
    fun `requirement verdicts distinguish outstanding from unjudgeable`() {
        fun verdict(id: String) = moduleRequirementFor(id, setOf("v2"), setOf("famA"), families::get)

        assertEquals(ModuleRequirement.SATISFIED, verdict("v2"))
        assertEquals("v1 maps to the completed family", ModuleRequirement.SATISFIED, verdict("v1"))
        assertEquals("famB has no completion", ModuleRequirement.OUTSTANDING, verdict("v3"))
        assertEquals("no family known for v9", ModuleRequirement.UNKNOWN, verdict("v9"))
    }
}
