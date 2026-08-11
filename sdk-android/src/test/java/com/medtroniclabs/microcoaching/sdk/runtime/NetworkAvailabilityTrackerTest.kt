package com.medtroniclabs.microcoaching.sdk.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Wi-Fi-handover regression: a single boolean flipped to offline whenever *any* network
 * was lost, so joining Wi-Fi while on cellular reported offline the moment Android dropped
 * the redundant cellular link.
 */
class NetworkAvailabilityTrackerTest {

    private val wifi = "wifi"
    private val cellular = "cellular"

    @Test
    fun `starts offline until a network reports in`() {
        assertFalse(NetworkAvailabilityTracker().isAvailable)
    }

    @Test
    fun `losing a redundant network while another is up stays online`() {
        val tracker = NetworkAvailabilityTracker()
        tracker.onAvailable(cellular)
        tracker.onAvailable(wifi)

        // Android tears down the now-redundant cellular link after the Wi-Fi handover.
        val nowOffline = tracker.onLost(cellular)

        assertFalse("losing cellular must not report offline while Wi-Fi is up", nowOffline)
        assertTrue(tracker.isAvailable)
    }

    @Test
    fun `losing the last network reports offline`() {
        val tracker = NetworkAvailabilityTracker()
        tracker.onAvailable(wifi)

        assertTrue(tracker.onLost(wifi))
        assertFalse(tracker.isAvailable)
    }

    @Test
    fun `first network up is reported as an offline to online transition`() {
        val tracker = NetworkAvailabilityTracker()

        assertTrue("first network up is a restore", tracker.onAvailable(wifi))
        assertFalse("a second network is not another restore", tracker.onAvailable(cellular))
    }

    @Test
    fun `re-adding an already-up network does not double count`() {
        val tracker = NetworkAvailabilityTracker()
        tracker.onAvailable(wifi)
        tracker.onAvailable(wifi)

        assertTrue("one loss of the only network means offline", tracker.onLost(wifi))
        assertFalse(tracker.isAvailable)
    }

    @Test
    fun `losing an untracked network changes nothing`() {
        val tracker = NetworkAvailabilityTracker()
        tracker.onAvailable(wifi)

        assertFalse(tracker.onLost(cellular))
        assertTrue(tracker.isAvailable)
    }

    @Test
    fun `losing the last network when already offline is not a new transition`() {
        val tracker = NetworkAvailabilityTracker()

        assertFalse(tracker.onLost(wifi))
        assertFalse(tracker.isAvailable)
    }

    @Test
    fun `reconnecting after a full drop is a fresh restore`() {
        val tracker = NetworkAvailabilityTracker()
        tracker.onAvailable(wifi)
        tracker.onLost(wifi)

        assertTrue(tracker.onAvailable(cellular))
        assertTrue(tracker.isAvailable)
    }

    @Test
    fun `clear drops every tracked network`() {
        val tracker = NetworkAvailabilityTracker()
        tracker.onAvailable(wifi)
        tracker.onAvailable(cellular)

        tracker.clear()

        assertFalse(tracker.isAvailable)
    }
}
