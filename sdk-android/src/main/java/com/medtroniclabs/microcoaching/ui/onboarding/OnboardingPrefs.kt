package com.medtroniclabs.microcoaching.ui.onboarding

import android.content.Context

/**
 * Thin wrapper around SharedPreferences for onboarding state.
 *
 * All SDK SharedPrefs keys are prefixed with `mc_` to avoid collision
 * with SPICE's own preferences file.
 */
object OnboardingPrefs {

    private const val PREFS_NAME = com.medtroniclabs.microcoaching.util.PrefsNames.ONBOARDING
    private const val KEY_ONBOARDED = "mc_onboarded_v1"
    private const val KEY_INITIAL_ASSESSMENT_DONE = "mc_initial_assessment_done_v1"

    fun isOnboarded(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDED, false)

    fun markOnboarded(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDED, true)
            .apply()
    }

    /** True once the CHW has completed the one-time cross-domain assessment (UC-1). */
    fun isInitialAssessmentDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_INITIAL_ASSESSMENT_DONE, false)

    fun markInitialAssessmentDone(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INITIAL_ASSESSMENT_DONE, true)
            .apply()
    }

    fun resetForTesting(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ONBOARDED)
            .remove(KEY_INITIAL_ASSESSMENT_DONE)
            .apply()
    }
}
