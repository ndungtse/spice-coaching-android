package com.medtroniclabs.microcoaching.ui.podashboard

import com.medtroniclabs.microcoaching.network.DocumentUsageDocumentRow
import com.medtroniclabs.microcoaching.network.DocumentUsageEventRow
import com.medtroniclabs.microcoaching.network.DocumentUsageResponse
import com.medtroniclabs.microcoaching.network.TeamActivitySummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/** Pure-mapping contract for the document-usage DTOs → UI models. */
class DocumentUsageMappersTest {

    private val range = DateRange(0L, 0L)
    private val docId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    // View labels render in the device's zone, so pin it for the assertions.
    private val systemZone = TimeZone.getDefault()

    @Before
    fun pinZone() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restoreZone() = TimeZone.setDefault(systemZone)

    private fun row(
        id: String = docId,
        title: String? = "ANC Field Guide",
        views: Int = 12,
        users: Int = 4,
        lastBy: String? = "Amina Begum",
    ) = DocumentUsageDocumentRow(
        documentId = id,
        documentTitle = title,
        totalViews = views,
        uniqueUsers = users,
        lastViewedAt = null,
        lastViewedByUserId = 7,
        lastViewedByUserName = lastBy,
    )

    @Test
    fun `document row maps title counts and last reader`() {
        val mapped = row().toDocumentUsageRow()

        assertEquals(docId, mapped.documentId)
        assertEquals("ANC Field Guide", mapped.title)
        assertEquals(12, mapped.totalViews)
        assertEquals(4, mapped.uniqueUsers)
        assertEquals("Amina Begum", mapped.lastViewedBy)
    }

    @Test
    fun `falls back to the id when the backend cannot enrich a title`() {
        // Titles are resolved server-side; a deleted document record yields null.
        // Showing the id keeps the row tappable instead of rendering blank.
        assertEquals(docId, row(title = null).toDocumentUsageRow().title)
        assertEquals(docId, row(title = "  ").toDocumentUsageRow().title)
    }

    @Test
    fun `blank last reader becomes null rather than an empty name`() {
        assertNull(row(lastBy = "").toDocumentUsageRow().lastViewedBy)
    }

    @Test
    fun `dashboard carries the summary and falls back to page size for the total`() {
        val response = DocumentUsageResponse(
            totalViews = 30,
            uniqueDocuments = 2,
            uniqueUsers = 5,
            // total_document_rows unset (0) — the "Show all (N)" count must still
            // reflect the rows that actually came back.
            documents = listOf(row(), row(id = "doc-2", title = "Referral Handbook")),
        )

        val d = mapDashboard(
            range, TeamActivitySummary(), emptyList(), emptyList(), emptyList(),
            documentUsage = response,
        )

        assertEquals(2, d.documentUsage.size)
        assertEquals(2, d.documentUsageTotal)
        assertEquals(30, d.documentUsageSummary?.totalViews)
        assertEquals(2, d.documentUsageSummary?.uniqueDocuments)
        assertEquals(5, d.documentUsageSummary?.uniqueUsers)
    }

    @Test
    fun `dashboard without a document-usage response leaves the section empty`() {
        // The tab fetches document-usage best-effort; a 502 must not blank the tab.
        val d = mapDashboard(range, TeamActivitySummary(), emptyList(), emptyList(), emptyList())

        assertTrue(d.documentUsage.isEmpty())
        assertEquals(0, d.documentUsageTotal)
        assertNull(d.documentUsageSummary)
    }

    @Test
    fun `detail reads its totals from the matching document row`() {
        val response = DocumentUsageResponse(
            // Response-level KPIs are already narrowed by document_id, but prefer
            // the row so a mismatch can't silently report the wrong number.
            totalViews = 999,
            uniqueUsers = 999,
            documents = listOf(row(views = 12, users = 4)),
            totalEvents = 12,
            events = listOf(
                DocumentUsageEventRow(
                    eventId = "e1", documentId = docId, documentTitle = "ANC Field Guide",
                    userId = 7, userName = "Amina Begum", userRole = "SK",
                    upazilaId = "gazipur-sadar", district = "Gazipur",
                ),
            ),
        )

        val detail = response.toDocumentUsageDetail(docId)

        assertEquals("ANC Field Guide", detail.title)
        assertEquals(12, detail.totalViews)
        assertEquals(4, detail.uniqueUsers)
        assertEquals(1, detail.events.size)
        assertEquals("Amina Begum", detail.events[0].userName)
        assertEquals("SK", detail.events[0].userRole)
        // Upazila is the finer-grained of the two and wins over district.
        assertEquals("gazipur-sadar", detail.events[0].geography)
    }

    @Test
    fun `event falls back to district then to the user id`() {
        val event = DocumentUsageEventRow(
            eventId = "e1", documentId = docId,
            userId = 42, userName = null, userRole = null,
            upazilaId = null, district = "Gazipur",
        ).toDocumentViewEventItem()

        assertEquals("42", event.userName)
        assertNull(event.userRole)
        assertEquals("Gazipur", event.geography)
    }

    @Test
    fun `detail on an empty response still identifies the document`() {
        val detail = DocumentUsageResponse().toDocumentUsageDetail(docId)

        assertEquals(docId, detail.documentId)
        assertEquals(docId, detail.title)
        assertEquals(0, detail.totalViews)
        assertTrue(detail.events.isEmpty())
        assertTrue(detail.readers.isEmpty())
    }

    // ── Reader pivot ───────────────────────────────────────────────────────

