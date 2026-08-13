package com.medtroniclabs.microcoaching.ui.podashboard

import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.network.CoachingApiService
import com.medtroniclabs.microcoaching.network.ProblemDetail
import com.medtroniclabs.microcoaching.network.TeamActivitySummary
import com.medtroniclabs.microcoaching.network.TeamMemberActivityDetail
import com.medtroniclabs.microcoaching.util.LenientJson
import retrofit2.Response
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Real [PODashboardDataSource] backed by the `dashboard/…` endpoints on
 * [CoachingApiService]. Fetches the wire DTOs and delegates all shaping to the
 * pure mappers in PODashboardMappers.kt. See
 * `docs/dashboads_and_leaderboard/dashboard_apis.md`.
 *
 * The `team-activity` endpoint is the spine — one call feeds the KPI cards, the
 * My-SKs list, the module-completion pivot, and the refresher rows. It identifies
 * the PO from the auth token, so [chwId] is not sent as a parameter.
 */
class ApiPODashboardDataSource(
    private val api: CoachingApiService = MicroCoachingSDK.getInstance().apiService,
) : PODashboardDataSource {

    override suspend fun loadDashboard(chwId: String, range: DateRange): PoDashboard {
        val from = range.fromMillis.toApiDate()
        val to = range.toMillis.toApiDate()

        // Each section is loaded best-effort — one failing endpoint (e.g. the analytics
        // spine returning 502) must not blank the whole dashboard.
        var summary = TeamActivitySummary()
        val members = mutableListOf<TeamMemberActivityDetail>()
        val spineError = runCatching {
            // Spine: pull the full roster (paginated) + the summary from the first page.
            var offset = 0
            var totalMembers = Int.MAX_VALUE
            while (offset < totalMembers) {
                val page = api.getTeamActivity(from, to, limit = PAGE, offset = offset).bodyOrThrow()
                if (offset == 0) summary = page.summary
                totalMembers = page.totalMembers.takeIf { it > 0 } ?: page.totalUsers
                members += page.members
                if (page.members.size < PAGE) break
                offset += PAGE
            }
        }.exceptionOrNull()?.message

        val existingResp = runCatching {
            api.getDigitalHelpModules(from, to, limit = TOP_K).bodyOrThrow()
        }.getOrNull()
        val suggestedResp = runCatching {
            api.getModuleCreationSuggestions(from, to, limit = TOP_K).bodyOrThrow()
        }.getOrNull()
        // The tab only previews a few rows, so the event drill-down isn't fetched
        // here — 1 is the smallest events_limit the backend accepts.
        val documentUsageResp = runCatching {
            api.getDocumentUsage(
                from, to,
                topLimit = TOP_K.coerceAtMost(DOCUMENT_TOP_LIMIT_MAX),
                documentsLimit = TOP_K,
                eventsLimit = 1,
            ).bodyOrThrow()
        }.getOrNull()

        return mapDashboard(
            range, summary, members,
            existing = existingResp?.modules ?: emptyList(),
            suggested = suggestedResp?.suggestions ?: emptyList(),
            spineError = spineError,
            documentUsage = documentUsageResp,
            // total_modules can be 0 in some responses — fall back to the page size so
            // "Show all" still appears when a full page came back.
            existingTotal = (existingResp?.totalModules ?: 0).coerceAtLeast(existingResp?.modules?.size ?: 0),
            suggestedTotal = (suggestedResp?.totalSuggestions ?: 0).coerceAtLeast(suggestedResp?.suggestions?.size ?: 0),
        )
    }

    override suspend fun loadAllSearchedExisting(range: DateRange): List<TopQuery> {
        val from = range.fromMillis.toApiDate()
        val to = range.toMillis.toApiDate()
        val items = mutableListOf<TopQuery>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total) {
            val page = api.getDigitalHelpModules(from, to, limit = PAGE, offset = offset).bodyOrThrow()
            page.modules.forEach { items += it.toExistingTopQuery(items.size) }
            total = page.totalModules.coerceAtLeast(items.size)
            if (page.modules.size < PAGE) break
            offset += PAGE
        }
        return items
    }

    override suspend fun loadAllSearchedSuggested(range: DateRange): List<TopQuery> {
        val from = range.fromMillis.toApiDate()
        val to = range.toMillis.toApiDate()
        val items = mutableListOf<TopQuery>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total) {
            val page = api.getModuleCreationSuggestions(from, to, limit = PAGE, offset = offset).bodyOrThrow()
            page.suggestions.forEach { items += it.toSuggestedTopQuery(items.size) }
            total = page.totalSuggestions.coerceAtLeast(items.size)
            if (page.suggestions.size < PAGE) break
            offset += PAGE
        }
        return items
    }

    override suspend fun loadAllDocumentUsage(range: DateRange): List<DocumentUsageRow> {
        val from = range.fromMillis.toApiDate()
        val to = range.toMillis.toApiDate()
        val items = mutableListOf<DocumentUsageRow>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (offset < total) {
            val page = api.getDocumentUsage(
                from, to,
                documentsLimit = PAGE,
                documentsOffset = offset,
                eventsLimit = 1,
            ).bodyOrThrow()
            page.documents.forEach { items += it.toDocumentUsageRow() }
            total = page.totalDocumentRows.coerceAtLeast(items.size)
            if (page.documents.size < PAGE) break
            offset += PAGE
        }
        return items
    }

    override suspend fun loadDocumentUsageDetail(
        documentId: String,
        range: DateRange,
    ): DocumentUsageDetail {
        val from = range.fromMillis.toApiDate()
        val to = range.toMillis.toApiDate()
        // `document_id` narrows every section, so one call yields both the
        // document's totals and its view list.
        val response = api.getDocumentUsage(
            from, to,
            documentId = documentId,
            documentsLimit = 1,
            eventsLimit = PAGE,
        ).bodyOrThrow()
        return response.toDocumentUsageDetail(documentId)
    }

    override suspend fun loadSkDetail(skId: String): SkDetail? {
        val range = defaultRange()
        val from = range.fromMillis.toApiDate()
        val to = range.toMillis.toApiDate()

        // No single-SK endpoint — locate the row in team-activity, then pull its questions.
        val row = findUser(skId, from, to) ?: return null
        val questions = runCatching {
            api.getTeamMemberQuestions(skId, from, to).bodyOrThrow().questions
        }.getOrDefault(emptyList())
        return mapSkDetail(skId, row, questions)
    }

    override suspend fun loadSearchedModuleDetail(moduleId: String, range: DateRange): SearchedModuleDetail {
        val from = range.fromMillis.toApiDate()
        val to = range.toMillis.toApiDate()

        val questionsResp = api.getDigitalHelpModuleQuestions(moduleId, from, to).bodyOrThrow()
        val requestedCount = runCatching {
            api.getDigitalHelpModuleRequests(moduleId, from, to).bodyOrThrow().moduleRequestedCount
        }.getOrDefault(0)
        return mapSearchedModuleDetail(moduleId, questionsResp, requestedCount)
    }

    override suspend fun loadSuggestionDetail(suggestionId: String): SuggestionDetail =
        mapSuggestionDetail(api.getModuleCreationSuggestion(suggestionId).bodyOrThrow())

    /** Locate one SK in the paginated team-activity roster. */
    private suspend fun findUser(skId: String, from: String, to: String): TeamMemberActivityDetail? {
        var offset = 0
        var totalMembers = Int.MAX_VALUE
        while (offset < totalMembers) {
            val page = api.getTeamActivity(from, to, limit = PAGE, offset = offset).bodyOrThrow()
            totalMembers = page.totalMembers.takeIf { it > 0 } ?: page.totalUsers
            page.members.firstOrNull { it.userId.toString() == skId }?.let { return it }
            if (page.members.size < PAGE) break
            offset += PAGE
        }
        return null
    }

    private fun <T> Response<T>.bodyOrThrow(): T {
        if (isSuccessful) return body() ?: error("The dashboard service returned an empty response")
        // Prefer the backend's problem+json message (e.g. "Analytics backend unavailable").
        val problem = errorBody()?.string()?.takeIf { it.isNotBlank() }?.let {
            runCatching { LenientJson.decodeFromString<ProblemDetail>(it) }.getOrNull()
        }
        error(problem?.detail ?: problem?.title ?: "The dashboard service is unavailable (HTTP ${code()})")
    }

    private companion object {
        /**
         * Page size for every paged dashboard call (loop until a short page).
         * 100 is the lowest `limit` any of these routes accepts, so one value is
         * safe across all of them — going higher 422s on the stricter ones.
         */
        const val PAGE = 100
        const val TOP_K = 20        // ranked top-searched rows to fetch
        /** `top_limit` caps lower than the page limits. */
        const val DOCUMENT_TOP_LIMIT_MAX = 50
    }
}

/** Last-7-days window (inclusive), as UTC start-of-day millis. */
internal fun defaultRange(): DateRange {
    val today = LocalDate.now(ZoneOffset.UTC)
    return DateRange(
        today.minusDays(6).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
}

/** UTC start-of-day millis → `YYYY-MM-DD`. */
internal fun Long.toApiDate(): String =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate().toString()

/**
 * ISO-8601 timestamp/date → a short relative label ("Today" / "Yesterday" /
 * "N days ago"). Blank on null/unparseable input.
 */
internal fun relativeDayLabel(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val date = runCatching { OffsetDateTime.parse(iso).toLocalDate() }
        .recoverCatching { Instant.parse(iso).atZone(ZoneOffset.UTC).toLocalDate() }
        .recoverCatching { LocalDate.parse(iso) }
        .getOrNull() ?: return ""
    val days = ChronoUnit.DAYS.between(date, LocalDate.now(ZoneOffset.UTC))
    return when {
        days <= 0L -> "Today"
        days == 1L -> "Yesterday"
        else -> "$days days ago"
    }
}
