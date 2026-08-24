package com.medtroniclabs.microcoaching.domain.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class EventFamilyMappingTest {

    @Test
    fun `backend-canonical coaching events map to the coaching family`() {
        assertEquals("coaching", eventFamilyFor("card_skipped"))
        assertEquals("coaching", eventFamilyFor("card_accepted"))
        assertEquals("coaching", eventFamilyFor("module_quiz_viewed"))
        assertEquals("coaching", eventFamilyFor("module_quiz_attempted"))
        assertEquals("coaching", eventFamilyFor("counselling_used"))
        assertEquals("coaching", eventFamilyFor("module_card_viewed"))
        assertEquals("coaching", eventFamilyFor("module_requested"))
        assertEquals("coaching", eventFamilyFor("video_progress_updated"))
        assertEquals("coaching", eventFamilyFor("document_viewed"))
    }

    @Test
    fun `module learning events map to the learning family`() {
        assertEquals("learning", eventFamilyFor("module_delivered"))
    }

    @Test
    fun `digital surface events map to the digital family`() {
        assertEquals("digital", eventFamilyFor("digital_help_used"))
        assertEquals("digital", eventFamilyFor("chat_feedback_positive"))
        assertEquals("digital", eventFamilyFor("chat_feedback_negative"))
        assertEquals("digital", eventFamilyFor("login_attempt"))
        assertEquals("digital", eventFamilyFor("form_submit"))
    }

    @Test
    fun `clinical observations map to the clinical_observed family`() {
        assertEquals("clinical_observed", eventFamilyFor("spice_action_observed"))
    }

    @Test
    fun `unknown events fall through to system`() {
        assertEquals("system", eventFamilyFor("definitely_not_a_real_event"))
    }

    /**
     * Types the SDK stopped emitting because nothing on the backend read them.
     * They fall through to `system` now; the assertion is here so a re-added
     * emitter shows up as a failing test rather than silent dead traffic.
     */
    @Test
    fun `retired event types are no longer classified`() {
        listOf(
            "session_start",
            "session_end",
            "card_shown",
            "risk_flag_observed",
            "module_completed",
            "sync_attempt",
        ).forEach { assertEquals(it, "system", eventFamilyFor(it)) }
    }
}
