package com.medtroniclabs.microcoaching.ai.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins when the model offer may appear.
 *
 * Each clause of the rule prevents a different visible defect, so they are asserted
 * individually rather than through one happy path: advertising a model the hardware cannot run,
 * inviting a 304 MB download with no connection, re-asking a question the user already
 * answered, and showing a card that never stops coming back.
 */
class LocalModelOfferRuleTest {

    private fun offer(
        deviceEligible: Boolean = true,
        networkAvailable: Boolean = true,
        choice: LocalModelChoice = LocalModelChoice.UNDECIDED,
        dismissCount: Int = 0,
    ) = shouldOfferLocalModel(deviceEligible, networkAvailable, choice, dismissCount)

    @Test
    fun `offered to an eligible connected device that has not decided`() {
        assertTrue(offer())
    }

    @Test
    fun `never offered on ineligible hardware`() {
        assertFalse(offer(deviceEligible = false))
    }

    /** Accepting starts the download, so offering it offline invites an impossible action. */
    @Test
    fun `never offered without connectivity`() {
        assertFalse(offer(networkAvailable = false))
    }

    @Test
    fun `never offered once the user has decided either way`() {
        assertFalse(offer(choice = LocalModelChoice.DISABLED))
        assertFalse(offer(choice = LocalModelChoice.ENABLED))
    }

    /** "Not now" is a deferral, so the offer survives it — up to a point. */
    @Test
    fun `survives deferral until the budget is spent`() {
        assertTrue(offer(dismissCount = 0))
        assertTrue(offer(dismissCount = LocalModelPrefs.MAX_OFFER_DISMISSALS - 1))
        assertFalse(offer(dismissCount = LocalModelPrefs.MAX_OFFER_DISMISSALS))
        assertFalse(offer(dismissCount = LocalModelPrefs.MAX_OFFER_DISMISSALS + 5))
    }

    /** Every clause is independently sufficient to suppress the offer. */
    @Test
    fun `any single failing condition suppresses the offer`() {
        val suppressors = listOf(
            offer(deviceEligible = false),
            offer(networkAvailable = false),
            offer(choice = LocalModelChoice.DISABLED),
            offer(dismissCount = LocalModelPrefs.MAX_OFFER_DISMISSALS),
        )
        assertTrue(suppressors.none { it })
    }
}
