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
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

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
        val spineFailure = runCatching {
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
        }.exceptionOrNull()
        val spineError = spineFailure?.message

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
            spineErrorIsAuth = spineFailure.isDashboardAuthError(),
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
        // `document_id` narrows every section, so the first call yields both the
        // document's totals and the first page of its opens.
        val first = api.getDocumentUsage(
            from, to,
            documentId = documentId,
            documentsLimit = 1,
            eventsLimit = PAGE,
        ).bodyOrThrow()

        // The readers list is pivoted from the opens, so stopping at one page
        // would under-count it — walk the rest, up to [EVENT_MAX]. Only worth
        // paging when the first page came back full.
        val events = first.events.mapTo(mutableListOf()) { it.toDocumentViewEventItem() }
        var truncated = false
        if (first.events.size == PAGE) {
            while (events.size < first.totalEvents) {
                if (events.size >= EVENT_MAX) {
                    truncated = true
                    break
                }
                val page = api.getDocumentUsage(
                    from, to,
                    documentId = documentId,
                    documentsLimit = 1,
                    eventsLimit = PAGE,
                    eventsOffset = events.size,
                ).bodyOrThrow()
                if (page.events.isEmpty()) break
                page.events.mapTo(events) { it.toDocumentViewEventItem() }
                if (page.events.size < PAGE) break
            }
        }
        return first.toDocumentUsageDetail(documentId, events, truncated)
    }

    override suspend fun loadSkDetail(skId: String, range: DateRange): SkDetail? {
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
        val message = problem?.detail ?: problem?.title ?: "The dashboard service is unavailable (HTTP ${code()})"
        // A 401 is a stale/expired session — a pull-to-refresh retry can't fix it, so surface it
        // as a distinct auth error the UI can turn into "log out and back in" guidance.
        if (code() == 401 || problem?.status == 401 || problem?.code == "not_authenticated") {
            throw DashboardAuthException(message)
        }
        error(message)
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
        /**
         * Ceiling on the opens pulled for one document's drill-down. A heavily
         * read document would otherwise cost a call per 100 opens; past this the
         * screen says so rather than showing a silently partial list.
         */
        const val EVENT_MAX = PAGE * 10
    }
}

/** A dashboard call failed with HTTP 401 — the session is invalid/expired (auth, not network). */
class DashboardAuthException(message: String) : Exception(message)

/** True when a failure is an expired/invalid session (HTTP 401), i.e. a re-login is needed. */
internal fun Throwable?.isDashboardAuthError(): Boolean = this is DashboardAuthException

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
 * ISO-8601 timestamp/date → the instant it names, or null if it is blank or
 * unparseable. Accepts all four shapes the dashboard routes emit: an offset
 * timestamp, a `Z` instant, a zone-less timestamp, and a plain date. The
 * zone-less case is the common one — these values come from ClickHouse
 * `DateTime64(3)` columns, which carry no zone, so the API renders them bare;
 * they are UTC by column convention (`timestamp_utc`) and read as such.
 */
internal fun parseApiInstant(iso: String?): Instant? {
    if (iso.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(iso).toInstant() }
        .recoverCatching { Instant.parse(iso) }
        .recoverCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) }
        .recoverCatching { LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant() }
        .getOrNull()
}

/**
 * ISO-8601 timestamp/date → the calendar day it falls on for the reader. A bare
 * date is already a calendar day and is taken as-is; anything carrying a time is
 * an instant, and lands on whichever day it is in the device's zone.
 */
private fun apiLocalDate(iso: String?): LocalDate? {
    if (iso.isNullOrBlank()) return null
    return runCatching { LocalDate.parse(iso) }
        .getOrNull()
        ?: parseApiInstant(iso)?.atZone(ZoneId.systemDefault())?.toLocalDate()
}

/**
 * ISO-8601 timestamp/date → a short relative label ("Today" / "Yesterday" /
 * "N days ago"). Blank on null/unparseable input.
 */
internal fun relativeDayLabel(iso: String?): String {
    val date = apiLocalDate(iso) ?: return ""
    val days = ChronoUnit.DAYS.between(date, LocalDate.now(ZoneId.systemDefault()))
    return when {
        days <= 0L -> "Today"
        days == 1L -> "Yesterday"
        else -> "$days days ago"
    }
}

/**
 * ISO-8601 timestamp → the day plus the time of day: "Today · 10:23" and
 * "Yesterday · 08:04" while the relative day still reads naturally, then an
 * absolute "15 Aug · 14:30" beyond that. Rendered in the device's zone, since
 * the reader of this screen is asking when their team opened the document.
 * Blank on null/unparseable input.
 */
internal fun relativeDateTimeLabel(iso: String?): String {
    // A bare date carries no time to show — zoning its UTC midnight would invent
    // one — so it degrades to the day-only label.
    if (iso != null && runCatching { LocalDate.parse(iso) }.isSuccess) return relativeDayLabel(iso)
    val instant = parseApiInstant(iso) ?: return ""
    val zoned = instant.atZone(ZoneId.systemDefault())
    val days = ChronoUnit.DAYS.between(zoned.toLocalDate(), LocalDate.now(ZoneId.systemDefault()))
    val day = when {
        days <= 0L -> "Today"
        days == 1L -> "Yesterday"
        else -> zoned.toLocalDate().format(ABSOLUTE_DATE)
    }
    return "$day · ${zoned.toLocalTime().format(TIME_OF_DAY)}"
}

// Fixed-locale patterns: these sit beside the hard-coded "Today"/"Yesterday"
// above, so a locale-varying month name would read half-translated.
private val TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val ABSOLUTE_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.US)
