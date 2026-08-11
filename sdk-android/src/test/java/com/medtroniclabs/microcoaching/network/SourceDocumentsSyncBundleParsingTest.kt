package com.medtroniclabs.microcoaching.network

import com.medtroniclabs.microcoaching.util.LenientJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing contract for `GET /sync/source-documents`, the single endpoint behind
 * both the Knowledge grid and the Training sub-tab.
 *
 * Guards three things: both arrays deserialize into the same item type, the
 * audio/video rows are distinguishable from documents so the two consumers can be
 * fed from one response, and extra or explicitly-null fields never fail the bundle
 * — a whole-response failure would blank both surfaces at once.
 */
class SourceDocumentsSyncBundleParsingTest {

    @Test
    fun `both halves parse and playable media is distinguishable`() {
        val json = """
            {
              "source_documents": [
                {
                  "source_document_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "source_type": "pdf",
                  "title": "Recognizing Danger Signs",
                  "original_filename": "danger-signs.pdf",
                  "presigned_url": "https://cdn/danger.pdf?sig=abc",
                  "presigned_expires_seconds": 3600,
                  "thumbnail_presigned_url": "https://cdn/danger.png?sig=def",
                  "thumbnail_presigned_expires_seconds": 86400
                }
              ],
              "assigned_documents": [
                {
                  "source_document_id": "744804ee-2395-486f-93a0-960973649f91",
                  "source_type": "video",
                  "title": "ANC Visit Protocol",
                  "original_filename": "anc.mp4",
                  "assigned_at": "2026-07-31T10:34:38.434256Z",
                  "presigned_url": "https://cdn/anc.mp4?sig=ghi",
                  "presigned_expires_seconds": 3600
                }
              ],
              "server_time_utc": "2026-08-03T08:52:42.783240+00:00"
            }
        """.trimIndent()

        val bundle = LenientJson.decodeFromString(SourceDocumentsSyncBundle.serializer(), json)

        val doc = bundle.sourceDocuments.single()
        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", doc.sourceDocumentId)
        assertEquals(3600L, doc.presignedExpiresSeconds)
        assertEquals(86400L, doc.thumbnailPresignedExpiresSeconds)
        assertFalse("a pdf must not reach the video list", doc.isPlayableMedia)

        val video = bundle.assignedDocuments.single()
        assertEquals("744804ee-2395-486f-93a0-960973649f91", video.sourceDocumentId)
        assertEquals("2026-07-31T10:34:38.434256Z", video.assignedAt)
        assertTrue("a video must reach the video list", video.isPlayableMedia)
    }

    @Test
    fun `audio counts as playable and unknown types do not`() {
        fun itemOf(type: String?) = SourceDocumentSyncDownloadItem(sourceDocumentId = "x", sourceType = type)

        assertTrue(itemOf("audio").isPlayableMedia)
        assertTrue("matching is case-insensitive", itemOf("VIDEO").isPlayableMedia)
        assertFalse(itemOf("docx").isPlayableMedia)
        assertFalse("an absent type must not be assumed playable", itemOf(null).isPlayableMedia)
    }

    @Test
    fun `unknown fields and explicit nulls do not fail the bundle`() {
        // The backend also emits assignment metadata and tenant ids, and sends an
        // explicit null for URLs it couldn't presign. kotlinx rejects a null on a
        // non-nullable field even when it has a default, so a single null here
        // would otherwise blank both the Knowledge grid and the Training list.
        val json = """
            {
              "source_documents": [],
              "assigned_documents": [
                {
                  "source_document_id": "744804ee-2395-486f-93a0-960973649f91",
                  "source_type": "video",
                  "title": "ALL OF NUTRITION SCIENCE",
                  "original_filename": null,
                  "assignment_type": "individual",
                  "tenant_id": 0,
                  "user": { "id": 42, "name": "x", "role": "chw" },
                  "presigned_url": null,
                  "presigned_expires_seconds": null,
                  "thumbnail_presigned_url": null,
                  "thumbnail_presigned_expires_seconds": null
                }
              ],
              "server_time_utc": "2026-08-03T08:52:42Z"
            }
        """.trimIndent()

        val bundle = LenientJson.decodeFromString(SourceDocumentsSyncBundle.serializer(), json)

        val video = bundle.assignedDocuments.single()
        assertNull(video.presignedUrl)
        assertNull(video.presignedExpiresSeconds)
        assertNull(video.originalFilename)
        assertTrue(bundle.sourceDocuments.isEmpty())
    }

    @Test
    fun `absent arrays default to empty rather than failing`() {
        val bundle = LenientJson.decodeFromString(
            SourceDocumentsSyncBundle.serializer(),
            """{ "server_time_utc": "2026-08-03T08:52:42Z" }""",
        )

        assertTrue(bundle.sourceDocuments.isEmpty())
        assertTrue(bundle.assignedDocuments.isEmpty())
    }
}
