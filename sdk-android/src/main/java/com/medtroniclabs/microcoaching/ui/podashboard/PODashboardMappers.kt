package com.medtroniclabs.microcoaching.ui.podashboard

import com.medtroniclabs.microcoaching.network.DigitalHelpModuleQuestionsResponse
import com.medtroniclabs.microcoaching.network.DigitalHelpModuleUsageItem
import com.medtroniclabs.microcoaching.network.DocumentUsageDocumentRow
import com.medtroniclabs.microcoaching.network.DocumentUsageEventRow
import com.medtroniclabs.microcoaching.network.DocumentUsageResponse
import com.medtroniclabs.microcoaching.network.ModuleCreationSuggestionDetailResponse
import com.medtroniclabs.microcoaching.network.ModuleCreationSuggestionEvidenceItem
import com.medtroniclabs.microcoaching.network.ModuleCreationSuggestionListItem
import com.medtroniclabs.microcoaching.network.TeamActivitySummary
import com.medtroniclabs.microcoaching.network.TeamMemberActivityDetail
import com.medtroniclabs.microcoaching.network.TeamMemberQuestionItem

/**
 * Pure DTO → UI-model mappers for the PO dashboard. Kept free of network I/O so
 * they are unit-testable in isolation (see PODashboardMappersTest). [ApiPODashboardDataSource]
 * fetches the DTOs and delegates all shaping here.
 */

/** Build the full dashboard from the team-activity spine + the two search lists. */
internal fun mapDashboard(
    range: DateRange,
    summary: TeamActivitySummary,
    users: List<TeamMemberActivityDetail>,
    existing: List<DigitalHelpModuleUsageItem>,
    suggested: List<ModuleCreationSuggestionListItem>,
    spineError: String? = null,
    spineErrorIsAuth: Boolean = false,
    existingTotal: Int = existing.size,
    suggestedTotal: Int = suggested.size,
    documentUsage: DocumentUsageResponse? = null,
): PoDashboard = PoDashboard(
    range = range,
    metrics = listOf(
        PoMetric(MetricKey.ACTIVE_NOW, summary.activeUsers, summary.totalUsers),
        PoMetric(MetricKey.INACTIVE, summary.nonActiveUsers, summary.totalUsers),
        PoMetric(MetricKey.FINISHED_MODULES, summary.usersCompletedModule, summary.totalUsers),
        PoMetric(MetricKey.CHATBOT_ENGAGED, summary.usersChatbotEngaged, summary.totalUsers),
    ),
    sks = users.map { it.toSkSummary() },
    moduleCompletion = users.toModuleCompletion(),
    topSearchedExisting = existing.mapIndexed { i, m -> m.toExistingTopQuery(i) },
    topSearchedSuggested = suggested.mapIndexed { i, s -> s.toSuggestedTopQuery(i) },
    topSearchedExistingTotal = existingTotal,
    topSearchedSuggestedTotal = suggestedTotal,
    documentUsage = documentUsage?.documents?.map { it.toDocumentUsageRow() } ?: emptyList(),
    // total_document_rows can be 0 in some responses — fall back to the page size
    // so "Show all (N)" still appears when a full page came back.
    documentUsageTotal = (documentUsage?.totalDocumentRows ?: 0)
        .coerceAtLeast(documentUsage?.documents?.size ?: 0),
    documentUsageSummary = documentUsage?.toDocumentUsageSummary(),
    spineError = spineError,
    spineErrorIsAuth = spineErrorIsAuth,
)

// ── Document usage ──────────────────────────────────────────────────────────

internal fun DocumentUsageResponse.toDocumentUsageSummary() = DocumentUsageSummary(
    totalViews = totalViews,
    uniqueDocuments = uniqueDocuments,
    uniqueUsers = uniqueUsers,
)

internal fun DocumentUsageDocumentRow.toDocumentUsageRow() = DocumentUsageRow(
    documentId = documentId,
    // Titles are resolved server-side; a null means the document record is gone.
    // Show the id rather than an empty cell so the row stays actionable.
    title = documentTitle?.takeIf { it.isNotBlank() } ?: documentId,
    totalViews = totalViews,
    uniqueUsers = uniqueUsers,
    lastViewedLabel = relativeDayLabel(lastViewedAt),
    lastViewedBy = lastViewedByUserName?.takeIf { it.isNotBlank() },
)

