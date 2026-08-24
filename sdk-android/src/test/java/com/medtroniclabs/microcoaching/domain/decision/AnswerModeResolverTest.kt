package com.medtroniclabs.microcoaching.domain.decision

import com.medtroniclabs.microcoaching.ai.model.LocalModelChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhausts the routing decision so the device matrix doesn't have to.
 *
 * The properties asserted at the end matter more than the individual cases: that consent is
 * never bypassed, that an unloaded engine is never promised, and that every combination still
 * answers with something. Those are the failures that would surface as a CHW getting silence,
 * an unconsented download, or a crash.
 */
class AnswerModeResolverTest {

    private fun inputs(
        preferOnline: Boolean = false,
        networkAvailable: Boolean = false,
        deviceEligible: Boolean = true,
        choice: LocalModelChoice = LocalModelChoice.ENABLED,
        modelReady: Boolean = true,
        engineLoaded: Boolean = true,
    ) = AnswerModeInputs(
        preferOnline, networkAvailable, deviceEligible, choice, modelReady, engineLoaded,
    )

    // ── ONLINE ───────────────────────────────────────────────────────────────

    @Test
    fun `online preference with connectivity routes online`() {
        assertEquals(
            AnswerMode.ONLINE,
            resolveAnswerMode(inputs(preferOnline = true, networkAvailable = true)),
        )
    }

    @Test
    fun `online preference without connectivity falls back on device`() {
        assertEquals(
            AnswerMode.ON_DEVICE_ASSISTED,
            resolveAnswerMode(inputs(preferOnline = true, networkAvailable = false)),
        )
    }

    /** Connectivity alone must not override a stored on-device preference. */
    @Test
    fun `connectivity without the preference stays on device`() {
        assertEquals(
            AnswerMode.ON_DEVICE_ASSISTED,
            resolveAnswerMode(inputs(preferOnline = false, networkAvailable = true)),
        )
    }

    /** Online needs no model, so it wins even on hardware that can host nothing. */
    @Test
    fun `online is available to ineligible devices`() {
        assertEquals(
            AnswerMode.ONLINE,
            resolveAnswerMode(
                inputs(
                    preferOnline = true,
                    networkAvailable = true,
                    deviceEligible = false,
                    choice = LocalModelChoice.DISABLED,
                    modelReady = false,
                    engineLoaded = false,
                ),
            ),
        )
    }

    // ── ASSISTED requires all four ───────────────────────────────────────────

    @Test
    fun `assisted needs eligibility, consent, a file and a loaded engine`() {
        assertEquals(AnswerMode.ON_DEVICE_ASSISTED, resolveAnswerMode(inputs()))

        assertEquals(
            AnswerMode.ON_DEVICE_DIRECT,
            resolveAnswerMode(inputs(deviceEligible = false)),
        )
        assertEquals(
            AnswerMode.ON_DEVICE_DIRECT,
            resolveAnswerMode(inputs(choice = LocalModelChoice.DISABLED)),
        )
        assertEquals(
            AnswerMode.ON_DEVICE_DIRECT,
            resolveAnswerMode(inputs(choice = LocalModelChoice.UNDECIDED)),
        )
        assertEquals(
            AnswerMode.ON_DEVICE_DIRECT,
            resolveAnswerMode(inputs(modelReady = false)),
        )
        // The file can be present and valid yet fail to load; rewording needs the engine.
        assertEquals(
            AnswerMode.ON_DEVICE_DIRECT,
            resolveAnswerMode(inputs(engineLoaded = false)),
        )
    }

    /** A downloaded file is not consent — the model stays unused until opted into. */
    @Test
    fun `a present model is not used without consent`() {
        assertEquals(
            AnswerMode.ON_DEVICE_DIRECT,
            resolveAnswerMode(
                inputs(choice = LocalModelChoice.UNDECIDED, modelReady = true, engineLoaded = true),
            ),
        )
    }

    // ── Properties over the whole input space ────────────────────────────────

    private fun allInputs(): List<AnswerModeInputs> {
        val flags = listOf(false, true)
        val result = mutableListOf<AnswerModeInputs>()
        for (preferOnline in flags) for (network in flags) for (eligible in flags) {
            for (choice in LocalModelChoice.entries) for (ready in flags) for (loaded in flags) {
                result += AnswerModeInputs(preferOnline, network, eligible, choice, ready, loaded)
            }
        }
        return result
    }

    @Test
    fun `every combination resolves`() {
        val all = allInputs()
        assertEquals(2 * 2 * 2 * 3 * 2 * 2, all.size)
        // A missing branch would throw rather than return, so reaching a mode is the assertion.
        all.forEach { assertNotEquals(null, resolveAnswerMode(it)) }
    }

    @Test
    fun `assisted is never chosen without consent`() {
        allInputs()
            .filter { it.choice != LocalModelChoice.ENABLED }
            .forEach { assertNotEquals(AnswerMode.ON_DEVICE_ASSISTED, resolveAnswerMode(it)) }
    }

    @Test
    fun `assisted is never chosen without a loaded engine and a ready file`() {
        allInputs()
            .filter { !it.engineLoaded || !it.modelReady }
            .forEach { assertNotEquals(AnswerMode.ON_DEVICE_ASSISTED, resolveAnswerMode(it)) }
    }

    @Test
    fun `assisted is never chosen on ineligible hardware`() {
        allInputs()
            .filter { !it.deviceEligible }
            .forEach { assertNotEquals(AnswerMode.ON_DEVICE_ASSISTED, resolveAnswerMode(it)) }
    }

    @Test
    fun `online is chosen exactly when preferred and connected`() {
        allInputs().forEach { i ->
            val expected = i.preferOnline && i.networkAvailable
            assertEquals(expected, resolveAnswerMode(i) == AnswerMode.ONLINE)
        }
    }

    /**
     * With no network and no usable model there is still an answer to give. This is the
     * property that keeps chat from having an unanswerable state at all.
     */
    @Test
    fun `direct is always available as the floor`() {
        val stripped = AnswerModeInputs(
            preferOnline = true,
            networkAvailable = false,
            deviceEligible = false,
            choice = LocalModelChoice.DISABLED,
            modelReady = false,
            engineLoaded = false,
        )
        assertEquals(AnswerMode.ON_DEVICE_DIRECT, resolveAnswerMode(stripped))
    }

    /** Only the model-backed mode should ever depend on the engine being loaded. */
    @Test
    fun `engine state changes nothing outside assisted mode`() {
        allInputs().filter { it.engineLoaded }.forEach { loaded ->
            val unloaded = loaded.copy(engineLoaded = false)
            if (resolveAnswerMode(loaded) != AnswerMode.ON_DEVICE_ASSISTED) {
                assertEquals(resolveAnswerMode(loaded), resolveAnswerMode(unloaded))
            } else {
                assertTrue(resolveAnswerMode(unloaded) == AnswerMode.ON_DEVICE_DIRECT)
            }
        }
    }
}
