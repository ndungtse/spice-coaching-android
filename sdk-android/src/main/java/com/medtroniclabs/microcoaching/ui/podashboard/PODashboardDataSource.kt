package com.medtroniclabs.microcoaching.ui.podashboard

/**
 * Source of PO dashboard data.
 *
 * Production uses [ApiPODashboardDataSource] (the `dashboard/…` endpoints — see
 * docs/dashboads_and_leaderboard/dashboard_apis.md). [StubPODashboardDataSource]
 * is retained for @Preview and unit tests.
 */
interface PODashboardDataSource {
    suspend fun loadDashboard(chwId: String, range: DateRange): PoDashboard
    suspend fun loadSkDetail(skId: String): SkDetail?

    /** Detail for a tapped "Top Searched Existing" module. */
    suspend fun loadSearchedModuleDetail(moduleId: String, range: DateRange): SearchedModuleDetail?

    /** Detail for a tapped "Top Searched Suggested" module/topic. */
    suspend fun loadSuggestionDetail(suggestionId: String): SuggestionDetail?

    /** Full ranked "Top Searched Existing" list for its "Show all" screen. */
    suspend fun loadAllSearchedExisting(range: DateRange): List<TopQuery>

    /** Full ranked "Top Searched Suggested" list for its "Show all" screen. */
    suspend fun loadAllSearchedSuggested(range: DateRange): List<TopQuery>

    /** Full knowledge-document usage list for its "Show all" screen. */
    suspend fun loadAllDocumentUsage(range: DateRange): List<DocumentUsageRow>

    /** Detail for a tapped document — its totals plus who opened it, when. */
    suspend fun loadDocumentUsageDetail(documentId: String, range: DateRange): DocumentUsageDetail?
}

/** Deterministic mock so the dashboard + drill-downs are demoable before the backend lands. */
class StubPODashboardDataSource : PODashboardDataSource {

    private val moduleNames = listOf(
        "UHIS Updated Features",
        "Fundal Height Assessment",
        "Emergency Referrals",
        "Pregnancy Danger Signs",
    )
    private val MODULES_TOTAL = 4
    private val REFRESHERS_TOTAL = 2

    /** Compact roster row; expanded into [SkSummary]/[SkDetail] below. */
    private data class Row(
        val id: String,
        val name: String,
        val status: SkStatus,
        val modulesDone: Int,
        val lastSeen: String,
        val queries: Int,
        val refreshersDone: Int,
        val streakDays: Int,
    )

    private val roster = listOf(
        Row("sk1", "Amina Begum", SkStatus.ACTIVE, 3, "Today", 12, 2, 5),
        Row("sk2", "Nasrin Akter", SkStatus.ACTIVE, 4, "Today", 9, 2, 8),
        Row("sk3", "Sumaiya Khan", SkStatus.NEEDS_ATTENTION, 2, "4 days ago", 4, 1, 2),
        Row("sk4", "Fatima Sultana", SkStatus.INACTIVE, 1, "8 days ago", 0, 1, 0),
        Row("sk5", "Rokeya Akter", SkStatus.ACTIVE, 4, "Today", 8, 2, 6),
        Row("sk6", "Rashida Banu", SkStatus.INACTIVE, 0, "10 days ago", 0, 0, 0),
        Row("sk7", "Rehana Begum", SkStatus.ACTIVE, 4, "Yesterday", 7, 2, 4),
        Row("sk8", "Selina Sultana", SkStatus.ACTIVE, 4, "Today", 6, 2, 7),
        Row("sk9", "Bilkis Hoque", SkStatus.ACTIVE, 3, "2 days ago", 5, 1, 3),
        Row("sk10", "Salma Hoque", SkStatus.NEEDS_ATTENTION, 2, "5 days ago", 2, 1, 1),
        Row("sk11", "Nasima Islam", SkStatus.ACTIVE, 4, "Today", 10, 2, 9),
        Row("sk12", "Marium Khatun", SkStatus.ACTIVE, 3, "Yesterday", 3, 2, 2),
    )

