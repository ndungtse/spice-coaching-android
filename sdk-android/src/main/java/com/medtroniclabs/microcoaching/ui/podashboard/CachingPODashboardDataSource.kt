package com.medtroniclabs.microcoaching.ui.podashboard

import com.medtroniclabs.microcoaching.data.db.dao.DashboardCacheDao
import com.medtroniclabs.microcoaching.data.db.entity.DashboardCacheEntity
import com.medtroniclabs.microcoaching.util.StrictJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Offline cache decorator over a [PODashboardDataSource] (MED-I516).
 *
 * `loadDashboard` write-throughs each COMPLETE online result (`spineError == null`)
 * into the single-row [DashboardCacheDao], and serves that row — guarded by [chwId] —
 * when offline or when the API fails. A partial snapshot is shown live but never
 * overwrites a good cache, so offline never regresses to a degraded snapshot.
 *
 * The by-id detail methods and the "Show all" list methods are online-only and
 * delegate straight through.
 */
class CachingPODashboardDataSource(
    private val delegate: PODashboardDataSource,
    private val dao: DashboardCacheDao,
    private val isOnline: () -> Boolean,
    private val json: Json = StrictJson,
    private val now: () -> Long = { System.currentTimeMillis() },
) : PODashboardDataSource {

    override suspend fun loadDashboard(chwId: String, range: DateRange): PoDashboard {
        if (!isOnline()) return cachedOrThrow(chwId, cause = null)
        return runCatching { delegate.loadDashboard(chwId, range) }.fold(
            onSuccess = { fresh ->
                val stamped = fresh.copy(fetchedAt = now(), fromCache = false)
                // Only cache complete snapshots — never overwrite good data with a
                // spine-failed one.
                if (fresh.spineError == null) {
                    dao.upsert(
                        DashboardCacheEntity(
                            chwId = chwId,
                            fromDate = range.fromMillis.toApiDate(),
                            toDate = range.toMillis.toApiDate(),
                            payloadJson = json.encodeToString(stamped),
                            fetchedAt = stamped.fetchedAt,
                        ),
                    )
                }
                stamped
            },
            onFailure = { err -> cachedOrThrow(chwId, cause = err) },
        )
    }

    private suspend fun cachedOrThrow(chwId: String, cause: Throwable?): PoDashboard {
        val row = dao.get()?.takeIf { it.chwId == chwId }
        val cached = row?.let { runCatching { json.decodeFromString<PoDashboard>(it.payloadJson) }.getOrNull() }
        return cached?.copy(fromCache = true, fetchedAt = row.fetchedAt)
            ?: throw (cause ?: IllegalStateException("No cached dashboard available offline."))
    }

    // ── Online-only — delegate straight through ─────────────────────────────
    override suspend fun loadSkDetail(skId: String) = delegate.loadSkDetail(skId)
    override suspend fun loadSearchedModuleDetail(moduleId: String, range: DateRange) =
        delegate.loadSearchedModuleDetail(moduleId, range)
    override suspend fun loadSuggestionDetail(suggestionId: String) = delegate.loadSuggestionDetail(suggestionId)
    override suspend fun loadAllSearchedExisting(range: DateRange) = delegate.loadAllSearchedExisting(range)
    override suspend fun loadAllSearchedSuggested(range: DateRange) = delegate.loadAllSearchedSuggested(range)
    override suspend fun loadAllDocumentUsage(range: DateRange) = delegate.loadAllDocumentUsage(range)
    override suspend fun loadDocumentUsageDetail(documentId: String, range: DateRange) =
        delegate.loadDocumentUsageDetail(documentId, range)
}

/**
 * The production [PODashboardDataSource]: the API source wrapped in the offline cache
 * decorator. Shared by [PODashboardViewModel] and [PoSectionListViewModel] so the tab,
 * the KPI drill-downs, and the spine "Show all" lists all read/write the same cache.
 */
internal fun defaultPODashboardSource(): PODashboardDataSource {
    val sdk = com.medtroniclabs.microcoaching.MicroCoachingSDK.getInstance()
    val dao = com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
        .getInstance(sdk.config.context).dashboardCacheDao()
    return CachingPODashboardDataSource(
        delegate = ApiPODashboardDataSource(),
        dao = dao,
        isOnline = { sdk.isNetworkAvailable() },
    )
}
