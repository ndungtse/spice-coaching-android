package com.medtroniclabs.microcoaching.network

import com.medtroniclabs.microcoaching.data.localized.LocalizedText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the MicroCoaching Knowledge Layer backend.
 *
 * Base URL: [com.medtroniclabs.microcoaching.MicroCoachingConfig.backendUrl]
 * Auth:     `Authorization: {authToken}` + `Client: mob` (injected by NetworkModule)
 * Docs:     http://<host>/docs  (FastAPI Swagger UI)
 *
 */
interface CoachingApiService {

    // ── Sync endpoints ────────────────────────────────────────────────────────

    /**
     * Upload a batch of SDK events for analytics and gap-profile computation.
     *
     * The ack partitions the submitted ids rather than failing the whole batch:
     * each comes back under `accepted`, `duplicates` (already ingested — the
     * server dedups on event id, so a resend is harmless), `buffered` (held
     * server-side for retry) or `rejected`. Only `rejected` ids still need
     * sending; see [TelemetryAckResponse].
     */
    @POST("telemetry/events")
    suspend fun pushTelemetry(@Body batch: TelemetryBatch): Response<TelemetryAckResponse>

    /**
     * Fetch the module sync bundle (modules + their inline quiz + module-family rows),
     * plus the caller's assigned-module ids and training-request history.
     *
     * [since] is the ISO 8601 watermark from the previous call (`server_time_utc`
     * in the response). The backend requires this query parameter on every call;
     * pass [SyncDefaults.EPOCH_ISO] for first sync to receive the full catalogue.
     */
    @GET("sync/modules")
    suspend fun pullModules(
        @Query("since") since: String,
    ): Response<ModulesSyncBundle>

    /**
     * Fetch the behavioural-gap taxonomy plus the authenticated CHW's gap state,
     * quiz state and module-completion rows. The CHW is resolved from the auth
     * token, so there is no way to ask about a different one.
     */
    @GET("sync/gaps")
    suspend fun pullGaps(
        @Query("since") since: String? = null,
    ): Response<GapsSyncBundle>

    /**
     * Fetch the trigger definitions + module-trigger bindings updated since
     * [since]. Backend requires the query parameter.
     */
    @GET("sync/triggers")
    suspend fun pullTriggers(
        @Query("since") since: String,
    ): Response<TriggersSyncBundle>

    /**
     * Fetch the config-threshold snapshot. Returns a flat key→value dict
     * representing all thresholds the backend wants the device to honour.
     */
    @GET("sync/config")
    suspend fun pullConfig(): Response<ConfigSyncBundle>

    /**
     * Fetch the ranked chat-FAQ suggestions (frequently-asked chat questions).
     * Cached locally and surfaced as chat suggestion chips; the static defaults
     * are the empty-cache fallback. Scoped to the token's tenant.
     *
     * [since] is the ISO-8601 watermark (`server_time_utc` from the previous
     * call); pass [SyncDefaults.EPOCH_ISO] on first sync for the full list.
     */
    @GET("sync/chat-faqs")
    suspend fun pullChatFaqs(
        @Query("since") since: String,
    ): Response<ChatFaqsSyncBundle>

    /**
     * Fetch the source-document catalogue — the durable source for both the
     * Knowledge section and the Training sub-tab, in one response.
     *
     * `source_documents` holds the documents linked to published modules that
     * changed after [since]. `assigned_documents` holds the authenticated user's
     * complete current assignment snapshot and ignores [since] entirely, so every
     * call returns the full set. Both carry inline presigned URLs for the document
     * and its thumbnail — this is the only source of those URLs, as there is no
     * on-demand presign endpoint.
     *
     * Callers pass [SyncDefaults.EPOCH_ISO] rather than a stored watermark:
     * presigned URLs only ride on the rows a response actually returns, so a
     * narrower [since] would leave unchanged documents holding expired URLs.
     */
    @GET("sync/source-documents")
    suspend fun getSourceDocuments(
        @Query("since") since: String,
    ): Response<SourceDocumentsSyncBundle>

    /**
     * Resolve a single media `object_name` (embedded in rich card bodies as
     * image/video nodes, e.g. `media/<uuid>_<file>.png`) to a short-lived presigned
     * GET URL. `bucket/object` references are also accepted by the backend.
     *
     * @param objectName  object name returned by upload (required).
     * @param expiresSeconds  URL lifetime in seconds (default 600, max 86400).
     * @param disposition  `auto` (inline for PDF/images, attachment otherwise),
     *                      or force `inline` / `attachment`.
     */
    @GET("admin/files/presigned-url")
    suspend fun getMediaPresignedUrl(
        @Query("object_name") objectName: String,
        @Query("expires_seconds") expiresSeconds: Int = 600,
        @Query("disposition") disposition: String = "auto",
    ): Response<MediaPresignedUrlResponse>

