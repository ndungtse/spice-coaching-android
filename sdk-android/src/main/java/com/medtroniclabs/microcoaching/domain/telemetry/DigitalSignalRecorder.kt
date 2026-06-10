package com.medtroniclabs.microcoaching.domain.telemetry

// TODO: records digital_proficiency_events to Room.
// Signals: sync_attempt (success/failure), form_submit (success/failure), digital_help_used.
// Feeds digital_proficiency_score in chw_gap_profile_local → drives the SPICE digital track.
// Keep lightweight — runs on every form submission; no blocking IO on main thread.
