package com.medtroniclabs.microcoaching.ui.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the checkpoint-throttle policy from docs/_events/video.md, encoded in
 * [VideoProgressReporter.shouldEmit]: emit on the first checkpoint, on a ≥5%
 * advance, once ≥10 s has elapsed, or on any forced (pause / exit / completion)
 * event — but stay quiet between those so telemetry volume stays low.
 */
class VideoProgressReporterThrottleTest {

    @Test
    fun `first checkpoint always emits`() {
        assertTrue(VideoProgressReporter.shouldEmit(force = false, lastEmittedPercent = -1.0, percent = 0.0, lastEmitMs = 0, nowMs = 0))
    }

    @Test
    fun `small delta within interval is suppressed`() {
        // 2% more, only 3s later → below both thresholds.
        assertFalse(
            VideoProgressReporter.shouldEmit(
                force = false, lastEmittedPercent = 40.0, percent = 42.0, lastEmitMs = 1_000, nowMs = 4_000,
            ),
        )
    }

    @Test
    fun `five percent advance emits`() {
        assertTrue(
            VideoProgressReporter.shouldEmit(
                force = false, lastEmittedPercent = 40.0, percent = 45.0, lastEmitMs = 1_000, nowMs = 3_000,
            ),
        )
    }

    @Test
    fun `ten second interval emits even with tiny delta`() {
        assertTrue(
            VideoProgressReporter.shouldEmit(
                force = false, lastEmittedPercent = 40.0, percent = 40.5, lastEmitMs = 0, nowMs = 10_000,
            ),
        )
    }

    @Test
    fun `forced emit bypasses the throttle`() {
        assertTrue(
            VideoProgressReporter.shouldEmit(
                force = true, lastEmittedPercent = 40.0, percent = 40.1, lastEmitMs = 1_000, nowMs = 1_100,
            ),
        )
    }
}
