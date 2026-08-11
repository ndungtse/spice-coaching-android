package com.medtroniclabs.microcoaching.domain.lifecycle

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder
import com.medtroniclabs.microcoaching.domain.telemetry.sha256Short

/**
 * TP-7 — `onVisitCompleted`. Runs when SPICE signals the CHW has finished a
 * patient visit. Three jobs:
 *
 *  1. **Backfill `patient_visit_id`** on every still-pending coaching_event
 *     row that this visit produced. `onAssessmentSubmitted` writes events
 *     before SPICE has resolved the final `encounterId` (BUG-5), so events
 *     land with `patient_visit_id IS NULL`; `onVisitCompleted` is the moment
 *     we know the visit ID and can stamp it on retroactively.
 *  2. **Emit `session_end`** — the closing system event for the visit.
 *  3. **Trigger an immediate sync** so the backend sees the visit-closure
 *     row + the now-stamped events within seconds of CHW finishing.
 *
 * Backfill is scoped by `(session_id = "sdk-hook", chw_id, sync_status =
 * 'pending')` — already-synced events are not mutated, and other sessions
 * (chat, learn) are not affected.
 */
class VisitCompletedHandler(
    private val coachingEventDao: CoachingEventDao,
    private val sessionId: String = SDK_HOOK_SESSION_ID,
) {

    suspend fun handle(
        chwId: String,
        encounterId: String,
        recorder: EventRecorder,
        flush: () -> Unit,
    ) {
        if (encounterId.isBlank()) {
            Log.w(TAG, "onVisitCompleted skipped — encounterId is blank")
            return
        }
        try {
            val updated = coachingEventDao.backfillPatientVisitId(
                sessionId = sessionId,
                chwId = chwId,
                encounterId = encounterId,
            )
            Log.i(
                TAG,
                "Visit close: backfilled patient_visit_id on $updated event(s) " +
                    "for chw=${chwId.sha256Short()} visit=$encounterId",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Visit-close backfill failed: ${e.message}", e)
        }

        try {
            recorder.recordSessionEnd()
        } catch (e: Exception) {
            Log.w(TAG, "session_end emission failed: ${e.message}", e)
        }

        flush()
    }

    companion object {
        private const val TAG = "VisitCompletedHandler"
        /** Matches `newSdkHookRecorder.sessionId` in MicroCoachingSDK. */
        const val SDK_HOOK_SESSION_ID = "sdk-hook"
    }
}