internal fun DocumentUsageEventRow.toDocumentViewEventItem() = DocumentViewEventItem(
    userId = userId,
    userName = userName?.takeIf { it.isNotBlank() } ?: userId.toString(),
    userRole = userRole?.takeIf { it.isNotBlank() },
    // Both are resolved server-side; prefer the finer-grained upazila.
    geography = upazilaId?.takeIf { it.isNotBlank() } ?: district?.takeIf { it.isNotBlank() },
    viewedAtLabel = relativeDateTimeLabel(viewedAt),
    viewedAtMillis = parseApiInstant(viewedAt)?.toEpochMilli(),
)

/**
 * Pivot raw opens into one row per reader, carrying how many times they opened
 * the document and their latest open. Grouped by user id, not display name, so
 * two readers sharing a name stay separate. Ordered by opens descending, then by
 * the most recent open. Role and geography are filled from whichever of a
 * reader's opens carries them — the wire leaves either null per event.
 */
internal fun List<DocumentViewEventItem>.toDocumentReaders(): List<DocumentReaderItem> {
    class Acc(val name: String, var role: String?, var geography: String?) {
        var opens = 0
        var lastMillis: Long? = null
        var lastLabel = ""
    }
    val byUser = LinkedHashMap<Int, Acc>()
    forEach { event ->
        val acc = byUser.getOrPut(event.userId) {
            Acc(event.userName, event.userRole, event.geography)
        }
        acc.opens++
        if (acc.role == null) acc.role = event.userRole
        if (acc.geography == null) acc.geography = event.geography
        val millis = event.viewedAtMillis
        val known = acc.lastMillis
        when {
            millis != null && (known == null || millis > known) -> {
                acc.lastMillis = millis
                acc.lastLabel = event.viewedAtLabel
            }
            // No timestamp anywhere yet — keep the first non-blank label so an
            // unparseable instant still shows something.
            known == null && acc.lastLabel.isBlank() -> acc.lastLabel = event.viewedAtLabel
        }
    }
    return byUser.entries
        .sortedWith(
            compareByDescending<Map.Entry<Int, Acc>> { it.value.opens }
                .thenByDescending { it.value.lastMillis ?: Long.MIN_VALUE },
        )
        .map { (id, acc) ->
            DocumentReaderItem(
                userId = id,
                userName = acc.name,
                userRole = acc.role,
                geography = acc.geography,
                opens = acc.opens,
                lastViewedAtLabel = acc.lastLabel,
            )
        }
}

/**
 * Detail for one document. The response is already narrowed by `document_id`, so
 * its KPIs describe that document alone; [documentId] is passed in because an
 * empty result carries no row to read it from.
 *
 * [allEvents] defaults to this response's own page. The data source overrides it
 * with the opens accumulated across every page, because readers are pivoted from
 * that list and a partial one would under-count them; [truncated] says the
 * accumulation stopped at its ceiling.
 */
internal fun DocumentUsageResponse.toDocumentUsageDetail(
    documentId: String,
    allEvents: List<DocumentViewEventItem> = events.map { it.toDocumentViewEventItem() },
    truncated: Boolean = false,
): DocumentUsageDetail {
    val row = documents.firstOrNull { it.documentId == documentId } ?: documents.firstOrNull()
    return DocumentUsageDetail(
        documentId = documentId,
        title = row?.documentTitle?.takeIf { it.isNotBlank() }
            ?: events.firstOrNull()?.documentTitle?.takeIf { it.isNotBlank() }
            ?: documentId,
        totalViews = row?.totalViews ?: totalViews,
        uniqueUsers = row?.uniqueUsers ?: uniqueUsers,
        events = allEvents,
        totalEvents = totalEvents.coerceAtLeast(allEvents.size),
        readers = allEvents.toDocumentReaders(),
        eventsTruncated = truncated,
    )
}

// ── Section-filter predicates (pure, unit-testable) ─────────────────────────

/** Status filter applied on the SK-based "Show all" screens (My SKs, Refreshers). */
enum class SkStatusFilter { ALL, ACTIVE, INACTIVE, CHATBOT_ENGAGED }

internal fun SkSummary.matchesFilter(filter: SkStatusFilter): Boolean = when (filter) {
    SkStatusFilter.ALL -> true
    SkStatusFilter.ACTIVE -> status == SkStatus.ACTIVE
    SkStatusFilter.INACTIVE -> status == SkStatus.INACTIVE
    SkStatusFilter.CHATBOT_ENGAGED -> queries > 0
}

internal fun TeamMemberActivityDetail.toSkSummary() = SkSummary(
    id = userId.toString(),
    name = name,
    status = isActive.toSkStatus(),
    modulesDone = assignedModules.count { it.completedInRange },
    modulesTotal = assignedModules.size,
    lastSeenLabel = relativeDayLabel(lastActiveAt),
    queries = chatbotQueryCount,
    refreshersDone = refreshersCompleted,
    refreshersTotal = refreshersGenerated,
)

