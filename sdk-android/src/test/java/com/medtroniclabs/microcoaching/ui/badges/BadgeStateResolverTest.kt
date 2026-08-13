package com.medtroniclabs.microcoaching.ui.badges

import com.medtroniclabs.microcoaching.data.db.entity.BadgeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the earned/locked rule and the journey numbering.
 *
 * A badge is earned or it isn't — there is no third state, so anything that
 * reintroduced one would put a treatment on the grid that the design no longer has.
 * The header's "X of N earned" is read straight off this result, so a miscount here
 * is visible on the first screen a CHW sees.
 */
class BadgeStateResolverTest {

    private fun row(id: String, rank: Int, earnedAt: String? = null, name: String = id) = BadgeEntity(
        badgeId = id,
        chwId = "chw-1",
        name = name,
        sequence = rank + 1,
        earnedAt = earnedAt,
        rank = rank,
    )

    @Test
    fun `earned_at is the only thing that decides state`() {
        val snapshot = listOf(
            row("a", 0, earnedAt = "2026-08-01T00:00:00Z"),
            row("b", 1, earnedAt = "2026-08-02T00:00:00Z"),
            row("c", 2),
            row("d", 3),
        ).toBadgesSnapshot()

        assertEquals(
            listOf(BadgeState.EARNED, BadgeState.EARNED, BadgeState.LOCKED, BadgeState.LOCKED),
            snapshot.badges.map { it.state },
        )
        assertEquals(2, snapshot.earnedCount)
        assertEquals(4, snapshot.totalCount)
    }

    @Test
    fun `the first unearned badge gets no special treatment`() {
        // There is no CURRENT/"NOW" state: the first unearned badge is locked like
        // every other unearned one.
        val snapshot = listOf(row("a", 0), row("b", 1), row("c", 2)).toBadgesSnapshot()

        assertTrue(snapshot.badges.all { it.state == BadgeState.LOCKED })
        assertEquals(0, snapshot.earnedCount)
    }

    @Test
    fun `a fully earned collection is all earned`() {
        val snapshot = listOf(
            row("a", 0, earnedAt = "2026-08-01T00:00:00Z"),
            row("b", 1, earnedAt = "2026-08-02T00:00:00Z"),
        ).toBadgesSnapshot()

        assertTrue(snapshot.badges.all { it.state == BadgeState.EARNED })
        assertEquals(2, snapshot.earnedCount)
    }

    @Test
    fun `an earned badge after an unearned one stays earned`() {
        // Badges are not strictly sequential — a CHW can earn a later one first.
        val snapshot = listOf(
            row("a", 0),
            row("b", 1, earnedAt = "2026-08-02T00:00:00Z"),
            row("c", 2),
        ).toBadgesSnapshot()

        assertEquals(
            listOf(BadgeState.LOCKED, BadgeState.EARNED, BadgeState.LOCKED),
            snapshot.badges.map { it.state },
        )
        assertEquals(1, snapshot.earnedCount)
    }

    @Test
    fun `milestones mirror the badges and are numbered from one`() {
        val snapshot = listOf(
            row("a", 0, earnedAt = "2026-08-01T00:00:00Z", name = "First Step"),
            row("b", 1, name = "Growth Tracker"),
        ).toBadgesSnapshot()

        assertEquals(listOf("1", "2"), snapshot.milestones.map { it.code })
        assertEquals(listOf("First Step", "Growth Tracker"), snapshot.milestones.map { it.title })
        assertEquals(snapshot.badges.map { it.state }, snapshot.milestones.map { it.state })
        assertEquals(snapshot.badges.map { it.id }, snapshot.milestones.map { it.id })
    }

    @Test
    fun `a zero-based or sparse sequence still numbers the path from one`() {
        // `sequence` only has to define an order; rendering it verbatim would show
        // a "0" as the first milestone.
        val snapshot = listOf(
            row("a", 0).copy(sequence = 0),
            row("b", 1).copy(sequence = 7),
        ).toBadgesSnapshot()

        assertEquals(listOf("1", "2"), snapshot.milestones.map { it.code })
    }

    @Test
    fun `no rows yields an empty snapshot`() {
        val snapshot = emptyList<BadgeEntity>().toBadgesSnapshot()
        assertTrue(snapshot.badges.isEmpty())
        assertTrue(snapshot.milestones.isEmpty())
        assertEquals(0, snapshot.totalCount)
    }
}
