package com.medtroniclabs.microcoaching.domain.lifecycle

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.domain.telemetry.sha256Short

/**
 * `onVisitCompleted`. Runs when SPICE signals the CHW has finished a patient
 * visit. Two jobs:
 *
 *  1. **Backfill `patient_visit_id`** on every still-pending coaching_event
 *     row that this visit produced. Hook-originated events are written before
 *     SPICE has resolved the final `encounterId`, so they land with
 *     `patient_visit_id IS NULL`; this is the moment the visit id is known and
 *     can be stamped on retroactively.
 *  2. **Trigger an immediate sync** so the backend sees the now-stamped events
 *     within seconds of the CHW finishing.
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

        flush()
    }

    companion object {
        private const val TAG = "VisitCompletedHandler"
        /** Matches `newSdkHookRecorder.sessionId` in MicroCoachingSDK. */
        const val SDK_HOOK_SESSION_ID = "sdk-hook"
    }
}
