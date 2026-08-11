package com.medtroniclabs.microcoaching.sdk.context

import android.content.SharedPreferences
import android.util.Log
import com.medtroniclabs.microcoaching.domain.context.CHWWorkContext
import com.medtroniclabs.microcoaching.domain.context.PatientSnapshot
import com.medtroniclabs.microcoaching.domain.context.TodaysVisit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the anonymised CHW work context the host pushes in — recent screenings
 * ([CHWWorkContext]), today's visits ([TodaysVisit]), and the last patient snapshot — so the
 * on-device chat / morning generator can read them back. Per-CHW `SharedPreferences`, rewritten
 * as one blob per home-open (hence the write-time caps).
 *
 * Extracted verbatim from `MicroCoachingSDK` (behaviour-preserving). [prefs] is a provider so
 * this never forces the facade's lazy `chwPrefs` at construction.
 */
internal class ChwContextStore(
    private val scope: CoroutineScope,
    private val prefs: () -> SharedPreferences,
) {
    fun updateContext(chwWorkContext: CHWWorkContext) {
        Log.d(TAG, "CHWContext [SDK] onCHWContextUpdated — count=${chwWorkContext.screenedTodayCount}, recentPatients=${chwWorkContext.recentPatients.size}")
        scope.launch { storeContext(chwWorkContext) }
    }

    private fun storeContext(ctx: CHWWorkContext) {
        // Cap the host-supplied list at write time. The value is rewritten as one
        // SharedPreferences XML blob on every home-screen open — an uncapped patient list means
        // a multi-hundred-KB serialize on the QueuedWork thread. The chat prompt clamps to a
        // char budget anyway, so entries beyond the newest MAX_RECENT_PATIENTS never reach the model.
        val capped = if (ctx.recentPatients.size > MAX_RECENT_PATIENTS) {
            Log.w(
                TAG,
                "onCHWContextUpdated: recentPatients=${ctx.recentPatients.size} " +
                    "exceeds cap — keeping the newest $MAX_RECENT_PATIENTS.",
            )
            ctx.copy(
                recentPatients = ctx.recentPatients
                    .sortedByDescending { it.screenedAtMs }
                    .take(MAX_RECENT_PATIENTS),
            )
        } else {
            ctx
        }
        val json = Json.encodeToString(capped)
        prefs().edit().putString("chw_work_context", json).apply()
    }

    /** Returns the last CHW work context pushed via [updateContext], or null if none stored. */
    fun loadContext(): CHWWorkContext? {
        val json = prefs().getString("chw_work_context", null) ?: return null
        return try {
            Json.decodeFromString<CHWWorkContext>(json)
        } catch (e: Exception) {
            Log.w(TAG, "CHWContext [SDK] Failed to decode CHWWorkContext: ${e.message}")
            null
        }
    }

    fun updateTodaysVisits(visits: List<TodaysVisit>) {
        Log.d(TAG, "CHWContext [SDK] onTodaysVisitsUpdated — visits=${visits.size}")
        scope.launch { storeTodaysVisits(visits) }
    }

    private fun storeTodaysVisits(visits: List<TodaysVisit>) {
        // Generous cap: a CHW's due-today list is tens of visits; hundreds+ means the host
        // passed an unfiltered follow-up table. Cap so the per-home-open prefs rewrite can't
        // balloon (see storeContext).
        val capped = if (visits.size > MAX_TODAYS_VISITS) {
            Log.w(
                TAG,
                "onTodaysVisitsUpdated: visits=${visits.size} exceeds cap — " +
                    "keeping the first $MAX_TODAYS_VISITS.",
            )
            visits.take(MAX_TODAYS_VISITS)
        } else {
            visits
        }
        val json = Json.encodeToString(capped)
        prefs().edit().putString("todays_visits", json).apply()
    }

    /** Returns the visits last pushed via [updateTodaysVisits], or empty if none/undecodable. */
    fun loadTodaysVisits(): List<TodaysVisit> {
        val json = prefs().getString("todays_visits", null) ?: return emptyList()
        return try {
            Json.decodeFromString<List<TodaysVisit>>(json)
        } catch (e: Exception) {
            Log.w(TAG, "CHWContext [SDK] Failed to decode todays_visits: ${e.message}")
            emptyList()
        }
    }

    fun storeLastPatientSnapshot(snapshot: PatientSnapshot) {
        val json = Json.encodeToString(snapshot)
        prefs().edit().putString("last_patient_snapshot", json).apply()
        Log.d(TAG, "CHWContext [SDK] Persisted PatientSnapshot: conditions=${snapshot.conditions}, risk=${snapshot.riskLevel}")
    }

    /** Returns the snapshot from the most recent assessment, or null if none stored. */
    fun loadLastPatientSnapshot(): PatientSnapshot? {
        val json = prefs().getString("last_patient_snapshot", null) ?: return null
        return try {
            Json.decodeFromString<PatientSnapshot>(json)
        } catch (e: Exception) {
            Log.w(TAG, "CHWContext [SDK] Failed to decode PatientSnapshot: ${e.message}")
            null
        }
    }

    private companion object {
        private const val TAG = "MicroCoachingSDK"

        /** Write-time cap on [CHWWorkContext.recentPatients]. */
        private const val MAX_RECENT_PATIENTS = 50

        /** Write-time cap on today's-visits list. */
        private const val MAX_TODAYS_VISITS = 200
    }
}
