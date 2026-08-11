package com.medtroniclabs.microcoaching.sync

import android.util.Log
import androidx.room.withTransaction
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.AssignedModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChatFaqEntity
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.DigitalProficiencyEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.LlmTraceEntity
import com.medtroniclabs.microcoaching.data.db.entity.decodeSourceDocumentRefs
import com.medtroniclabs.microcoaching.data.mapper.parseIsoMillis
import com.medtroniclabs.microcoaching.data.mapper.toConfigEntities
import com.medtroniclabs.microcoaching.data.mapper.toEntity
import com.medtroniclabs.microcoaching.data.mapper.toPayload
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.network.CoachingApiService
import com.medtroniclabs.microcoaching.data.db.entity.SourceDocumentThumbnailEntity
import com.medtroniclabs.microcoaching.data.localized.toJsonString
import com.medtroniclabs.microcoaching.network.SyncDefaults
import com.medtroniclabs.microcoaching.network.TelemetryBatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.util.UUID

// Outbound push path — extension functions on SyncApi, extracted verbatim (behaviour-preserving).
private const val TAG = "SyncApi"
private const val OUTBOUND_BATCH_SIZE = 200
private const val MAX_OUTBOUND_BATCHES = 50
private const val MAX_OUTBOUND_RETRIES = 5
private const val SYNCED_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

// ── Outbound ──────────────────────────────────────────────────────────────

/**
 * Push all pending coaching events, LLM traces, and digital events to the backend.
 *
 * Pages through the backlog in [OUTBOUND_BATCH_SIZE]-row batches per table
 * rather than materialising everything at once — a device offline for weeks
 * accumulates thousands of rows (traces carry full prompt/response text),
 * and one giant batch meant one giant entity list + payload tree + JSON
 * body on an already-tight heap.
 *
 * On success: marks all sent rows as `synced` in Room and continues to the
 * next batch until drained (or [MAX_OUTBOUND_BATCHES], a runaway guard).
 * On failure: increments retry counts; rows stay `pending` for the next attempt.
 * Always records a `sync_attempt` event per batch regardless of outcome.
 *
 * @return [OutboundResult] with aggregate counts and any failure reason.
 */
suspend fun SyncApi.pushPendingEvents(): OutboundResult {
    var syncedTotal = 0
    var failedTotal = 0
    var batches = 0

    while (batches < MAX_OUTBOUND_BATCHES) {
        val events = db.coachingEventDao().getPending(OUTBOUND_BATCH_SIZE)
        val traces = db.llmTraceDao().getPending(OUTBOUND_BATCH_SIZE)
        val digitalEvents = db.digitalProficiencyEventDao().getPending(OUTBOUND_BATCH_SIZE)

        if (events.isEmpty() && traces.isEmpty() && digitalEvents.isEmpty()) {
            if (batches == 0) {
                Log.d(TAG, "Nothing pending — skip outbound sync.")
                return OutboundResult(skipped = true)
            }
            break
        }
        batches++

        val result = pushBatch(events, traces, digitalEvents)
        if (!result.success) {
            // Surface the failure along with the progress already made;
            // remaining rows stay `pending` for the next worker run.
            return result.copy(
                syncedCount = syncedTotal + result.syncedCount,
                failedCount = failedTotal + result.failedCount,
            )
        }
        syncedTotal += result.syncedCount
        failedTotal += result.failedCount

        // Rejected rows stay `pending` until they hit the retry cap, so
        // looping again would re-push them within this same run. Stop and
        // let the next scheduled run retry them.
        if (result.failedCount > 0) break

        val drained = events.size < OUTBOUND_BATCH_SIZE &&
            traces.size < OUTBOUND_BATCH_SIZE &&
            digitalEvents.size < OUTBOUND_BATCH_SIZE
        if (drained) break
    }
    return OutboundResult(syncedCount = syncedTotal, failedCount = failedTotal)
}

