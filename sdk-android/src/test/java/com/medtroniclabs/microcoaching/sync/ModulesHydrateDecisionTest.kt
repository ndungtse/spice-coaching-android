package com.medtroniclabs.microcoaching.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [shouldHydrateFullCatalogue] — the self-heal decision that fixes
 * newly-assigned modules never appearing on the device.
 *
 * Background: the backend returns the *full* `assigned_module_ids` on every
 * `/sync/modules` call, but the `modules` list is a watermark delta. Assigning
 * an existing (content-unchanged) module lands the assignment id with no module
 * row, so it has no `module_cache` content and can't render until the daily
 * full-catalogue reconcile. When an incremental pull leaves such a gap, we force
 * a full-catalogue fetch to hydrate the content immediately.
 *
 * Plain JUnit, no harness — the predicate is pure so the DB/network wiring in
 * [SyncApi.pullModules] doesn't need mocking here.
 */
class ModulesHydrateDecisionTest {

    @Test
    fun `incremental pull leaving an unresolved assignment triggers hydrate`() {
        assertTrue(shouldHydrateFullCatalogue(wasFullCatalogue = false, unresolvedAssignedCount = 1))
        assertTrue(shouldHydrateFullCatalogue(wasFullCatalogue = false, unresolvedAssignedCount = 7))
    }

    @Test
    fun `incremental pull with everything resolved does not hydrate`() {
        assertFalse(shouldHydrateFullCatalogue(wasFullCatalogue = false, unresolvedAssignedCount = 0))
    }

    @Test
    fun `full-catalogue pull never re-hydrates`() {
        // A full pull is already authoritative; a still-unresolved assignment there
        // is a genuinely missing/retired module, not a watermark gap — re-fetching
        // would loop without helping.
        assertFalse(shouldHydrateFullCatalogue(wasFullCatalogue = true, unresolvedAssignedCount = 0))
        assertFalse(shouldHydrateFullCatalogue(wasFullCatalogue = true, unresolvedAssignedCount = 3))
    }
}
