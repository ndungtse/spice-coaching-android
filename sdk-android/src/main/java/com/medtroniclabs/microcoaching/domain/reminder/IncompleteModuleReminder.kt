package com.medtroniclabs.microcoaching.domain.reminder

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The two daily reminder windows (MED-1529 Req 2). Noon-split, see [IncompleteModuleReminder]. */
enum class ReminderWindow { MORNING, AFTERNOON }

/**
 * Pure decision logic for the "incomplete assigned modules" reminder popup
 * (MED-1529, Requirement 2).
 *
 * ## What this does
 *
 * When the Coaching Home Screen opens, [shouldShow] decides whether — and in
 * which [ReminderWindow] — to surface a one-button reminder popup counting the
 * user's assigned-but-incomplete modules. The popup fires **at most twice per
 * local calendar day**: once in the morning window and once in the afternoon
 * window.
 *
 * ## Rule semantics
 *
 * Windows are a simple **noon split** on the device's local time
 * (`local hour < 12` → [ReminderWindow.MORNING], else [ReminderWindow.AFTERNOON]),
 * so every open of the home screen falls in exactly one window and the whole
 * day is covered.
 *
 * [shouldShow] returns the window to show in, or `null` (show nothing) when any
 * of these hold:
 *  1. The assigned-module set hasn't loaded yet (`loaded == false`) — avoids a
 *     premature popup before Room has been read.
 *  2. There are no incomplete modules (`incompleteCount <= 0`).
 *  3. The current window's popup has **already been shown on today's local
 *     date** — tracked by the caller via the two `lastShown*Date` params
 *     (`"yyyy-MM-dd"` strings, or `null` if never shown).
 *
 * The gate is pure: all clock and persistence inputs are parameters, so it is
 * unit-testable without Android or a Clock interface (mirrors `QuizRetryGate`).
 * The "mark when shown" persistence, and the `incompleteCount` derivation, live
 * with the caller.
 */
object IncompleteModuleReminder {

    private val DATE_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** Local calendar date of [nowMillis] in [zone], formatted `yyyy-MM-dd`. */
    fun todayKey(nowMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().format(DATE_KEY)

    /** Noon split: MORNING when the local hour is `< 12`, else AFTERNOON. */
    fun classify(nowMillis: Long, zone: ZoneId): ReminderWindow {
        val hour = Instant.ofEpochMilli(nowMillis).atZone(zone).hour
        return if (hour < 12) ReminderWindow.MORNING else ReminderWindow.AFTERNOON
    }

    /**
     * @param incompleteCount assigned modules whose status is not "completed".
     * @param loaded whether the assigned-module set has been read at least once.
     * @param lastShownMorningDate `"yyyy-MM-dd"` the morning popup last fired, or null.
     * @param lastShownAfternoonDate `"yyyy-MM-dd"` the afternoon popup last fired, or null.
     * @param nowMillis current millis-since-epoch (parameterised for deterministic tests).
     * @param zone device zone used for the window and the calendar-day key.
     * @return the [ReminderWindow] to show in, or `null` to show nothing.
     */
    fun shouldShow(
        incompleteCount: Int,
        loaded: Boolean,
        lastShownMorningDate: String?,
        lastShownAfternoonDate: String?,
        nowMillis: Long,
        zone: ZoneId,
    ): ReminderWindow? {
        if (!loaded) return null
        if (incompleteCount <= 0) return null

        val window = classify(nowMillis, zone)
        val today = todayKey(nowMillis, zone)
        val lastShown = when (window) {
            ReminderWindow.MORNING -> lastShownMorningDate
            ReminderWindow.AFTERNOON -> lastShownAfternoonDate
        }
        return if (lastShown == today) null else window
    }
}