    private fun open(
        userId: Int,
        name: String? = null,
        role: String? = "SK",
        upazila: String? = "gazipur-sadar",
        viewedAt: String? = null,
    ) = DocumentUsageEventRow(
        eventId = "e$userId-$viewedAt",
        documentId = docId,
        userId = userId,
        userName = name,
        userRole = role,
        upazilaId = upazila,
        viewedAt = viewedAt,
    ).toDocumentViewEventItem()

    @Test
    fun `readers collapse a user's opens into one row and count them`() {
        val readers = listOf(
            open(7, "Amina Begum", viewedAt = "2026-08-14T09:00:00"),
            open(7, "Amina Begum", viewedAt = "2026-08-15T06:30:00"),
            open(9, "Rahim Uddin", viewedAt = "2026-08-13T11:00:00"),
        ).toDocumentReaders()

        assertEquals(2, readers.size)
        assertEquals(7, readers[0].userId)
        assertEquals("Amina Begum", readers[0].userName)
        assertEquals("SK", readers[0].userRole)
        assertEquals("gazipur-sadar", readers[0].geography)
        // Ordered by opens descending, so the twice-opening reader leads.
        assertEquals(2, readers[0].opens)
        assertEquals(1, readers[1].opens)
    }

    @Test
    fun `readers group by user id, not display name`() {
        // Two different people who happen to share a name must not merge.
        val readers = listOf(
            open(7, "Amina Begum", viewedAt = "2026-08-15T09:00:00"),
            open(9, "Amina Begum", viewedAt = "2026-08-15T10:00:00"),
        ).toDocumentReaders()

        assertEquals(2, readers.size)
        assertEquals(setOf(7, 9), readers.map { it.userId }.toSet())
        assertTrue(readers.all { it.opens == 1 })
    }

    @Test
    fun `a reader's label comes from their most recent open`() {
        val readers = listOf(
            open(7, "Amina Begum", viewedAt = "2020-01-01T09:00:00"),
            open(7, "Amina Begum", viewedAt = "2020-01-03T14:30:00"),
            open(7, "Amina Begum", viewedAt = "2020-01-02T08:00:00"),
        ).toDocumentReaders()

        assertEquals(1, readers.size)
        assertEquals(3, readers[0].opens)
        // Well past the relative window, so the label is the absolute form of
        // the latest open — the 3rd, not the 1st or last in the list.
        assertEquals("3 Jan · 14:30", readers[0].lastViewedAtLabel)
    }

    @Test
    fun `reader identity fills in from whichever open carries it`() {
        // The wire leaves role/geography null per event, not per user.
        val readers = listOf(
            open(7, "Amina Begum", role = null, upazila = null, viewedAt = "2026-08-15T09:00:00"),
            open(7, "Amina Begum", role = "SK", upazila = "gazipur-sadar", viewedAt = "2026-08-15T10:00:00"),
        ).toDocumentReaders()

        assertEquals(1, readers.size)
        assertEquals("SK", readers[0].userRole)
        assertEquals("gazipur-sadar", readers[0].geography)
    }

    // ── View-time labels ───────────────────────────────────────────────────

    @Test
    fun `open time label is absolute once past yesterday`() {
        assertEquals("3 Jan · 14:30", open(7, viewedAt = "2020-01-03T14:30:00").viewedAtLabel)
        // Fractional seconds are what the API actually sends.
        assertEquals("3 Jan · 14:30", open(7, viewedAt = "2020-01-03T14:30:00.005").viewedAtLabel)
        // Offset and instant forms stay supported.
        assertEquals("3 Jan · 14:30", open(7, viewedAt = "2020-01-03T14:30:00Z").viewedAtLabel)
        assertEquals("3 Jan · 14:30", open(7, viewedAt = "2020-01-03T14:30:00+00:00").viewedAtLabel)
    }

    @Test
    fun `open time renders in the device zone, not UTC`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka")) // UTC+6
        // Late-evening UTC is the next morning for the reader looking at this.
        val event = open(7, viewedAt = "2020-01-03T23:30:00")

        assertEquals("4 Jan · 05:30", event.viewedAtLabel)
    }

    @Test
    fun `a bare date degrades to the day label rather than inventing a time`() {
        assertEquals("3 days ago", open(7, viewedAt = LocalDate.now().minusDays(3).toString()).viewedAtLabel)
    }

    @Test
    fun `an unparseable or missing view time leaves the label blank`() {
        assertEquals("", open(7, viewedAt = null).viewedAtLabel)
        assertEquals("", open(7, viewedAt = "not-a-date").viewedAtLabel)
        assertNull(open(7, viewedAt = null).viewedAtMillis)
    }

    @Test
    fun `detail pivots readers from the accumulated opens, not just this page`() {
        // The data source pages the opens, then hands the full list back in.
        val page = DocumentUsageResponse(
            documents = listOf(row(views = 3, users = 2)),
            totalEvents = 3,
            events = listOf(DocumentUsageEventRow(eventId = "e1", documentId = docId, userId = 7)),
        )
        val accumulated = listOf(
            open(7, "Amina Begum", viewedAt = "2026-08-15T09:00:00"),
            open(7, "Amina Begum", viewedAt = "2026-08-15T10:00:00"),
            open(9, "Rahim Uddin", viewedAt = "2026-08-15T11:00:00"),
        )

        val detail = page.toDocumentUsageDetail(docId, accumulated, truncated = true)

        assertEquals(3, detail.events.size)
        assertEquals(2, detail.readers.size)
        assertEquals(2, detail.readers[0].opens)
        assertTrue(detail.eventsTruncated)
    }
}
