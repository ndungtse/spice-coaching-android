package com.medtroniclabs.microcoaching.network

import com.medtroniclabs.microcoaching.data.localized.LocalizedText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data-transfer objects for the PO/AM/Admin dashboard endpoints on
 * [CoachingApiService] (`GET dashboard/...`).
 *
 * Mirrors the dashboard OpenAPI response schemas. All `title` fields are
 * localized maps (`{bn, en}`) on the wire, deserialized into [LocalizedText];
 * counts/lists carry defaults so an additive backend drift never crashes parsing.
 */

/**
 * RFC-7807 `application/problem+json` error body the backend returns on failures
 * (e.g. 502 `analytics_unavailable`). Used to surface a human message instead of a
 * bare status code.
 */
@Serializable
data class ProblemDetail(
    @SerialName("title") val title: String? = null,
    @SerialName("detail") val detail: String? = null,
    @SerialName("status") val status: Int? = null,
    @SerialName("code") val code: String? = null,
)

// ── team-activity (GET dashboard/team-activity) ───────────────────────────────

@Serializable
data class TeamActivityResponse(
    @SerialName("from_date") val fromDate: String = "",
    @SerialName("to_date") val toDate: String = "",
    @SerialName("summary") val summary: TeamActivitySummary = TeamActivitySummary(),
    @SerialName("users") val users: List<TeamMemberActivityDetail> = emptyList(),
    @SerialName("total_users") val totalUsers: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("limit") val limit: Int = 0,
    @SerialName("offset") val offset: Int = 0,
    @SerialName("server_time_utc") val serverTimeUtc: String = "",
)

@Serializable
data class TeamActivitySummary(
    @SerialName("total_users") val totalUsers: Int = 0,
    @SerialName("active_users") val activeUsers: Int = 0,
    @SerialName("non_active_users") val nonActiveUsers: Int = 0,
    @SerialName("users_completed_module") val usersCompletedModule: Int = 0,
    @SerialName("users_chatbot_engaged") val usersChatbotEngaged: Int = 0,
)

@Serializable
data class TeamMemberActivityDetail(
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("name") val name: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_chatbot_engaged") val isChatbotEngaged: Boolean = false,
    @SerialName("last_chat_at") val lastChatAt: String? = null,
    @SerialName("last_active_at") val lastActiveAt: String? = null,
    @SerialName("has_completed_module_in_range") val hasCompletedModuleInRange: Boolean = false,
    @SerialName("assigned_modules") val assignedModules: List<TeamMemberModuleActivity> = emptyList(),
    @SerialName("chatbot_query_count") val chatbotQueryCount: Int = 0,
    @SerialName("chatbot_unattributed_query_count") val chatbotUnattributedQueryCount: Int = 0,
    @SerialName("chatbot_modules") val chatbotModules: List<TeamMemberChatbotModuleUsage> = emptyList(),
    @SerialName("refreshers_generated") val refreshersGenerated: Int = 0,
    @SerialName("refreshers_completed") val refreshersCompleted: Int = 0,
)

