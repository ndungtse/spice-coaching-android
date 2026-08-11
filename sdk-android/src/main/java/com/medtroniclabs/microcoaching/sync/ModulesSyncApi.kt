package com.medtroniclabs.microcoaching.sync

import android.util.Log
import androidx.room.withTransaction
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.AssignedModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.RequestedModuleEntity
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

// Modules sync (/sync/modules) — extension functions on SyncApi, extracted verbatim.
private const val TAG = "SyncApi"

// ── Inbound ───────────────────────────────────────────────────────────────

/**
 * Fetch published modules updated after [sinceWatermark]. Backend requires
 * a non-null `since` query param; the SDK supplies [SyncDefaults.EPOCH_ISO]
 * on first sync (empty cache) so the device gets the full catalogue.
 *
 * **Retirement handling.** A terminally-retired family (no published
 * version remains) is removed via two complementary signals:
 *  - **(A) explicit `retired_family_ids`** in the bundle — authoritative and
 *    incremental, applied on every pull (even a retirement-only delta with empty
 *    `modules`). The backend doesn't send this today, so it's currently a no-op;
 *    it activates automatically if/when the field appears.
 *  - **(B) full-catalogue reconcile** — on a `since=EPOCH` fetch
 *    ([forceFullCatalogue] or an empty cache), any locally-cached family absent
 *    from a **non-empty** full response is deleted. The fallback that needs no
 *    backend change. We never prune on an empty response, so a transient blank
 *    bundle can't wipe the cache.
 *
 * Both subtract the families present in this response, so neither can delete a
 * family the server just published (the supersede-vs-terminal safeguard).
 * Deleted families also have their `module_trigger_binding` rows removed.
 *
 * @param forceFullCatalogue force a `since=EPOCH` fetch so signal (B) can run
 *   even when a watermark exists. Driven periodically by [InboundSyncWorker].
 */
suspend fun SyncApi.pullModules(
    sinceWatermark: String?,
    forceFullCatalogue: Boolean = false,
): ModulesResult {
    val localCount = db.moduleDao().countActive()
    val wantFull = forceFullCatalogue || localCount == 0 || sinceWatermark.isNullOrBlank()
    if (wantFull && sinceWatermark != null && localCount != 0) {
        val why = if (forceFullCatalogue) "periodic retirement reconcile" else "cache empty"
        Log.i(TAG, "Modules: requesting full bundle ($why; ignoring stored watermark $sinceWatermark).")
    }
    val firstSince = if (wantFull) SyncDefaults.EPOCH_ISO else sinceWatermark!!
    val first = fetchAndApplyModules(firstSince)

    // Self-heal newly-assigned modules whose content predates the watermark.
    // The backend returns the *full* `assigned_module_ids` set on every call,
    // but the `modules` list is a watermark delta (content updated after
    // `since`). Assigning an *existing, unchanged* module to a CHW therefore
    // lands the assignment id without any module row — so it has no cached
    // content and never renders (the training filter matches against
    // `module_cache`). It would otherwise only appear after the daily
    // full-catalogue reconcile. When an incremental pull leaves such gaps,
    // do one full-catalogue fetch now to hydrate their content. See
    // [shouldHydrateFullCatalogue].
    if (first.success && shouldHydrateFullCatalogue(first.wasFullCatalogue, first.unresolvedAssignedCount)) {
        Log.i(
            TAG,
            "Modules: ${first.unresolvedAssignedCount} assigned module(s) missing content " +
                "after incremental pull — forcing full-catalogue fetch to hydrate.",
        )
        val full = fetchAndApplyModules(SyncDefaults.EPOCH_ISO)
        if (full.success) return full
        Log.w(TAG, "Full-catalogue hydrate fetch failed (${full.error}); keeping incremental result.")
    }
    return first
}

/**
 * Single `/sync/modules` call: the backend returns the published-catalogue delta
 * since [effectiveSince] (cached in `module_cache`, powers BM25) plus the
 * authenticated caller's full `assigned_module_ids` and request history. All
 * three tables are populated from this one response.
 *
 * [effectiveSince] is already resolved by [pullModules] — pass
 * [SyncDefaults.EPOCH_ISO] for a full-catalogue fetch.
 */