/**
 * Delete synced telemetry rows older than [SYNCED_RETENTION_MS]. Called by
 * [OutboundSyncWorker] after a successful (or nothing-pending) push.
 *
 * Only `llm_trace` and `digital_proficiency_event` are pruned — they exist
 * purely to be shipped to the backend, and traces carry full prompt/response
 * text (the bulk of the growth). `coaching_event` rows are deliberately
 * RETAINED: quiz mastery (latest correct answer per question) and gap-state
 * replay read the full local history, so deleting old synced rows would
 * reset mastery and resurface completed refreshers.
 */
suspend fun SyncApi.pruneSyncedTelemetry(now: Long = System.currentTimeMillis()) {
    val cutoff = now - SYNCED_RETENTION_MS
    val traces = runCatching { db.llmTraceDao().deleteSyncedOlderThan(cutoff) }.getOrDefault(0)
    val digital = runCatching { db.digitalProficiencyEventDao().deleteSyncedOlderThan(cutoff) }.getOrDefault(0)
    if (traces > 0 || digital > 0) {
        Log.i(TAG, "Pruned synced telemetry — llm_trace=$traces digital_proficiency=$digital rows older than 30d.")
    }
}

/** Pushes one bounded batch — see [pushPendingEvents] for the paging loop. */
private suspend fun SyncApi.pushBatch(
    events: List<CoachingEventEntity>,
    traces: List<LlmTraceEntity>,
    digitalEvents: List<DigitalProficiencyEventEntity>,
): OutboundResult {
    // chw_id is passed through verbatim — backend accepts whatever
    // identifier shape the host supplies (SPICE forwards its own integer
    // user id today; backend has been relaxed to handle non-UUID values).
    val eventTypeBreakdown = events.groupingBy { it.eventType }.eachCount()
    Log.i(
        TAG,
        "Pushing pending events — coaching_events=${events.size} " +
            "llm_traces=${traces.size} digital_events=${digitalEvents.size} | " +
            "by_type=$eventTypeBreakdown",
    )

    val allPayloads = events.map { it.toPayload() } +
        traces.map { it.toPayload() } +
        digitalEvents.map { it.toPayload() }

    val batch = TelemetryBatch(
        events = allPayloads,
        sdkVersion = sdkVersion,
        chwId = chwId,
        tenantId = tenantId,
    )

    return try {
        val response = apiService.pushTelemetry(batch)
        val now = System.currentTimeMillis()

        if (response.isSuccessful) {
            val body = response.body()!!
            // The backend's ack splits ids into five buckets. We treat
            // `accepted`, `duplicates` (already ingested via Redis dedup),
            // and `buffered` (queued to backend Redis after ClickHouse
            // failure — HTTP 202 from our perspective) as "the backend has
            // them, mark synced". `rejected` (with per-event reasons in
            // `errors`) retry up to MAX_OUTBOUND_RETRIES, then escalate to
            // `failed` so the queue drains instead of cycling forever.
            val syncedSet = body.accepted.toSet() + body.duplicates.toSet() + body.buffered.toSet()
            val rejectedSet = body.rejected.toSet()

            val syncedEventIds   = events.map { it.eventId }.filter { it in syncedSet }
            val syncedTraceIds   = traces.map { it.id }.filter { it in syncedSet }
            val syncedDigitalIds = digitalEvents.map { it.id }.filter { it in syncedSet }

            if (syncedEventIds.isNotEmpty())   db.coachingEventDao().markSynced(syncedEventIds, now)
            if (syncedTraceIds.isNotEmpty())    db.llmTraceDao().markSynced(syncedTraceIds, now)
            if (syncedDigitalIds.isNotEmpty())  db.digitalProficiencyEventDao().markSynced(syncedDigitalIds, now)

            if (body.errors.isNotEmpty()) {
                body.errors.forEach { reason -> Log.w(TAG, "Event rejected by backend — $reason") }
            }

            if (rejectedSet.isNotEmpty()) {
                val rejectedEventIds = events.map { it.eventId }.filter { it in rejectedSet }
                val rejectedTraceIds = traces.map { it.id }.filter { it in rejectedSet }
                val rejectedDigitalIds = digitalEvents.map { it.id }.filter { it in rejectedSet }
                if (rejectedEventIds.isNotEmpty()) db.coachingEventDao().incrementRetryCount(rejectedEventIds)
                if (rejectedTraceIds.isNotEmpty()) db.llmTraceDao().incrementRetryCount(rejectedTraceIds)
                if (rejectedDigitalIds.isNotEmpty()) db.digitalProficiencyEventDao().incrementRetryCount(rejectedDigitalIds)

                // Move rows that just hit the retry cap to `failed` so they stop
                // being re-batched on every subsequent run. Applied symmetrically
                // to all three outbound tables — previously only coaching_event
                // capped, which meant permanently malformed traces / digital rows
                // cycled forever (caught in code review).
                if (rejectedEventIds.isNotEmpty()) {
                    val exhausted = db.coachingEventDao().getRetryCounts(rejectedEventIds)
                        .filter { it.retryCount >= MAX_OUTBOUND_RETRIES }
                        .map { it.eventId }
                    if (exhausted.isNotEmpty()) {
                        Log.w(TAG, "Giving up on ${exhausted.size} coaching_event row(s) after $MAX_OUTBOUND_RETRIES retries: $exhausted")
                        db.coachingEventDao().markFailed(exhausted)
                    }
                }
                if (rejectedTraceIds.isNotEmpty()) {
                    val n = db.llmTraceDao().markFailedIfExhausted(rejectedTraceIds, MAX_OUTBOUND_RETRIES)
                    if (n > 0) Log.w(TAG, "Giving up on $n llm_trace row(s) after $MAX_OUTBOUND_RETRIES retries")
                }
                if (rejectedDigitalIds.isNotEmpty()) {
                    val n = db.digitalProficiencyEventDao()
                        .markFailedIfExhausted(rejectedDigitalIds, MAX_OUTBOUND_RETRIES)
                    if (n > 0) Log.w(TAG, "Giving up on $n digital_proficiency_event row(s) after $MAX_OUTBOUND_RETRIES retries")
                }
            }

            recordSyncAttempt(success = true, networkState = "online")
            Log.i(
                TAG,
                "Outbound sync OK — sent: coaching_events=${events.size} " +
                    "llm_traces=${traces.size} digital_events=${digitalEvents.size} | " +
                    "accepted=${body.accepted.size} rejected=${body.rejected.size} " +
                    "duplicates=${body.duplicates.size} buffered=${body.buffered.size} " +
                    "errors=${body.errors.size}",
            )
            OutboundResult(syncedCount = syncedSet.size, failedCount = rejectedSet.size)
        } else {
            val errorMsg = "HTTP ${response.code()}"
            recordSyncAttempt(success = false, errorType = errorMsg, networkState = "online")
            Log.w(TAG, "Outbound sync server error: $errorMsg")
            OutboundResult(error = errorMsg, errorKind = httpKindFor(response.code()))
        }
    } catch (e: IOException) {
        recordSyncAttempt(success = false, errorType = e.javaClass.simpleName, networkState = "offline")
        Log.w(TAG, "Outbound sync network error: ${e.message}")
        OutboundResult(error = e.message ?: "network error", errorKind = SyncErrorKind.NETWORK)
    } catch (e: Exception) {
        recordSyncAttempt(success = false, errorType = e.javaClass.simpleName, networkState = "online")
        Log.w(TAG, "Outbound sync unexpected error: ${e.message}", e)
        OutboundResult(error = e.message ?: "unexpected error", errorKind = SyncErrorKind.UNEXPECTED)
    }
}
