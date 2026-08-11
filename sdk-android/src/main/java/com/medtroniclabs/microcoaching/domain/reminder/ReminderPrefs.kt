package com.medtroniclabs.microcoaching.domain.reminder

import android.content.Context

/**
 * SharedPreferences store for the incomplete-module reminder (MED-1529 Req 2):
 * the last local calendar date (`"yyyy-MM-dd"`) each [ReminderWindow] popup fired,
 * per user id.
 *
 * Reuses the shared `mc_coaching_prefs` file with the `mc_` key prefix (SDK
 * convention — see `OnboardingPrefs`). Keys are scoped per user id so a user
 * switch on a shared device naturally starts with a clean slate.
 */
object ReminderPrefs {

    private const val PREFS_NAME = com.medtroniclabs.microcoaching.util.PrefsNames.REMINDER

    private fun key(userId: String, window: ReminderWindow): String = when (window) {
        ReminderWindow.MORNING -> "mc_reminder_morning_date_$userId"
        ReminderWindow.AFTERNOON -> "mc_reminder_afternoon_date_$userId"
    }

    /** Last local date (`"yyyy-MM-dd"`) the [window] popup fired for [userId], or null. */
    fun lastShownDate(context: Context, userId: String, window: ReminderWindow): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key(userId, window), null)

    /** Record that the [window] popup fired for [userId] on [dateKey] (`"yyyy-MM-dd"`). */
    fun markShown(context: Context, userId: String, window: ReminderWindow, dateKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key(userId, window), dateKey)
            .apply()
    }
}