    // ── Coaching ───────────────────────────────────────────────────────────────

    /**
     * Backend RAG chat endpoint. Embeds [RagQueryRequest.question], retrieves top similar
     * published modules server-side, and returns a grounded answer with source documents.
     *
     * Used as the primary chat path when the device is online (any device class).
     * On failure the caller falls back to the local path appropriate for the device
     * (Gemma for normal, BM25 for low-end).
     */
    @POST("coaching/rag-query")
    suspend fun ragQuery(@Body request: RagQueryRequest): Response<RagQueryResponse>

    /**
     * Retrieve the backend-prioritised morning module list for the authenticated
     * CHW — gap-driven and recently-added modules, ranked server-side. The CHW
     * and tenant come from the auth token; the device falls back to its local
     * morning-card cache when offline.
     */
    @GET("morning/cards")
    suspend fun getMorningCards(): Response<MorningCardsResponse>

    // A CHW training request is not a REST call — it is recorded as a
    // `module_requested` telemetry event and shipped via POST /telemetry/events.

    // ── Dashboard (PO) ────────────────────────────────────────────
    // All date-ranged (from_date/to_date as YYYY-MM-DD) and paginated. Auth +
    // X-Tenant-Id are injected by NetworkModule. Tenant scoping is resolved
    // server-side from the token, so none of these take a tenant parameter.

    /** Team-activity report for the authenticated organizer — summary + per-SK detail. */
    @GET("dashboard/team-activity")
    suspend fun getTeamActivity(
        @Query("from_date") fromDate: String,
        @Query("to_date") toDate: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): Response<TeamActivityResponse>

    /** Paginated chatbot questions asked by one team member. */
    @GET("dashboard/team-activity/users/{user_id}/questions")
    suspend fun getTeamMemberQuestions(
        @Path("user_id") userId: String,
        @Query("from_date") fromDate: String,
        @Query("to_date") toDate: String,
        @Query("po_user_id") poUserId: Int? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): Response<TeamMemberQuestionsResponse>

    /** Modules ranked by combined digital-help + module-requested volume. */
    @GET("dashboard/digital-help-modules")
    suspend fun getDigitalHelpModules(
        @Query("from_date") fromDate: String,
        @Query("to_date") toDate: String,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): Response<DigitalHelpModuleUsageResponse>

    /** Paginated chatbot questions that matched one module. */
    @GET("dashboard/digital-help-modules/{module_id}/questions")
    suspend fun getDigitalHelpModuleQuestions(
        @Path("module_id") moduleId: String,
        @Query("from_date") fromDate: String,
        @Query("to_date") toDate: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): Response<DigitalHelpModuleQuestionsResponse>

    /** Aggregate module-requested count for one module. */
    @GET("dashboard/digital-help-modules/{module_id}/requests")
    suspend fun getDigitalHelpModuleRequests(
        @Path("module_id") moduleId: String,
        @Query("from_date") fromDate: String,
        @Query("to_date") toDate: String,
    ): Response<DigitalHelpModuleRequestsResponse>

    /** Daily module-creation suggestions inferred from unattributed demand. */
    @GET("dashboard/module-creation-suggestions")
    suspend fun getModuleCreationSuggestions(
        @Query("from_date") fromDate: String,
        @Query("to_date") toDate: String,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): Response<ModuleCreationSuggestionListResponse>

    /** One suggestion's detail — the chat questions and free-text requests behind it. */
    @GET("dashboard/module-creation-suggestions/{suggestion_id}")
    suspend fun getModuleCreationSuggestion(
        @Path("suggestion_id") suggestionId: String,
    ): Response<ModuleCreationSuggestionDetailResponse>

    /**
     * Knowledge-document view analytics — KPIs, the per-document table, and the
     * event drill-down in one response.
     *
     * This route's date params are `from`/`to`, **not** the `from_date`/`to_date`
     * every other dashboard endpoint above uses; sending `from_date` here 422s.
     *
     * Hierarchy scoping (a PO sees themselves plus their SKs) is resolved
     * server-side from the auth token, so no `po_id` is sent. [documentId] narrows
     * every section to one document, which is what the drill-down uses.
     */
    @GET("dashboard/document-usage")
    suspend fun getDocumentUsage(
        @Query("from") fromDate: String,
        @Query("to") toDate: String,
        @Query("document_id") documentId: String? = null,
        @Query("top_limit") topLimit: Int = 10,
        @Query("documents_limit") documentsLimit: Int = 20,
        @Query("documents_offset") documentsOffset: Int = 0,
        @Query("events_limit") eventsLimit: Int = 50,
        @Query("events_offset") eventsOffset: Int = 0,
    ): Response<DocumentUsageResponse>
}
