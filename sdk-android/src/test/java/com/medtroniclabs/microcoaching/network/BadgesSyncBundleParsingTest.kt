package com.medtroniclabs.microcoaching.network

import com.medtroniclabs.microcoaching.util.LenientJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how `GET /sync/badges` decodes.
 *
 * A parse failure here empties the Badges tab wholesale — the response carries both
 * the catalogue and the CHW's earned badges, so there is no partial success. The
 * fixture is deliberately hostile: a real signed URL whose query string dwarfs the
 * object, fields the SDK doesn't model, and explicit `null`s where the schema shows
 * values (kotlinx rejects an explicit null for a non-nullable field even when it has
 * a default, so a nullable declaration is the only thing keeping those rows alive).
 */
class BadgesSyncBundleParsingTest {

    private val presignedUrl =
        "https://minio.example.org/medtronics/badges/first-step.png" +
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=key%2F20260811%2Fus-east-1%2Fs3%2F" +
            "aws4_request&X-Amz-Date=20260811T104257Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host" +
            "&X-Amz-Signature=6f1c9d0b5a4e3f2d1c0b9a8f7e6d5c4b3a29180706f5e4d3c2b1a09f8e7d6c5b"

    private fun parse(json: String) =
        LenientJson.decodeFromString(BadgesSyncBundle.serializer(), json)

    @Test
    fun `both lists parse and earned_at is what separates them`() {
        val bundle = parse(
            """
            {
              "available_badges": [
                {
                  "id": "b1",
                  "name": "First Step",
                  "domain": "ANC",
                  "image_storage_path": "badges/first-step.png",
                  "image_presigned_url": "$presignedUrl",
                  "image_presigned_expires_seconds": 86400,
                  "sequence": 1,
                  "module_ids": ["m1", "m2"]
                }
              ],
              "earned_badges": [
                {
                  "id": "b1",
                  "name": "First Step",
                  "domain": "ANC",
                  "image_storage_path": "badges/first-step.png",
                  "image_presigned_url": "$presignedUrl",
                  "image_presigned_expires_seconds": 86400,
                  "sequence": 1,
                  "module_ids": ["m1", "m2"],
                  "earned_at": "2026-08-11T10:42:57.710Z"
                }
              ],
              "server_time_utc": "2026-08-11T10:42:57.710Z"
            }
            """.trimIndent(),
        )

        val available = bundle.availableBadges.single()
        assertEquals("b1", available.id)
        assertEquals("First Step", available.name)
        assertEquals("ANC", available.domain)
        assertEquals("badges/first-step.png", available.imageStoragePath)
        assertEquals(presignedUrl, available.imagePresignedUrl)
        assertEquals(86_400L, available.imagePresignedExpiresSeconds)
        assertEquals(1, available.sequence)
        assertEquals(listOf("m1", "m2"), available.moduleIds)
        assertNull("available rows carry no earned_at", available.earnedAt)

        assertEquals("2026-08-11T10:42:57.710Z", bundle.earnedBadges.single().earnedAt)
        assertEquals("2026-08-11T10:42:57.710Z", bundle.serverTimeUtc)
    }

    @Test
    fun `unknown fields and explicit nulls do not fail the bundle`() {
        val bundle = parse(
            """
            {
              "available_badges": [
                {
                  "id": "b2",
                  "name": null,
                  "domain": null,
                  "image_storage_path": null,
                  "image_presigned_url": null,
                  "image_presigned_expires_seconds": null,
                  "sequence": null,
                  "tier": "gold",
                  "criteria": { "modules_completed": 5 }
                }
              ],
              "earned_badges": [],
              "server_time_utc": null,
              "next_review_at": "2026-09-01T00:00:00Z"
            }
            """.trimIndent(),
        )

        val badge = bundle.availableBadges.single()
        assertEquals("b2", badge.id)
        assertNull(badge.name)
        assertNull(badge.domain)
        assertNull(badge.imageStoragePath)
        assertNull(badge.imagePresignedUrl)
        assertNull(badge.imagePresignedExpiresSeconds)
        assertNull(badge.sequence)
        assertNull(badge.earnedAt)
        assertTrue("an absent module_ids defaults to empty", badge.moduleIds.isEmpty())
        assertNull(bundle.serverTimeUtc)
    }

    @Test
    fun `absent lists default to empty rather than failing`() {
        val bundle = parse("""{ "server_time_utc": "2026-08-11T10:42:57.710Z" }""")
        assertTrue(bundle.availableBadges.isEmpty())
        assertTrue(bundle.earnedBadges.isEmpty())
    }
}
