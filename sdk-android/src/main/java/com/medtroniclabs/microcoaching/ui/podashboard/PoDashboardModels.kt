package com.medtroniclabs.microcoaching.ui.podashboard

import kotlinx.serialization.Serializable

/** Selected reporting window for the dashboard — the From–To date-range filter (UTC millis). */
@Serializable
data class DateRange(val fromMillis: Long, val toMillis: Long)

/** Engagement status of an SK. */
@Serializable
enum class SkStatus { ACTIVE, NEEDS_ATTENTION, INACTIVE }

/** The four KPI cards at the top of the dashboard. */
@Serializable
enum class MetricKey { ACTIVE_NOW, INACTIVE, FINISHED_MODULES, CHATBOT_ENGAGED }

/** Dashboard sections that support a "Show all" full-list screen (capped at 5 on the tab). */
enum class PoDashboardSection {
    MY_SKS,
    REFRESHERS,
    MODULE_COMPLETION,
    SEARCHED_EXISTING,
    SEARCHED_SUGGESTED,
    DOCUMENT_USAGE,
}

@Serializable
data class PoMetric(val key: MetricKey, val value: Int, val total: Int)

/** A supervised SK as shown in the My-SKs list (display-only; never persisted/logged). */
@Serializable
data class SkSummary(
    val id: String,
    val name: String,
    val status: SkStatus,
    val modulesDone: Int,
    val modulesTotal: Int,
    val lastSeenLabel: String,
    val queries: Int,
    val refreshersDone: Int,
    val refreshersTotal: Int,
)

@Serializable
data class SkCheck(val skId: String, val name: String, val done: Boolean)

@Serializable
data class ModuleCompletion(
    val moduleName: String,
    val done: Int,
    val total: Int,
    val perSk: List<SkCheck>,
)

/**
 * Ranked row for the "Top Queries" / "Top Searched" cards (rank · text · count).
 * [id] is the module_id / suggestion_id when the row is tappable;
 * null for the team-wide Top Queries dummy rows.
 */
@Serializable
data class TopQuery(val rank: Int, val text: String, val count: Int, val id: String? = null)

// ── "Top Searched" drill-downs ──────────────────────────────────────────────

/** One user query behind a searched module (existing-module drill-down). */
data class ModuleQuestionItem(val text: String, val occurrenceCount: Int, val lastAskedLabel: String)

/**
 * Detail for a tapped "Top Searched Existing" module. The card shows the combined
 * count; here the split is broken out — [servedCount] (chatbot matches) vs
 * [requestedCount] (SK assignment requests) — alongside the served query list.
 */
data class SearchedModuleDetail(
    val title: String,
    val servedCount: Int,
    val requestedCount: Int,
    val questions: List<ModuleQuestionItem>,
)

/** One piece of evidence (a query or a free-text request) behind a suggestion. */
data class SuggestionEvidenceItem(
    val source: String,
    val text: String,
    val occurrenceCount: Int,
    val lastSeenLabel: String,
    val sampleChwId: Int?,
)

/** Detail for a tapped "Top Searched Suggested" module/topic. */
data class SuggestionDetail(
    val title: String,
    val kind: String,
    val rationale: String?,
    val questionCount: Int,
    val requestCount: Int,
    val questions: List<SuggestionEvidenceItem>,
    val requests: List<SuggestionEvidenceItem>,
)

// ── Knowledge-document usage ────────────────────────────────────────────────

/** The three headline numbers above the document-usage list. */
@Serializable
data class DocumentUsageSummary(
    val totalViews: Int = 0,
    val uniqueDocuments: Int = 0,
    val uniqueUsers: Int = 0,
)

/**
 * One knowledge document in the usage list. [lastViewedBy] is a person's name —
 * display-only, never logged, same handling as [SkSummary.name].
 */
@Serializable
data class DocumentUsageRow(
    val documentId: String,
    val title: String,
    val totalViews: Int,
    val uniqueUsers: Int,
    val lastViewedLabel: String,
    val lastViewedBy: String?,
)

/** One document-open event in the per-document drill-down. Display-only; see [DocumentUsageRow]. */
data class DocumentViewEventItem(
    val userName: String,
    val userRole: String?,
    val geography: String?,
    val viewedAtLabel: String,
)

/** Detail for a tapped document: its totals plus who opened it, when. */
data class DocumentUsageDetail(
    val documentId: String,
    val title: String,
    val totalViews: Int,
    val uniqueUsers: Int,
    val events: List<DocumentViewEventItem>,
    val totalEvents: Int,
)

/** Everything the dashboard tab + its drill-downs render. */
@Serializable
data class PoDashboard(
    val range: DateRange,
    val metrics: List<PoMetric>,
    val sks: List<SkSummary>,
    val moduleCompletion: List<ModuleCompletion>,
    // Chatbot search analytics — reuse [TopQuery] (rank · title · count).
    val topSearchedExisting: List<TopQuery>,   // published modules the chatbot matched / were requested
    val topSearchedSuggested: List<TopQuery>,  // topics the chatbot couldn't answer (content gaps)
    // Backend totals for the two search lists — the tab only fetches the top few, so it can't
    // derive "Show all (N)" from list size. SK/module totals come from their (full) list size.
    val topSearchedExistingTotal: Int = 0,
    val topSearchedSuggestedTotal: Int = 0,
    // Knowledge-document usage. Defaulted so an offline snapshot written by an
    // older build still decodes.
    val documentUsage: List<DocumentUsageRow> = emptyList(),
    val documentUsageTotal: Int = 0,
    val documentUsageSummary: DocumentUsageSummary? = null,
    /**
     * Non-null when the `team-activity` spine call failed but the rest of the
     * dashboard still loaded — the tab renders an inline notice for the spine
     * sections (KPIs / SKs / completion / refreshers) instead of blanking.
     */
    val spineError: String? = null,
    /** Epoch millis the server data was fetched. Drives the "Last synced" subtitle (AC3). */
    val fetchedAt: Long = 0L,
    /** True when this snapshot was served from the offline cache rather than a fresh fetch. */
    val fromCache: Boolean = false,
)

// ── Single-SK detail ("My SK") ──────────────────────────────────────────────

data class SkModuleStatus(val name: String, val done: Boolean)
data class SkActivity(val lastChatbotUse: String, val lastModule: String)

data class SkDetail(
    val id: String,
    val name: String,
    val location: String,
    val status: SkStatus,
    val modulesDone: Int,
    val modulesTotal: Int,
    val queries: Int,
    val streakDays: Int,
    val modules: List<SkModuleStatus>,
    val activity: SkActivity,
    val topQueries: List<TopQuery>,
)
