package com.medtroniclabs.microcoaching.domain.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class EventFamilyMappingTest {

    @Test
    fun `backend-canonical coaching events map to the coaching family`() {
        assertEquals("coaching", eventFamilyFor("card_shown"))
        assertEquals("coaching", eventFamilyFor("card_skipped"))
        assertEquals("coaching", eventFamilyFor("card_accepted"))
        // `module_quiz_viewed` (Events-Modelling 1.7, formerly `quiz_started`) is
        // the "CHW opened a quiz" event in the `coaching` family.
        assertEquals("coaching", eventFamilyFor("module_quiz_viewed"))
        // Per v3 E2E doc (docs/v3/coaching-platform-e2e-backend.md) the per-question
        // attempt event is `module_quiz_attempted` in the `coaching` family.
        assertEquals("coaching", eventFamilyFor("module_quiz_attempted"))
        assertEquals("coaching", eventFamilyFor("counselling_used"))
        // `module_card_viewed` moved to `coaching` per Events-Modelling 1.7.
        assertEquals("coaching", eventFamilyFor("module_card_viewed"))
    }

    @Test
    fun `module learning events map to the learning family`() {
        assertEquals("learning", eventFamilyFor("module_delivered"))
        assertEquals("learning", eventFamilyFor("module_completed"))
    }

    @Test
    fun `digital surface events map to the digital family`() {
        assertEquals("digital", eventFamilyFor("sync_attempt"))
        assertEquals("digital", eventFamilyFor("login_attempt"))
        assertEquals("digital", eventFamilyFor("form_submit"))
    }

    @Test
    fun `clinical observations map to the clinical_observed family`() {
        assertEquals("clinical_observed", eventFamilyFor("risk_flag_observed"))
        assertEquals("clinical_observed", eventFamilyFor("spice_action_observed"))
    }

    @Test
    fun `unknown or session events fall through to system`() {
        assertEquals("system", eventFamilyFor("session_start"))
        assertEquals("system", eventFamilyFor("session_end"))
        assertEquals("system", eventFamilyFor("definitely_not_a_real_event"))
    }
}
