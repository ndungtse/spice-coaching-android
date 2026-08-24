package com.medtroniclabs.microcoaching.sync

import com.medtroniclabs.microcoaching.data.db.entity.PublishedSourceDocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The catalogue's two halves share one table, so [mergeSourceDocumentRows] decides
 * what a document present in both ends up as.
 *
 * Both halves have to survive the merge, for different reasons: the assigned rows
 * are the only thing the Knowledge grid lists, and the module-linked rows are the
 * only thing a chat citation chip can resolve a URL against. Dropping either
 * silently breaks one of those surfaces.
 */
class SourceDocumentRowMergeTest {

    private fun row(
        id: String,
        assignedAt: String? = null,
        sourceType: String? = "pdf",
        title: String? = null,
    ) = PublishedSourceDocumentEntity(
        sourceDocumentId = id,
        sourceType = sourceType,
        title = title,
        assignedAt = assignedAt,
    )

    @Test
    fun `an assigned document keeps its assignment when it is also module-linked`() {
        val merged = mergeSourceDocumentRows(
            moduleLinked = listOf(row("doc-1", assignedAt = null, title = "as module source")),
            assigned = listOf(row("doc-1", assignedAt = "2026-08-09T12:06:02Z", title = "as assigned")),
        )

        val doc = merged.single()
        // Losing assigned_at here would drop the document out of the grid entirely.
        assertEquals("2026-08-09T12:06:02Z", doc.assignedAt)
        assertEquals("as assigned", doc.title)
    }

    @Test
    fun `module-linked documents survive so citations still resolve`() {
        val merged = mergeSourceDocumentRows(
            moduleLinked = listOf(row("cited-doc"), row("other-doc")),
            assigned = listOf(row("assigned-doc", assignedAt = "2026-08-10T00:00:00Z")),
        )

        val byId = merged.associateBy { it.sourceDocumentId }
        assertEquals(3, merged.size)
        assertNotNull("a cited module source must stay resolvable", byId["cited-doc"])
        assertNull("module-linked rows are not assigned", byId["cited-doc"]?.assignedAt)
        assertNotNull(byId["assigned-doc"]?.assignedAt)
    }

    @Test
    fun `repeated ids within one half collapse to a single row`() {
        // Re-ingested files can repeat a source id; the table's primary key would
        // otherwise drop the later one non-deterministically.
        val merged = mergeSourceDocumentRows(
            moduleLinked = listOf(row("dup", title = "first"), row("dup", title = "second")),
            assigned = emptyList(),
        )

        assertEquals(1, merged.size)
        assertEquals("second", merged.single().title)
    }

    @Test
    fun `both halves empty yields an empty list rather than throwing`() {
        assertEquals(emptyList<PublishedSourceDocumentEntity>(), mergeSourceDocumentRows(emptyList(), emptyList()))
    }

    @Test
    fun `playable assignments still land in the table for citation lookup`() {
        // Video and audio are filtered out of the grid by the DAO query, not here —
        // their URLs still have to be resolvable like any other document.
        val merged = mergeSourceDocumentRows(
            moduleLinked = emptyList(),
            assigned = listOf(row("vid", assignedAt = "2026-08-10T00:00:00Z", sourceType = "video")),
        )

        assertEquals("video", merged.single().sourceType)
    }
}
