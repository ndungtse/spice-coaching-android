package com.medtroniclabs.microcoaching.ui.video

import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.dao.AssignedVideoDao
import com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns raw player progress callbacks into (a) monotonic local progress writes
 * (so the card + resume anchor update immediately) and (b) throttled
 * `video_progress_updated` telemetry, following the cadence in
 * docs/_events/video.md:
 *
 *  - checkpoint every ~10 s **or** on a ≥5% progress delta,
 *  - a forced emit on pause / screen-exit,
 *  - a final completion emit (`percent_watched = 100`, `completed = true`) that
 *    also flushes telemetry promptly.
 *
 * The local DB write happens on **every** callback (cheap + monotonic); only the
 * telemetry emit is throttled, so on-device resume/progress stays accurate while
 * outbound volume stays low. Wired to a UI-lifecycle scope (the Activity's
 * `lifecycleScope`) — DB/telemetry work is dispatched to IO.
 */
internal class VideoProgressReporter(
    private val videoId: String,
    private val chwId: String,
    private val dao: AssignedVideoDao,
    private val recorder: EventRecorder,
    private val scope: CoroutineScope,
) {

    private var lastEmittedPercent = -1.0
    private var lastEmitMs = 0L
    private var completedEmitted = false

    /** Periodic checkpoint while playing — telemetry emitted only past the throttle. */
    fun onCheckpoint(positionMs: Long, durationMs: Long) = handle(positionMs, durationMs, force = false)

    /** Pause / screen-exit — a forced telemetry emit regardless of the throttle. */
    fun onFlush(positionMs: Long, durationMs: Long) = handle(positionMs, durationMs, force = true)

    /** Playback reached the end — final 100% / completed emit, flushed promptly. */
    fun onCompleted(durationMs: Long) {
        if (completedEmitted) return
        completedEmitted = true
        val dur = durationMs.coerceAtLeast(0L)
        scope.launch(Dispatchers.IO) {
            runCatching { dao.updateProgress(videoId, chwId, dur, 100.0, completed = true, watchedAt = nowIso()) }
                .onFailure { Log.w(TAG, "completed DB update failed: ${it.message}") }
            runCatching { recorder.recordVideoProgress(videoId, dur, 100.0, completed = true) }
                .onFailure { Log.w(TAG, "completed telemetry failed: ${it.message}") }
            runCatching { MicroCoachingSDK.getInstance().flushTelemetryNow() }
        }
    }

    private fun handle(positionMs: Long, durationMs: Long, force: Boolean) {
        if (completedEmitted) return
        val pos = positionMs.coerceAtLeast(0L)
        val dur = durationMs.coerceAtLeast(0L)
        val percent = if (dur > 0L) (pos.toDouble() / dur * 100.0).coerceIn(0.0, 100.0) else 0.0

        // Always keep local resume state fresh (monotonic in the DAO). completed
        // stays false here — authoritative completion comes from onCompleted().
        val watchedAt = nowIso()
        scope.launch(Dispatchers.IO) {
            runCatching { dao.updateProgress(videoId, chwId, pos, percent, completed = false, watchedAt = watchedAt) }
                .onFailure { Log.w(TAG, "progress DB update failed: ${it.message}") }
            // The player already knows the exact length, so record it here rather
            // than paying to discover it elsewhere. A no-op once it is known.
            runCatching { dao.updateDurationIfUnknown(videoId, chwId, dur) }
                .onFailure { Log.w(TAG, "duration DB update failed: ${it.message}") }
        }

        val now = System.currentTimeMillis()
        if (!shouldEmit(force, lastEmittedPercent, percent, lastEmitMs, now)) return

        lastEmittedPercent = percent
        lastEmitMs = now
        scope.launch(Dispatchers.IO) {
            runCatching { recorder.recordVideoProgress(videoId, pos, percent, completed = false) }
                .onFailure { Log.w(TAG, "progress telemetry failed: ${it.message}") }
        }
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    companion object {
        private const val TAG = "VideoProgressReporter"

        /** Emit telemetry at most once per this interval unless a delta/force fires. */
        const val MIN_INTERVAL_MS = 10_000L

        /** …or whenever watched-percent advances by at least this much since the last emit. */
        const val MIN_PERCENT_DELTA = 5.0

        /**
         * The throttle decision from docs/_events/video.md, extracted pure so it's
         * unit-testable: emit on a forced event (pause / exit / completion), on the
         * first checkpoint ([lastEmittedPercent] < 0), on a ≥[MIN_PERCENT_DELTA]
         * advance, or once [MIN_INTERVAL_MS] has elapsed since the last emit.
         */
        fun shouldEmit(
            force: Boolean,
            lastEmittedPercent: Double,
            percent: Double,
            lastEmitMs: Long,
            nowMs: Long,
        ): Boolean = force ||
            lastEmittedPercent < 0.0 ||
            percent - lastEmittedPercent >= MIN_PERCENT_DELTA ||
            nowMs - lastEmitMs >= MIN_INTERVAL_MS
    }
}
