package com.medtroniclabs.microcoaching.ui.podashboard

import com.medtroniclabs.microcoaching.network.DocumentUsageDocumentRow
import com.medtroniclabs.microcoaching.network.DocumentUsageEventRow
import com.medtroniclabs.microcoaching.network.DocumentUsageResponse
import com.medtroniclabs.microcoaching.network.TeamActivitySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-mapping contract for the document-usage DTOs → UI models. */
class DocumentUsageMappersTest {

    private val range = DateRange(0L, 0L)
    private val docId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

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
    }
}
