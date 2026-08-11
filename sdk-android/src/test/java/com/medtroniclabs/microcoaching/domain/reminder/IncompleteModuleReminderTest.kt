package com.medtroniclabs.microcoaching.domain.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Pins the incomplete-module reminder rule owned by [IncompleteModuleReminder].
 *
 * Test style mirrors the rest of the SDK suite (see `QuizRetryGateTest`): plain
 * JUnit, no MockK, a fixed UTC zone, and millis built from explicit
 * [LocalDateTime]s so the window / calendar-day maths is deterministic.
 */
class IncompleteModuleReminderTest {

    private val ZONE = ZoneOffset.UTC

    /** Epoch millis for a wall-clock time in [ZONE]. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    // ── classify (noon split) ─────────────────────────────────────────────────

    @Test
    fun `classify is MORNING at midnight`() {
        assertEquals(ReminderWindow.MORNING, IncompleteModuleReminder.classify(at(2026, 7, 7, 0), ZONE))
    }

    @Test
    fun `classify is MORNING just before noon`() {
        assertEquals(ReminderWindow.MORNING, IncompleteModuleReminder.classify(at(2026, 7, 7, 11, 59), ZONE))
    }

    @Test
    fun `classify is AFTERNOON at exactly noon`() {
        assertEquals(ReminderWindow.AFTERNOON, IncompleteModuleReminder.classify(at(2026, 7, 7, 12), ZONE))
    }

    @Test
    fun `classify is AFTERNOON late evening`() {
        assertEquals(ReminderWindow.AFTERNOON, IncompleteModuleReminder.classify(at(2026, 7, 7, 23), ZONE))
    }

    @Test
    fun `todayKey formats as yyyy-MM-dd in zone`() {
        assertEquals("2026-07-07", IncompleteModuleReminder.todayKey(at(2026, 7, 7, 9), ZONE))
    }

    // ── shouldShow: suppression gates ─────────────────────────────────────────

    @Test
    fun `null when not loaded`() {
        assertNull(
            IncompleteModuleReminder.shouldShow(
                incompleteCount = 3,
                loaded = false,
                lastShownMorningDate = null,
                lastShownAfternoonDate = null,
                nowMillis = at(2026, 7, 7, 9),
                zone = ZONE,
            ),
        )
    }

    @Test
    fun `null when count is zero`() {
        assertNull(
            IncompleteModuleReminder.shouldShow(
                incompleteCount = 0,
                loaded = true,
                lastShownMorningDate = null,
                lastShownAfternoonDate = null,
                nowMillis = at(2026, 7, 7, 9),
                zone = ZONE,
            ),
        )
    }

    // ── shouldShow: first show per window ─────────────────────────────────────

    @Test
    fun `morning shown on first open with incomplete modules`() {
        assertEquals(
            ReminderWindow.MORNING,
            IncompleteModuleReminder.shouldShow(
                incompleteCount = 3,
                loaded = true,
                lastShownMorningDate = null,
                lastShownAfternoonDate = null,
                nowMillis = at(2026, 7, 7, 9),
                zone = ZONE,
            ),
        )
    }

    @Test
    fun `afternoon shown on first afternoon open`() {
        assertEquals(
            ReminderWindow.AFTERNOON,
            IncompleteModuleReminder.shouldShow(
                incompleteCount = 1,
                loaded = true,
                lastShownMorningDate = null,
                lastShownAfternoonDate = null,
                nowMillis = at(2026, 7, 7, 15),
                zone = ZONE,
            ),
        )
    }

    // ── shouldShow: once-per-window suppression ───────────────────────────────

    @Test
    fun `null when morning already shown today`() {
        assertNull(
            IncompleteModuleReminder.shouldShow(
                incompleteCount = 3,
                loaded = true,
                lastShownMorningDate = "2026-07-07",
                lastShownAfternoonDate = null,
                nowMillis = at(2026, 7, 7, 10),
                zone = ZONE,
            ),
        )
    }

    @Test
    fun `afternoon still shows when only morning was shown today`() {
        assertEquals(
            ReminderWindow.AFTERNOON,
            IncompleteModuleReminder.shouldShow(
                incompleteCount = 3,
                loaded = true,
                lastShownMorningDate = "2026-07-07",
                lastShownAfternoonDate = null,
                nowMillis = at(2026, 7, 7, 14),
                zone = ZONE,
            ),
        )
    }

    @Test
    fun `null when both windows already shown today`() {
        assertNull(
            IncompleteModuleReminder.shouldShow(
                incompleteCount = 3,
                loaded = true,
                lastShownMorningDate = "2026-07-07",
                lastShownAfternoonDate = "2026-07-07",
                nowMillis = at(2026, 7, 7, 14),
                zone = ZONE,
            ),
        )
    }

    // ── shouldShow: cross-day reset ───────────────────────────────────────────

    @Test
    fun `morning shows again on a new day even if shown yesterday`() {
        assertEquals(
            ReminderWindow.MORNING,
            IncompleteModuleReminder.shouldShow(
                incompleteCount = 3,
                loaded = true,
                lastShownMorningDate = "2026-07-06",
                lastShownAfternoonDate = "2026-07-06",
                nowMillis = at(2026, 7, 7, 9),
                zone = ZONE,
            ),
        )
    }
}
