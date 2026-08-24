package com.medtroniclabs.microcoaching.network

import com.medtroniclabs.microcoaching.data.mapper.toEntity
import com.medtroniclabs.microcoaching.util.LenientJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module thumbnail arrives inline on `GET /sync/modules` and is the only
 * source for it, so this pins the path from wire to `module_cache` row.
 *
 * The fixture mirrors a real payload: `tenant_id` is a bare integer while the DTO
 * types it `String?`, and the presigned URL is a full signed S3 link whose query
 * string is longer than the rest of the object. Both have to survive, because a
 * parse failure here takes the whole modules bundle — and therefore the entire
 * module cache — down with it.
 */
class ModuleThumbnailPayloadTest {

    private val presignedUrl =
        "https://agent-qa.beehyv.com/medtronics-storage/ingest/thumbnails/" +
            "4f1db437-d8d8-4a81-bd7c-f8ac6c216b59.png?response-content-type=image%2Fpng" +
            "&response-content-disposition=inline%3B%20filename%3D%224f1db437.png%22" +
            "&X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Credential=microcoaching%2F20260811%2Fus-east-1%2Fs3%2Faws4_request" +
            "&X-Amz-Date=20260811T050416Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host" +
            "&X-Amz-Signature=7f720b83f8349e7fe3f344b135376470d033db92e42cad4094c8714e9fe62d33"

    private fun payloadJson(thumbnailFields: String) = """
        {
          "id": "164e2ab3-2000-4000-8000-000000000001",
          "module_family_id": "164e2ab3-2000-4000-8000-000000000002",
          "version": 3,
          "title": { "bn": "গর্ভকালীন পরিচর্যা", "en": "Antenatal care" },
          "domain": "maternal_health",
          "sub_domain": null,
          "module_type": "quiz",
          "content_domain": "clinical",
          "tenant_id": 2,
          "estimated_minutes": 10,
          "difficulty_level": "basic",
          "pass_threshold_override": null,
          "clinically_reviewed": true,
          "published_at": "2026-08-01T00:00:00Z",
          "updated_at": "2026-08-10T00:00:00Z",
          "source_documents": [],
          $thumbnailFields
          "search_metadata": { "audience": "chw_field_worker" },
          "primary_gap_id": "9771703c-096b-407a-8d18-a4539104824e",
          "behavioural_gap_ids": [],
          "cards": [],
          "quiz": []
        }
    """.trimIndent()

    private fun parse(thumbnailFields: String) =
        LenientJson.decodeFromString(ModuleSyncPayload.serializer(), payloadJson(thumbnailFields))

    @Test
    fun `inline thumbnail url and lifetime parse off the wire`() {
        val payload = parse(
            """
            "has_thumbnail": true,
            "thumbnail_presigned_url": "$presignedUrl",
            "thumbnail_presigned_expires_seconds": 86400,
            """.trimIndent(),
        )

        assertTrue(payload.hasThumbnail)
        assertEquals(presignedUrl, payload.thumbnailPresignedUrl)
        assertEquals(86400L, payload.thumbnailPresignedExpiresSeconds)
        // An integer tenant_id decoding into a String? field is what keeps the
        // whole bundle parseable; it is not incidental to this fixture.
        assertEquals("2", payload.tenantId)
    }

    @Test
    fun `the url reaches module_cache with an absolute expiry`() {
        val payload = parse(
            """
            "has_thumbnail": true,
            "thumbnail_presigned_url": "$presignedUrl",
            "thumbnail_presigned_expires_seconds": 86400,
            """.trimIndent(),
        )

        val syncedAtMillis = 1_786_442_293_000L
        val entity = payload.toEntity(syncedAtMillis)

        assertEquals(presignedUrl, entity.thumbnailUrl)
        assertTrue(entity.hasThumbnail)
        // Relative lifetime → absolute epoch second, anchored to this row's sync
        // time and trimmed by the 10s safety margin.
        assertEquals(1_786_442_293L + 86_400L - 10L, entity.thumbnailExpiresAtEpochSec)
    }

    @Test
    fun `a module flagged with a thumbnail but sent no url stores null, not a broken row`() {
        // What the device sees when the server sets the flag without presigning.
        // The row must still persist — the tile falls back to its placeholder.
        val payload = parse("\"has_thumbnail\": true,")

        val entity = payload.toEntity(1_786_442_293_000L)

        assertTrue(entity.hasThumbnail)
        assertNull(entity.thumbnailUrl)
        assertNull(entity.thumbnailExpiresAtEpochSec)
    }

    @Test
    fun `explicit nulls do not fail the module`() {
        val payload = parse(
            """
            "has_thumbnail": false,
            "thumbnail_presigned_url": null,
            "thumbnail_presigned_expires_seconds": null,
            """.trimIndent(),
        )

        assertNull(payload.thumbnailPresignedUrl)
        assertNull(payload.thumbnailPresignedExpiresSeconds)
        assertNull(payload.toEntity(1L).thumbnailUrl)
    }
}