    override suspend fun loadDashboard(chwId: String, range: DateRange): PoDashboard {
        // Stub roster is range-independent; a real backend filters engagement by [range]
        // (Active/Non-Responsive definitions key off module opens within the window).
        val rows = roster
        val total = rows.size

        val metrics = listOf(
            PoMetric(MetricKey.ACTIVE_NOW, rows.count { it.status == SkStatus.ACTIVE }, total),
            PoMetric(MetricKey.INACTIVE, rows.count { it.status == SkStatus.INACTIVE }, total),
            PoMetric(MetricKey.FINISHED_MODULES, rows.count { it.modulesDone == MODULES_TOTAL }, total),
            PoMetric(MetricKey.CHATBOT_ENGAGED, rows.count { it.queries > 0 }, total),
        )

        val moduleCompletion = moduleNames.mapIndexed { mIdx, name ->
            // An SK has done module m if it falls within their completed count.
            val perSk = rows.map { SkCheck(it.id, it.name, mIdx < it.modulesDone) }
            ModuleCompletion(name, perSk.count { it.done }, total, perSk)
        }

        return PoDashboard(
            range = range,
            metrics = metrics,
            sks = rows.map { it.toSummary() },
            moduleCompletion = moduleCompletion,
            // Stub: published modules the chatbot matched / SKs requested.
            topSearchedExisting = listOf(
                TopQuery(1, "Hypertension Management", 42),
                TopQuery(2, "Fundal Height Assessment", 28),
                TopQuery(3, "Emergency Referral Protocol", 19),
            ),
            // Stub: topics the chatbot couldn't answer (draft/missing module) — content gaps.
            topSearchedSuggested = listOf(
                TopQuery(1, "Postpartum Hemorrhage Management", 15),
                TopQuery(2, "Newborn Resuscitation", 11),
                TopQuery(3, "Gestational Diabetes Screening", 7),
            ),
            // Stub: knowledge documents opened from the library.
            documentUsage = documentUsageRows.take(3),
            documentUsageTotal = documentUsageRows.size,
            documentUsageSummary = DocumentUsageSummary(
                totalViews = documentUsageRows.sumOf { it.totalViews },
                uniqueDocuments = documentUsageRows.size,
                uniqueUsers = 9,
            ),
        )
    }

    private val documentUsageRows = listOf(
        DocumentUsageRow("doc-1", "ANC Field Guide", 34, 8, "Today", "Amina Begum"),
        DocumentUsageRow("doc-2", "Referral Protocol Handbook", 21, 6, "Yesterday", "Nasrin Akter"),
        DocumentUsageRow("doc-3", "Danger Signs Poster", 15, 5, "2 days ago", "Rehana Begum"),
        DocumentUsageRow("doc-4", "Newborn Care Checklist", 9, 4, "4 days ago", "Bilkis Hoque"),
        DocumentUsageRow("doc-5", "Hypertension Quick Reference", 6, 3, "6 days ago", "Salma Hoque"),
    )

    override suspend fun loadAllDocumentUsage(range: DateRange): List<DocumentUsageRow> =
        documentUsageRows

    override suspend fun loadDocumentUsageDetail(
        documentId: String,
        range: DateRange,
    ): DocumentUsageDetail? {
        val row = documentUsageRows.firstOrNull { it.documentId == documentId } ?: return null
        val events = listOf(
            DocumentViewEventItem("Amina Begum", "SK", "Gazipur Sadar", "Today"),
            DocumentViewEventItem("Nasrin Akter", "SK", "Gazipur Sadar", "Yesterday"),
            DocumentViewEventItem("Sumaiya Khan", "SK", "Kaliakair", "3 days ago"),
        )
        return DocumentUsageDetail(
            documentId = row.documentId,
            title = row.title,
            totalViews = row.totalViews,
            uniqueUsers = row.uniqueUsers,
            events = events,
            totalEvents = events.size,
        )
    }

