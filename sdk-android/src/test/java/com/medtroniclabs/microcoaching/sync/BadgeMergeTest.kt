package com.medtroniclabs.microcoaching.sync

import com.medtroniclabs.microcoaching.network.BadgeSyncPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the two lists in `GET /sync/badges` become `badge` rows.
 *
 * The failure modes this guards are all silent: a badge id appearing in both lists
 * would collide on the primary key, an earned badge whose definition was
 * deactivated would disappear from the CHW's collection, and a mis-ordered `rank`
 * would scramble the Your Journey path — none of which surfaces as an error.
 */
class BadgeMergeTest {

    private fun payload(
        id: String,
        name: String? = null,
        sequence: Int? = null,
        earnedAt: String? = null,
        url: String? = null,
        expires: Long? = null,
        moduleIds: List<String> = emptyList(),
    ) = BadgeSyncPayload(
        id = id,
        name = name,
        sequence = sequence,
        earnedAt = earnedAt,
        imagePresignedUrl = url,
        imagePresignedExpiresSeconds = expires,
        moduleIds = moduleIds,
    )

    private val nowMillis = 1_786_442_293_000L

    @Test
    fun `a badge in both lists yields one row carrying its earned_at`() {
        val rows = mergeBadgePayloads(
            available = listOf(payload("b1", name = "First Step", sequence = 1)),
            earned = listOf(payload("b1", name = "First Step", sequence = 1, earnedAt = "2026-08-01T00:00:00Z")),
            chwId = "chw-1",
            nowMillis = nowMillis,
        )

        assertEquals(1, rows.size)
        assertEquals("b1", rows.single().badgeId)
        assertEquals("chw-1", rows.single().chwId)
        assertEquals("2026-08-01T00:00:00Z", rows.single().earnedAt)
    }

    @Test
    fun `an earned badge missing from the catalogue is kept`() {
        // Its definition was deactivated after the CHW earned it. Dropping the row
        // would take an earned badge away from them.
        val rows = mergeBadgePayloads(
            available = listOf(payload("b1", name = "First Step", sequence = 1)),
            earned = listOf(payload("retired", name = "Pilot Badge", sequence = 2, earnedAt = "2026-07-01T00:00:00Z")),
            chwId = "chw-1",
            nowMillis = nowMillis,
        )

        assertEquals(listOf("b1", "retired"), rows.map { it.badgeId })
        assertEquals("2026-07-01T00:00:00Z", rows.last().earnedAt)
    }

    @Test
    fun `rank follows sequence, with missing sequences last`() {
        val rows = mergeBadgePayloads(
            available = listOf(
                payload("c", name = "Charlie", sequence = 3),
                payload("z", name = "Zulu"),
                payload("a", name = "Alpha", sequence = 1),
                payload("b", name = "Bravo", sequence = 2),
            ),
            earned = emptyList(),
            chwId = "chw-1",
            nowMillis = nowMillis,
        )

        assertEquals(listOf("a", "b", "c", "z"), rows.map { it.badgeId })
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.rank })
        assertEquals("a sequence-less badge stores 0 but still sorts last", 0, rows.last().sequence)
    }

    @Test
    fun `sequence-less badges break the tie on name`() {
        val rows = mergeBadgePayloads(
            available = listOf(payload("y", name = "Yankee"), payload("x", name = "X-ray")),
            earned = emptyList(),
            chwId = "chw-1",
            nowMillis = nowMillis,
        )
        assertEquals(listOf("x", "y"), rows.map { it.badgeId })
    }

    @Test
    fun `the artwork lifetime becomes an absolute expiry trimmed by the safety margin`() {
        val rows = mergeBadgePayloads(
            available = listOf(payload("b1", sequence = 1, url = "https://example.org/a.png", expires = 86_400)),
            earned = emptyList(),
            chwId = "chw-1",
            nowMillis = nowMillis,
        )

        assertEquals("https://example.org/a.png", rows.single().imageUrl)
        assertEquals(1_786_442_293L + 86_400L - 10L, rows.single().imageExpiresAt)
        assertEquals(nowMillis, rows.single().lastSynced)
    }

    @Test
    fun `an absent artwork lifetime leaves the expiry null`() {
        val rows = mergeBadgePayloads(
            available = listOf(payload("b1", sequence = 1, url = "https://example.org/a.png")),
            earned = emptyList(),
            chwId = "chw-1",
            nowMillis = nowMillis,
        )
        assertNotNull(rows.single().imageUrl)
        assertNull(rows.single().imageExpiresAt)
    }

    @Test
    fun `module ids round-trip as a json array and stay null when absent`() {
        val rows = mergeBadgePayloads(
            available = listOf(
                payload("b1", sequence = 1, moduleIds = listOf("m1", "m2")),
                payload("b2", sequence = 2),
            ),
            earned = emptyList(),
            chwId = "chw-1",
            nowMillis = nowMillis,
        )

        assertEquals("""["m1","m2"]""", rows.first().moduleIds)
        assertNull(rows.last().moduleIds)
    }

    @Test
    fun `an empty response yields no rows`() {
        assertTrue(
            mergeBadgePayloads(emptyList(), emptyList(), chwId = "chw-1", nowMillis = nowMillis).isEmpty(),
        )
    }
}