private suspend fun SyncApi.fetchAndApplyModules(effectiveSince: String): ModulesResult {
    return try {
        val isFullCatalogue = effectiveSince == SyncDefaults.EPOCH_ISO
        val response = apiService.pullModules(since = effectiveSince)
        val now = System.currentTimeMillis()

        if (response.isSuccessful) {
            val bundle = response.body()!!
            val rows = bundle.modules.map { it.toEntity(now) }
            val serverFamilies = rows.map { it.moduleFamilyId }.toSet()
            var prunedCount = 0
            var deletedRows = 0
            var deletedBindings = 0
            val toRetire = mutableSetOf<String>()

            // One transaction for the whole apply (upsert → version prune →
            // retirement delete): a single commit and a single invalidation
            // instead of one per step. Interleaved commits let concurrent
            // readers observe half-applied catalogues — and a delete landing
            // between a reader's CursorWindow fills was the fresh-install
            // "Couldn't read row N" crash (see ModuleDao's @Transaction note).
            db.withTransaction {
                if (rows.isNotEmpty()) {
                    db.moduleDao().upsertAll(rows)
                    // After upsert, drop any older versions of the same family
                    // so the cache only holds the latest published version.
                    serverFamilies.forEach { familyId ->
                        prunedCount += db.moduleDao().pruneOldVersions(familyId)
                    }
                }

                // ── Retirement — two complementary signals, unified into one delete ──
                // (A) Explicit `retired_family_ids` from the backend: authoritative and
                //     incremental, so it works even on a retirement-only delta (empty
                //     `modules`). No-op today since the backend doesn't send it yet.
                // (B) Full-catalogue reconcile: families absent from a *non-empty* full
                //     response have no published version remaining. The fallback that
                //     works without any backend change.
                // Both subtract `serverFamilies` so we never delete a family the server
                // just published (the supersede-vs-terminal safeguard from the review).
                toRetire += bundle.retiredFamilyIds.toSet() - serverFamilies              // (A)
                if (isFullCatalogue && rows.isNotEmpty()) {
                    toRetire += db.moduleDao().distinctFamilyIds().toSet() - serverFamilies // (B)
                }
                if (toRetire.isNotEmpty()) {
                    val retiredList = toRetire.toList()
                    deletedRows = db.moduleDao().deleteByFamilyIds(retiredList)
                    deletedBindings = db.moduleTriggerBindingDao().deleteByModuleFamilyIds(retiredList)
                    prunedCount += deletedRows
                }
            }
            if (toRetire.isNotEmpty()) {
                // Prefs write stays outside the DB transaction.
                syncPrefs?.addRetiredFamilyIds(toRetire)
                Log.i(TAG, "Modules retirement: removed ${toRetire.size} family(ies) " +
                    "($deletedRows rows, $deletedBindings bindings) " +
                    "[explicit=${bundle.retiredFamilyIds.size}, fullCatalogue=$isFullCatalogue]: $toRetire")
            }
            // ── Assigned modules ── populate the join table from this same
            // response. `assigned_module_ids` is the full current assignment set
            // for the CHW (the version `module.id`); we resolve each id's family
            // (from this bundle, else the cache) and reconcile. Skip entirely
            // when no CHW is signed in — there is nothing to scope them to. Never
            // delete on an empty list — mirrors the module-cache "never prune on
            // empty" safeguard, so a delta that omits the set can't wipe it.
            var unresolvedAssignedCount = 0
            val assignedCount = if (chwId.isNotBlank()) {
                val assignedRefs = parseAssignedRefs(bundle.assignedModuleIds)
                val familyByModuleId = rows.associate { it.moduleId to it.moduleFamilyId }
                val assignedRows = assignedRefs.map { ref ->
                    AssignedModuleEntity(
                        userId = chwId,
                        moduleId = ref.moduleId,
                        moduleFamilyId = familyByModuleId[ref.moduleId]
                            ?: db.moduleDao().getById(ref.moduleId)?.moduleFamilyId,
                        // Server-supplied assignment time (v3 `assigned_at`), parsed to
                        // epoch millis; null for the legacy id-only shape.
                        assignedAt = parseIsoMillis(ref.assignedAtIso),
                        lastSynced = now,
                    )
                }
                // An assigned module with no resolvable family has no content in
                // `module_cache` (not in this delta, not previously cached), so it
                // can't render. Count these so [pullModules] can hydrate them via a
                // full-catalogue fetch. A full-catalogue pull is authoritative — any
                // still-null family there is a genuinely missing/retired module, not
                // a watermark gap, so no further hydration would help.
                unresolvedAssignedCount = assignedRows.count { it.moduleFamilyId == null }
                val dao = db.assignedModuleDao()
                if (assignedRows.isNotEmpty()) {
                    dao.upsertAll(assignedRows)
                    dao.deleteForUserNotIn(chwId, assignedRefs.map { it.moduleId })
                }
                assignedRows.size
            } else {
                0
            }

            // The server's training-request history. Unlike the module set this is
            // the CHW's full history on every call, delta or not, so replacing it
            // wholesale can't lose rows. It only supplements the local
            // `module_requested` event log, which this never touches.
            val requestedCount = if (chwId.isNotBlank()) {
                val requestedRows = bundle.requestedModules.map { req ->
                    RequestedModuleEntity(
                        requestId = req.requestId,
                        chwId = chwId,
                        moduleId = req.moduleId,
                        requestedModuleName = req.requestedModuleName,
                        reason = req.reason,
                        submittedAt = req.submittedAt,
                        lastSynced = now,
                    )
                }.distinctBy { it.requestId }
                db.requestedModuleDao().replaceForUser(chwId, requestedRows)
                requestedRows.size
            } else {
                0
            }

            Log.i(
                TAG,
                "Modules sync OK: upserted=${rows.size} pruned=$prunedCount " +
                    "assigned=$assignedCount unresolvedAssigned=$unresolvedAssignedCount " +
                    "requested=$requestedCount " +
                    "families=${bundle.moduleFamilies.size} fullCatalogue=$isFullCatalogue " +
                    "server_time=${bundle.serverTimeUtc}",
            )
            ModulesResult(
                upsertedCount = rows.size,
                prunedCount = prunedCount,
                assignedCount = assignedCount,
                unresolvedAssignedCount = unresolvedAssignedCount,
                wasFullCatalogue = isFullCatalogue,
                newWatermark = bundle.serverTimeUtc,
            )
        } else {
            val errorMsg = "HTTP ${response.code()}"
            recordInboundFailure("inbound_modules", errorMsg)
            Log.w(TAG, "Modules sync server error: $errorMsg")
            ModulesResult(error = errorMsg, errorKind = httpKindFor(response.code()))
        }
    } catch (e: IOException) {
        recordInboundFailure("inbound_modules", e.javaClass.simpleName, offline = true)
        Log.w(TAG, "Modules sync network error: ${e.message}")
        ModulesResult(error = e.message ?: "network error", errorKind = SyncErrorKind.NETWORK)
    } catch (e: Exception) {
        recordInboundFailure("inbound_modules", e.javaClass.simpleName)
        Log.w(TAG, "Modules sync unexpected error: ${e.message}", e)
        ModulesResult(error = e.message ?: "unexpected error", errorKind = SyncErrorKind.UNEXPECTED)
    }
}