    override suspend fun loadSkDetail(skId: String): SkDetail? {
        val row = roster.firstOrNull { it.id == skId } ?: return null
        return SkDetail(
            id = row.id,
            name = row.name,
            location = "Dhaka North",
            status = row.status,
            modulesDone = row.modulesDone,
            modulesTotal = MODULES_TOTAL,
            queries = row.queries,
            streakDays = row.streakDays,
            modules = moduleNames.mapIndexed { i, n -> SkModuleStatus(n, i < row.modulesDone) },
            activity = SkActivity(lastChatbotUse = "2 days ago", lastModule = row.lastSeen),
            topQueries = listOf(
                TopQuery(1, "HTN Referral Thresholds", 6),
                TopQuery(2, "ANC Visit Schedule", 4),
                TopQuery(3, "Danger Signs", 3),
            ),
        )
    }

    override suspend fun loadSearchedModuleDetail(moduleId: String, range: DateRange): SearchedModuleDetail =
        SearchedModuleDetail(
            title = "Hypertension Management",
            servedCount = 30,
            requestedCount = 12,
            questions = listOf(
                ModuleQuestionItem("What BP reading needs referral?", 9, "Today"),
                ModuleQuestionItem("How often to check BP in pregnancy?", 6, "Yesterday"),
                ModuleQuestionItem("Signs of severe hypertension", 4, "3 days ago"),
            ),
        )

    override suspend fun loadSuggestionDetail(suggestionId: String): SuggestionDetail =
        SuggestionDetail(
            title = "Postpartum Hemorrhage Management",
            kind = "new_topic",
            rationale = "Repeated chatbot queries with no matching published module.",
            questionCount = 12,
            requestCount = 3,
            questions = listOf(
                SuggestionEvidenceItem("chatbot", "How to manage heavy bleeding after delivery?", 7, "Today", null),
                SuggestionEvidenceItem("chatbot", "PPH first response steps", 5, "2 days ago", null),
            ),
            requests = listOf(
                SuggestionEvidenceItem("module_requested", "Please add PPH training", 3, "Yesterday", null),
            ),
        )

    override suspend fun loadAllSearchedExisting(range: DateRange): List<TopQuery> = listOf(
        TopQuery(1, "Hypertension Management", 42, id = "mod-1"),
        TopQuery(2, "Fundal Height Assessment", 28, id = "mod-2"),
        TopQuery(3, "Emergency Referral Protocol", 19, id = "mod-3"),
        TopQuery(4, "ANC Visit Schedule", 14, id = "mod-4"),
        TopQuery(5, "Danger Signs in Pregnancy", 11, id = "mod-5"),
        TopQuery(6, "Newborn Care Basics", 8, id = "mod-6"),
    )

    override suspend fun loadAllSearchedSuggested(range: DateRange): List<TopQuery> = listOf(
        TopQuery(1, "Postpartum Hemorrhage Management", 15, id = "sug-1"),
        TopQuery(2, "Newborn Resuscitation", 11, id = "sug-2"),
        TopQuery(3, "Gestational Diabetes Screening", 7, id = "sug-3"),
        TopQuery(4, "Breastfeeding Support", 5, id = "sug-4"),
        TopQuery(5, "Family Planning Counselling", 4, id = "sug-5"),
        TopQuery(6, "Adolescent Health", 2, id = "sug-6"),
    )

    private fun Row.toSummary() = SkSummary(
        id = id,
        name = name,
        status = status,
        modulesDone = modulesDone,
        modulesTotal = MODULES_TOTAL,
        lastSeenLabel = lastSeen,
        queries = queries,
        refreshersDone = refreshersDone,
        refreshersTotal = REFRESHERS_TOTAL,
    )
}
