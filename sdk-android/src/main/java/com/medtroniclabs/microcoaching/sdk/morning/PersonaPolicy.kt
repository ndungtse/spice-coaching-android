package com.medtroniclabs.microcoaching.sdk.morning

import com.medtroniclabs.microcoaching.CoachingPersona

/**
 * The persona invariant, in one place. A Program Officer has no morning routine / refreshers,
 * so several surfaces early-return for a PO. Replaces the scattered `isProgramOfficer` checks
 * the facade repeated (init refilter collector, home/morning open, selected-morning mapping).
 *
 * [persona] is read lazily each call so a mid-session `setPersona` is reflected immediately.
 */
internal class PersonaPolicy(private val persona: () -> CoachingPersona) {
    val suppressesRefreshers: Boolean
        get() = persona() == CoachingPersona.PO
}
