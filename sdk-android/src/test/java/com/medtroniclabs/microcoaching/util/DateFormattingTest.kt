package com.medtroniclabs.microcoaching.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateFormattingTest {

    @Test
    fun `iso date label parses every backend timestamp shape`() {
        // Zulu, offset, and naive datetimes all share the same date prefix.
        assertEquals(isoDateLabel("2026-07-02T09:15:00Z"), isoDateLabel("2026-07-02T09:15:00+06:00"))
        assertEquals(isoDateLabel("2026-07-02T09:15:00Z"), isoDateLabel("2026-07-02T09:15:00"))
    }

    @Test
    fun `iso date label is null on blank or malformed input`() {
        assertNull(isoDateLabel(null))
        assertNull(isoDateLabel(""))
        assertNull(isoDateLabel("garbage"))
        assertNull(isoDateLabel("2026-07"))
    }
}
