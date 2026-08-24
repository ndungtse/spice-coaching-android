package com.medtroniclabs.microcoaching.domain.telemetry

import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.mapper.toPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * The wire contract for the three timestamp fields. Room stores one UTC epoch;
 * the mapper has to turn that into `timestamp_utc` (unchanged), `timestamp_local`
 * (device wall clock) and `event_date` (the CHW's calendar day).
 *
 * Every test pins the default zone to Asia/Dhaka (UTC+6, no DST) — the pilot
 * deployment's zone, and far enough from UTC that an unshifted value fails
 * visibly rather than passing on a CI box that happens to run in UTC.
 */
class SyncPayloadTimestampTest {

    private lateinit var originalZone: TimeZone

    /** 2023-11-14T22:13:20Z — 04:13 on the 15th in Dhaka, so the dates differ. */
    private val utcEvening = 1_700_000_000_000L
    private val sixHoursMs = 6 * 60 * 60 * 1000L

    @Before
    fun pinZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun entity(
        timestampLocal: Long = utcEvening,
        timestampUtc: Long? = null,
    ) = CoachingEventEntity(
        eventId = "evt-1",
        sdkVersion = "0.5.x",
        eventFamily = "coaching",
        sessionId = "sess-1",
        chwId = "chw-1",
        eventType = "module_quiz_attempted",
        timestampLocal = timestampLocal,
        timestampUtc = timestampUtc,
    )

    @Test
    fun `timestamp_local is the device wall clock, timestamp_utc is not shifted`() {
        val payload = entity().toPayload()

        assertEquals(utcEvening, payload.timestampUtc)
        assertEquals(utcEvening + sixHoursMs, payload.timestampLocal)
    }

    @Test
    fun `event_date is the local calendar day, not the UTC one`() {
        // 22:13 UTC on the 14th is already 04:13 on the 15th for the CHW. Dating
        // this in UTC files a whole morning of work under the previous day.
        assertEquals("2023-11-15", entity().toPayload().eventDate)
    }

    @Test
    fun `timestamp_utc is never null - it falls back to the stored epoch`() {
        // The backend coaching_events insert rejects a null timestamp_utc, and the
        // entity only carries a separate UTC value when NTP was available.
        assertEquals(utcEvening, entity(timestampUtc = null).toPayload().timestampUtc)
    }

    @Test
    fun `an NTP-corrected timestamp_utc wins over the stored epoch`() {
        val corrected = 1_699_999_999_000L
        val payload = entity(timestampLocal = utcEvening, timestampUtc = corrected).toPayload()

        assertEquals(corrected, payload.timestampUtc)
        assertEquals(corrected + sixHoursMs, payload.timestampLocal)
    }

    @Test
    fun `a UTC device leaves the two timestamps identical`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val payload = entity().toPayload()

        assertEquals(payload.timestampUtc, payload.timestampLocal)
        assertEquals("2023-11-14", payload.eventDate)
    }

    @Test
    fun `a west-of-UTC device shifts the day backwards`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Bogota")) // UTC-5, no DST
        val payload = entity().toPayload()

        assertEquals(utcEvening - 5 * 60 * 60 * 1000L, payload.timestampLocal)
        assertEquals("2023-11-14", payload.eventDate)
    }
}
