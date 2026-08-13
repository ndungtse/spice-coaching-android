package com.medtroniclabs.microcoaching.network

import com.medtroniclabs.microcoaching.util.LenientJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing contracts for the payloads added alongside the batch presign endpoint.
 *
 * `missing_paths` matters most: it is how the server declines a path it won't sign,
 * and treating that as a failure would hide the reason rich card media doesn't load.
 */
class SyncPayloadAdditionsParsingTest {

    @Test
    fun `presign response separates signed paths from declined ones`() {
        val json = """
            {
              "urls": [
                {
                  "storage_path": "medtronics/media/abc_photo.png",
                  "presigned_url": "https://cdn/media/abc_photo.png?sig=xyz",
                  "expires_seconds": 86400
                }
              ],
              "missing_paths": ["media/no-bucket-prefix.png"],
              "server_time_utc": "2026-08-12T15:09:20Z"
            }
        """.trimIndent()

        val body = LenientJson.decodeFromString(StoragePathsPresignResponse.serializer(), json)

        val signed = body.urls.single()
        assertEquals("medtronics/media/abc_photo.png", signed.storagePath)
        assertEquals(86400L, signed.expiresSeconds)
        assertEquals(listOf("media/no-bucket-prefix.png"), body.missingPaths)
    }

    @Test
    fun `a response that signs nothing still parses`() {
        // What a bucket-relative object_name currently produces. It has to read as
        // "declined", not as a malformed response.
        val body = LenientJson.decodeFromString(
            StoragePathsPresignResponse.serializer(),
            """{ "urls": [], "missing_paths": ["media/x.png"], "server_time_utc": "2026-08-12T15:09:20Z" }""",
        )

        assertTrue(body.urls.isEmpty())
        assertEquals(listOf("media/x.png"), body.missingPaths)
    }

    @Test
    fun `video progress parses and an empty delta is not an error`() {
        val json = """
            {
              "videos": [
                {
                  "source_document_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "last_position_ms": 125000,
                  "percent_watched": 42.5,
                  "completed": false,
                  "last_watched_at": "2026-08-12T15:09:20.404Z"
                }
              ],
              "server_time_utc": "2026-08-12T15:10:00Z"
            }
        """.trimIndent()

        val body = LenientJson.decodeFromString(VideoProgressSyncBundle.serializer(), json)
        val row = body.videos.single()
        assertEquals(125_000L, row.lastPositionMs)
        assertEquals(42.5, row.percentWatched, 0.0001)
        assertEquals("2026-08-12T15:10:00Z", body.serverTimeUtc)

        val empty = LenientJson.decodeFromString(
            VideoProgressSyncBundle.serializer(),
            """{ "videos": [], "server_time_utc": "2026-08-12T15:10:00Z" }""",
        )
        assertTrue("nothing changed since the watermark", empty.videos.isEmpty())
    }

    @Test
    fun `an unprobed video parses with no duration`() {
        // The backend ships the field before it has probed the media, so null is the
        // normal state — it must not fail the row, and the UI renders no duration.
        val item = LenientJson.decodeFromString(
            SourceDocumentsSyncBundle.serializer(),
            """
            {
              "assigned_documents": [
                { "source_document_id": "x", "source_type": "video", "duration_ms": null }
              ],
              "server_time_utc": "2026-08-12T15:10:00Z"
            }
            """.trimIndent(),
        ).assignedDocuments.single()

        assertNull(item.durationMs)
    }

    @Test
    fun `catalogue rows carry description, duration and storage paths`() {
        val json = """
            {
              "source_documents": [],
              "assigned_documents": [
                {
                  "source_document_id": "744804ee-2395-486f-93a0-960973649f91",
                  "source_type": "video",
                  "title": "ANC Visit Protocol",
                  "description": "A short refresher on the visit sequence.",
                  "duration_ms": 420000,
                  "storage_path": "medtronics/ingest/anc.mp4",
                  "thumbnail_storage_path": "medtronics/ingest/thumbnails/anc.png",
                  "presigned_url": "https://cdn/anc.mp4?sig=abc",
                  "presigned_expires_seconds": 3600
                }
              ],
              "server_time_utc": "2026-08-12T15:10:00Z"
            }
        """.trimIndent()

        val item = LenientJson
            .decodeFromString(SourceDocumentsSyncBundle.serializer(), json)
            .assignedDocuments
            .single()

        assertEquals("A short refresher on the visit sequence.", item.description)
        assertEquals(420_000L, item.durationMs)
        assertEquals("medtronics/ingest/anc.mp4", item.storagePath)
        assertEquals("medtronics/ingest/thumbnails/anc.png", item.thumbnailStoragePath)
    }
}