/** Pivot per-user assigned modules into per-module completion (module → assigned SKs). */
internal fun List<TeamMemberActivityDetail>.toModuleCompletion(): List<ModuleCompletion> {
    data class Acc(val title: String, val checks: MutableList<SkCheck>)
    val byModule = LinkedHashMap<String, Acc>()
    forEach { user ->
        user.assignedModules.forEach { m ->
            val acc = byModule.getOrPut(m.moduleId) {
                Acc(m.title?.forSdkLanguage().orModuleId(m.moduleId), mutableListOf())
            }
            acc.checks += SkCheck(user.userId.toString(), user.name, m.completedInRange)
        }
    }
    return byModule.values.map { acc ->
        ModuleCompletion(acc.title, acc.checks.count { it.done }, acc.checks.size, acc.checks)
    }
}

/** SK detail assembled from the located team-activity row + that member's questions. */
internal fun mapSkDetail(
    skId: String,
    row: TeamMemberActivityDetail,
    questions: List<TeamMemberQuestionItem>,
): SkDetail {
    val lastCompleted = row.assignedModules
        .filter { it.completedInRange }
        .maxByOrNull { it.completedAt ?: "" }
    return SkDetail(
        id = skId,
        // Geography + streak are not in the team-activity response.
        location = "",
        name = row.name,
        status = row.isActive.toSkStatus(),
        modulesDone = row.assignedModules.count { it.completedInRange },
        modulesTotal = row.assignedModules.size,
        queries = row.chatbotQueryCount,
        streakDays = 0,
        modules = row.assignedModules.map {
            SkModuleStatus(it.title?.forSdkLanguage().orModuleId(it.moduleId), it.completedInRange)
        },
        activity = SkActivity(
            lastChatbotUse = relativeDayLabel(row.lastChatAt),
            lastModule = lastCompleted?.title?.forSdkLanguage().orEmpty(),
        ),
        topQueries = questions.mapIndexed { i, q -> q.toTopQuery(i) },
    )
}

/** Existing-module drill-down: card shows the combined count, here we split it. */
internal fun mapSearchedModuleDetail(
    moduleId: String,
    questionsResp: DigitalHelpModuleQuestionsResponse,
    requestedCount: Int,
): SearchedModuleDetail = SearchedModuleDetail(
    title = questionsResp.title?.forSdkLanguage().orModuleId(moduleId),
    // "Served" proxy = distinct served queries; "requested" = the assignment-request aggregate.
    servedCount = questionsResp.totalQuestions,
    requestedCount = requestedCount,
    questions = questionsResp.questions.map {
        ModuleQuestionItem(it.question, it.occurrenceCount, relativeDayLabel(it.lastAskedAt))
    },
)

internal fun mapSuggestionDetail(resp: ModuleCreationSuggestionDetailResponse): SuggestionDetail {
    val s = resp.suggestion
    return SuggestionDetail(
        title = s.displayTitle,
        kind = s.suggestionKind,
        rationale = s.rationale,
        questionCount = s.questionCount,
        requestCount = s.requestCount,
        questions = resp.questions.map { it.toEvidence() },
        requests = resp.requests.map { it.toEvidence() },
    )
}

internal fun DigitalHelpModuleUsageItem.toExistingTopQuery(index: Int) = TopQuery(
    rank = index + 1,
    text = title?.forSdkLanguage().orModuleId(moduleId),
    count = digitalHelpCount + moduleRequestedCount,
    id = moduleId,
)

internal fun ModuleCreationSuggestionListItem.toSuggestedTopQuery(index: Int) = TopQuery(
    rank = if (rank > 0) rank else index + 1,
    text = displayTitle,
    count = questionCount + requestCount,
    id = id,
)

internal fun TeamMemberQuestionItem.toTopQuery(index: Int) =
    TopQuery(rank = index + 1, text = question, count = occurrenceCount)

internal fun ModuleCreationSuggestionEvidenceItem.toEvidence() = SuggestionEvidenceItem(
    source = source,
    text = text,
    occurrenceCount = occurrenceCount,
    lastSeenLabel = relativeDayLabel(lastSeenAt),
    sampleChwId = sampleChwId,
)

internal fun Boolean.toSkStatus(): SkStatus = if (this) SkStatus.ACTIVE else SkStatus.INACTIVE

internal fun String?.orModuleId(fallbackId: String): String =
    this?.takeIf { it.isNotBlank() } ?: fallbackId