@Serializable
data class TeamMemberModuleActivity(
    @SerialName("module_id") val moduleId: String = "",
    @SerialName("title") val title: LocalizedText? = null,
    @SerialName("completed_in_range") val completedInRange: Boolean = false,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
data class TeamMemberChatbotModuleUsage(
    @SerialName("module_id") val moduleId: String = "",
    @SerialName("title") val title: LocalizedText? = null,
    @SerialName("query_count") val queryCount: Int = 0,
)

// ── team member questions (GET dashboard/team-activity/users/{id}/questions) ───

@Serializable
data class TeamMemberQuestionsResponse(
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("from_date") val fromDate: String = "",
    @SerialName("to_date") val toDate: String = "",
    @SerialName("questions") val questions: List<TeamMemberQuestionItem> = emptyList(),
    @SerialName("total_questions") val totalQuestions: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("limit") val limit: Int = 0,
    @SerialName("offset") val offset: Int = 0,
    @SerialName("server_time_utc") val serverTimeUtc: String = "",
)

/** Shared by team-member and per-module question lists. */
@Serializable
data class TeamMemberQuestionItem(
    @SerialName("question") val question: String = "",
    @SerialName("occurrence_count") val occurrenceCount: Int = 0,
    @SerialName("last_asked_at") val lastAskedAt: String? = null,
)

// ── digital-help modules (GET dashboard/digital-help-modules) ─────────────────

@Serializable
data class DigitalHelpModuleUsageResponse(
    @SerialName("from_date") val fromDate: String = "",
    @SerialName("to_date") val toDate: String = "",
    @SerialName("total_digital_help") val totalDigitalHelp: Int = 0,
    @SerialName("total_module_requested") val totalModuleRequested: Int = 0,
    @SerialName("total_modules") val totalModules: Int = 0,
    @SerialName("limit") val limit: Int = 0,
    @SerialName("offset") val offset: Int = 0,
    @SerialName("modules") val modules: List<DigitalHelpModuleUsageItem> = emptyList(),
)

@Serializable
data class DigitalHelpModuleUsageItem(
    @SerialName("module_id") val moduleId: String = "",
    @SerialName("module_family_id") val moduleFamilyId: String? = null,
    @SerialName("digital_help_count") val digitalHelpCount: Int = 0,
    @SerialName("module_requested_count") val moduleRequestedCount: Int = 0,
    @SerialName("title") val title: LocalizedText? = null,
)

@Serializable
data class DigitalHelpModuleQuestionsResponse(
    @SerialName("module_id") val moduleId: String = "",
    @SerialName("title") val title: LocalizedText? = null,
    @SerialName("from_date") val fromDate: String = "",
    @SerialName("to_date") val toDate: String = "",
    @SerialName("questions") val questions: List<TeamMemberQuestionItem> = emptyList(),
    @SerialName("total_questions") val totalQuestions: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("limit") val limit: Int = 0,
    @SerialName("offset") val offset: Int = 0,
)

@Serializable
data class DigitalHelpModuleRequestsResponse(
    @SerialName("module_id") val moduleId: String = "",
    @SerialName("title") val title: LocalizedText? = null,
    @SerialName("from_date") val fromDate: String = "",
    @SerialName("to_date") val toDate: String = "",
    @SerialName("module_requested_count") val moduleRequestedCount: Int = 0,
)

// ── module-creation suggestions (GET dashboard/module-creation-suggestions) ────

@Serializable
data class ModuleCreationSuggestionListResponse(
    @SerialName("from_date") val fromDate: String = "",
    @SerialName("to_date") val toDate: String = "",
    @SerialName("suggestions") val suggestions: List<ModuleCreationSuggestionListItem> = emptyList(),
    @SerialName("total_suggestions") val totalSuggestions: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("limit") val limit: Int = 0,
    @SerialName("offset") val offset: Int = 0,
)

@Serializable
data class ModuleCreationSuggestionListItem(
    @SerialName("id") val id: String = "",
    @SerialName("suggestion_date") val suggestionDate: String = "",
    @SerialName("suggestion_kind") val suggestionKind: String = "",
    @SerialName("matched_module_id") val matchedModuleId: String? = null,
    @SerialName("proposed_topic") val proposedTopic: String? = null,
    @SerialName("display_title") val displayTitle: String = "",
    @SerialName("rationale") val rationale: String? = null,
    @SerialName("question_count") val questionCount: Int = 0,
    @SerialName("request_count") val requestCount: Int = 0,
    @SerialName("evidence_count") val evidenceCount: Int = 0,
    @SerialName("rank") val rank: Int = 0,
    @SerialName("computed_at") val computedAt: String = "",
)

@Serializable
data class ModuleCreationSuggestionDetailResponse(
    @SerialName("suggestion") val suggestion: ModuleCreationSuggestionListItem = ModuleCreationSuggestionListItem(),
    @SerialName("questions") val questions: List<ModuleCreationSuggestionEvidenceItem> = emptyList(),
    @SerialName("requests") val requests: List<ModuleCreationSuggestionEvidenceItem> = emptyList(),
)

@Serializable
data class ModuleCreationSuggestionEvidenceItem(
    @SerialName("source") val source: String = "",
    @SerialName("text") val text: String = "",
    @SerialName("occurrence_count") val occurrenceCount: Int = 0,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("sample_chw_id") val sampleChwId: Int? = null,
)

// ── document usage (GET dashboard/document-usage) ──────────────────────────────
// One response carries the KPIs, the per-document table, and the event drill-down
// under the same filters.

/** One ranked document by view volume. */
@Serializable
data class DocumentUsageTopItem(
    @SerialName("document_id") val documentId: String = "",
    @SerialName("document_title") val documentTitle: String? = null,
    @SerialName("view_count") val viewCount: Int = 0,
)

/** Per-document usage row. */
@Serializable
data class DocumentUsageDocumentRow(
    @SerialName("document_id") val documentId: String = "",
    @SerialName("document_title") val documentTitle: String? = null,
    @SerialName("total_views") val totalViews: Int = 0,
    @SerialName("unique_users") val uniqueUsers: Int = 0,
    @SerialName("last_viewed_at") val lastViewedAt: String? = null,
    @SerialName("last_viewed_by_user_id") val lastViewedByUserId: Int? = null,
    @SerialName("last_viewed_by_user_name") val lastViewedByUserName: String? = null,
)

/** One document-view event, for the per-document drill-down. */
@Serializable
data class DocumentUsageEventRow(
    @SerialName("event_id") val eventId: String = "",
    @SerialName("document_id") val documentId: String = "",
    @SerialName("document_title") val documentTitle: String? = null,
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("user_role") val userRole: String? = null,
    @SerialName("upazila_id") val upazilaId: String? = null,
    @SerialName("district") val district: String? = null,
    @SerialName("viewed_at") val viewedAt: String? = null,
)

@Serializable
data class DocumentUsageResponse(
    @SerialName("from_date") val fromDate: String = "",
    @SerialName("to_date") val toDate: String = "",
    @SerialName("total_views") val totalViews: Int = 0,
    @SerialName("unique_documents") val uniqueDocuments: Int = 0,
    @SerialName("unique_users") val uniqueUsers: Int = 0,
    @SerialName("top_documents") val topDocuments: List<DocumentUsageTopItem> = emptyList(),
    @SerialName("total_document_rows") val totalDocumentRows: Int = 0,
    @SerialName("documents") val documents: List<DocumentUsageDocumentRow> = emptyList(),
    @SerialName("total_events") val totalEvents: Int = 0,
    @SerialName("events") val events: List<DocumentUsageEventRow> = emptyList(),
    @SerialName("documents_limit") val documentsLimit: Int = 0,
    @SerialName("documents_offset") val documentsOffset: Int = 0,
    @SerialName("events_limit") val eventsLimit: Int = 0,
    @SerialName("events_offset") val eventsOffset: Int = 0,
)
