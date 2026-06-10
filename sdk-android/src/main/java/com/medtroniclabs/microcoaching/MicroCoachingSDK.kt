package com.medtroniclabs.microcoaching

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.sync.SyncApi
import com.medtroniclabs.microcoaching.domain.triggers.TriggerEvaluator
import com.medtroniclabs.microcoaching.domain.triggers.buildModuleCompletion
import com.medtroniclabs.microcoaching.ai.model.ModelProvider
import com.medtroniclabs.microcoaching.data.repository.ChatRepositoryImpl
import com.medtroniclabs.microcoaching.data.repository.CoachingEventRepositoryImpl
import com.medtroniclabs.microcoaching.ai.model.ModelManager
import com.medtroniclabs.microcoaching.domain.context.CHWWorkContext
import com.medtroniclabs.microcoaching.domain.context.PatientSnapshot
import com.medtroniclabs.microcoaching.ai.inference.InferenceRouter
import com.medtroniclabs.microcoaching.ai.translation.OnDeviceTranslator
import com.medtroniclabs.microcoaching.domain.decision.CoachingMode
import com.medtroniclabs.microcoaching.network.CoachingApiService
import com.medtroniclabs.microcoaching.network.NetworkModule
import com.medtroniclabs.microcoaching.sdk.CoachingDataRepository
import com.medtroniclabs.microcoaching.sdk.MicroCoachingDataCallback
import com.medtroniclabs.microcoaching.sdk.SdkDataExport
import com.medtroniclabs.microcoaching.domain.telemetry.TelemetryManager
import com.medtroniclabs.microcoaching.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Singleton entry point for the MicroCoaching SDK.
 *
 * Initialize once in your Application.onCreate():
 * ```kotlin
 * MicroCoachingSDK.Builder(this)
 *     .language(Language.BANGLA)
 *     .backendUrl(BuildConfig.COACHING_BACKEND_URL)
 *     .authToken(SecuredPreference.getToken())
 *     .otelEndpoint(BuildConfig.OTEL_ENDPOINT)
 *     .otelHeaders(mapOf("signoz-access-token" to BuildConfig.SIGNOZ_TOKEN))
 *     .enableTelemetry(BuildConfig.ENABLE_COACHING_TELEMETRY)
 *     .enableChat(true)
 *     .build()
 * ```
 *
 * Then retrieve the singleton from anywhere:
 * ```kotlin
 * val sdk = MicroCoachingSDK.getInstance()
 * ```
 *
 * Or inject [CoachingDataRepository] via Hilt:
 * ```kotlin
 * @Provides @Singleton
 * fun provideCoachingDataRepository(): CoachingDataRepository =
 *     MicroCoachingSDK.getInstance().dataRepository
 * ```
 */
/** Startup health snapshot returned by [MicroCoachingSDK.checkHealth]. */
data class SdkHealthReport(
    val isModelPresent: Boolean,
    val modelFileSizeBytes: Long,
    val modelStateName: String,
    val morningCardCount: Int,
)

class MicroCoachingSDK private constructor(val config: MicroCoachingConfig) {

    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Active language for SDK UI and LLM prompts.
     *
     * Set once via [Builder.language] at init time. Can be changed at runtime via
     * [setLanguage] — the new value takes effect the next time any SDK screen is
     * opened (Compose roots re-wrap their locale context on entry).
     */
    @Volatile
    var language: Language = config.language
        private set

    /**
     * Change the coaching language at runtime without rebuilding the SDK.
     *
     * The new language takes effect immediately for LLM prompts and the next time
     * any SDK-owned screen (CoachingFlowActivity, CoachingChatFragment,
     * CoachingCardFragment) is opened or restarted — they each re-wrap their locale
     * context from this value on entry.
     *
     * Example — switch to English after login:
     * ```kotlin
     * MicroCoachingSDK.getInstance().setLanguage(Language.ENGLISH)
     * ```
     */
    fun setLanguage(language: Language) {
        this.language = language
        if (language == Language.BANGLA) {
            sdkScope.launch { translator.ensureModelReady() }
        }
    }

    private val _morningModules = MutableStateFlow<List<ModuleEntity>>(emptyList())
    /** Modules currently published. Populated by [onHomeScreenShown]. */
    val morningModules: StateFlow<List<ModuleEntity>> = _morningModules.asStateFlow()

    private val _morningCardsItems = MutableStateFlow<List<MorningCardCacheEntity>>(emptyList())
    /**
     * Last-known backend-prioritised morning-card list. Populated after a successful
     * `GET /morning/cards` call in [onHomeScreenShown] / [onMorningOpen] / [InboundSyncWorker].
     *
     * Consumed by [QuickLearnViewModel] and [LearnViewModel] to attach [MorningCardCacheEntity.behaviouralGapId]
     * and [MorningCardCacheEntity.source] to each surfaced module.
     */
    val morningCardsItems: StateFlow<List<MorningCardCacheEntity>> = _morningCardsItems.asStateFlow()

    private val _latestModule = MutableStateFlow<ModuleEntity?>(null)
    /**
     * The most recent module surfaced by a workflow- or gap-trigger evaluation.
     * Host surfaces (e.g. SPICE's assessment-summary banner) collect this to
     * decide when to show the coaching card.
     */
    val latestModule: StateFlow<ModuleEntity?> = _latestModule.asStateFlow()

    private val triggerEvaluator: TriggerEvaluator by lazy {
        TriggerEvaluator(
            triggerDao = database.triggerDefinitionDao(),
            bindingDao = database.moduleTriggerBindingDao(),
            moduleDao = database.moduleDao(),
            gapProfileDao = database.chwGapProfileDao(),
            behaviouralGapDao = database.behaviouralGapDao(),
            configDao = database.configThresholdDao(),
            config = config,
        )
    }

    /** Lazy-initialized OTel telemetry manager. Inactive if [MicroCoachingConfig.enableTelemetry] is false. */
    val telemetry: TelemetryManager by lazy { TelemetryManager(config) }

    /** Sync orchestrator — schedules and triggers outbound + inbound WorkManager jobs. */
    val syncCoordinator: SyncCoordinator by lazy { SyncCoordinator(config.context) }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkLost = false

    init {
        if (config.backendUrl.isNotBlank()) {
            syncCoordinator.schedulePeriodic()
            registerConnectivityCallback()
        }
        if (config.language == Language.BANGLA) {
            sdkScope.launch { translator.ensureModelReady() }
        }
    }

    /**
     * The CHW identity last supplied by [onHomeScreenShown].
     * Read by [CoachingFlowActivity] when it is launched without an explicit chwId extra.
     */
    @Volatile
    var currentCHWId: String? = null
        internal set

    /** Lazy-initialized model lifecycle manager (download, verify, state). */
    val modelManager: ModelManager by lazy { ModelManager(config) }

    /** SDK-owned Room database — separate from SPICE's NCDMergerDatabase. */
    internal val database: MicroCoachingDatabase by lazy {
        MicroCoachingDatabase.getInstance(config.context)
    }

    /** Retrofit API service for the Knowledge Layer backend. */
    val apiService: CoachingApiService by lazy { NetworkModule.createApiService(config) }

    /** On-device LLM router — selects GemmaService or LiteRtLmService based on model file extension. */
    val inferenceRouter: InferenceRouter by lazy { InferenceRouter(config) }

    /** On-device EN↔BN translator. Active whenever [language] is [Language.BANGLA]. */
    val translator: OnDeviceTranslator by lazy { OnDeviceTranslator() }

    /**
     * Observable lifecycle of the on-device translation pack.
     *
     * UI surfaces (chat, coaching card) render a status chip when this is in
     * [com.medtroniclabs.microcoaching.ai.translation.TranslationModelState.Downloading]
     * or [com.medtroniclabs.microcoaching.ai.translation.TranslationModelState.Failed]
     * — see `TranslationModelStateChip` in the UI layer. Hosts can also observe
     * this directly to show a top-level banner if desired.
     */
    val translationModelState: kotlinx.coroutines.flow.StateFlow<com.medtroniclabs.microcoaching.ai.translation.TranslationModelState>
        get() = translator.state

    /**
     * Optional speech-to-text controller for the chat mic button.
     *
     * Phase 3 ships this as `null` by default; [com.medtroniclabs.microcoaching.ui.chat.CoachingChatFragment]
     * falls back to [com.medtroniclabs.microcoaching.ai.voice.NoOpVoiceInputController]
     * which surfaces a "coming soon" toast on tap.
     *
     * Phase 6 of the SDK roadmap (voice I/O) populates this with a real STT engine
     * (Bangla Whisper / on-device). Hosts may also assign a custom controller before
     * the chat surface is shown.
     */
    @Volatile
    var voiceInputController: com.medtroniclabs.microcoaching.ai.voice.VoiceInputController? = null

    /**
     * Pull-pattern data access interface.
     * SPICE can inject this via Hilt to query coaching data on demand.
     *
     * Alternative push pattern: register [MicroCoachingDataCallback] in [Builder.dataCallback].
     */
    val dataRepository: CoachingDataRepository by lazy {
        val chatRepo = ChatRepositoryImpl(database.chatMessageDao())
        val eventRepo = CoachingEventRepositoryImpl(database.coachingEventDao(), config)
        object : CoachingDataRepository {
            override suspend fun getChatHistory(sessionId: String) =
                chatRepo.getHistory(sessionId)
            override suspend fun getAllSessionIds() =
                chatRepo.getAllSessionIds()
            override suspend fun getPendingCoachingEvents() =
                eventRepo.getPendingCoachingEvents()
            override suspend fun markEventsSynced(eventIds: List<String>) =
                eventRepo.markEventsSynced(eventIds)
            override suspend fun exportAllData() = SdkDataExport(
                chatMessages = chatRepo.getAllMessages(),
                coachingEvents = eventRepo.getPendingCoachingEvents(),
            )
        }
    }

    // ── SPICE Workflow Event Hooks (Phase 1) ──────────────────────────────────
    // Stubs — implementations arrive in Phase 1 when SPICE hook signatures are confirmed.

    /**
     * Call when the CHW opens the SPICE home screen. Stores [chwId], immediately
     * surfaces modules from the local cache (fast path), then kicks off a
     * background morning-cards fetch and refreshes the list when it lands.
     *
     * **3-tier resolution:**
     * 1. If `morning_card_cache` has rows → join with `module_cache` in rank order.
     * 2. Else if `TriggerEvaluator` returns rows → use those (local gap/trigger logic).
     * 3. Else → empty list.
     */
    fun onHomeScreenShown(chwId: String) {
        currentCHWId = chwId
        sdkScope.launch {
            refreshMorningModules(chwId)
        }
    }

    /** Returns a lightweight health snapshot — useful for startup logging. */
    /**
     * Returns the highest-priority morning module to surface on the home screen
     * and modules screen. Priority: gap-sourced items (already ranked first by
     * the backend in [morningCardsItems]) → first item in [morningModules].
     *
     * Both [MorningCard] (home screen) and [QuizRefresherCard] (modules screen)
     * call this so they always show the same module.
     */
    fun getSelectedMorningModule(): ModuleEntity? = morningModules.value.firstOrNull()

    fun checkHealth(): SdkHealthReport {
        val model = modelManager.findLocalModel()
        return SdkHealthReport(
            isModelPresent = model != null,
            modelFileSizeBytes = model?.length() ?: 0L,
            modelStateName = modelManager.state.value::class.simpleName ?: "Unknown",
            morningCardCount = _morningModules.value.size,
        )
    }

    /**
     * Call when the CHW selects a patient.
     * @param patientId Use [patientTrackId] from SPICE PatientDetailsModel.
     */
    fun onPatientSelected(patientId: String) { /* Phase 1 */ }

    /**
     * Call after a successful assessment submission.
     * Primary UC-2 Apply trigger point — resolves the best coaching card for the patient
     * and pushes the result to [latestCardState] for [CoachingCardFragment] to observe.
     *
     * @param encounterId SPICE assessment/encounter ID for telemetry tracing. Pass `""` until Q9 is confirmed.
     * @param patientId Raw SPICE patient ID (will be SHA-256 hashed before any backend call).
     * @param assessmentData Optional map of patient vitals and clinical flags from SPICE ViewModel.
     *   Supported keys (TEAM-CONFIRM): `patient_track_id`, `age`, `gender`, `avg_systolic`,
     *   `avg_diastolic`, `bmi`, `cvd_risk_level`, `fbs_value`, `is_pregnant`,
     *   `is_htn_diagnosis`, `is_diabetes_diagnosis`, `upazila_id`.
     *   When empty the SDK will fall back to a condition-agnostic cached card.
     */
    fun onAssessmentSubmitted(
        encounterId: String,
        patientId: String,
        assessmentData: Map<String, Any> = emptyMap(),
    ) {
        val chwId = currentCHWId ?: return
        val payload = assessmentData.mapValues { it.value.toString() } +
            ("encounter_id" to encounterId) +
            ("patient_id" to patientId)
        evaluateWorkflowSignal(chwId, "assessment_submitted", payload)
    }

    /** Call when SPICE finalises a form submission (Phase 8 workflow hook). */
    fun onFormSubmitted(formId: String, payload: Map<String, String> = emptyMap()) {
        val chwId = currentCHWId ?: return
        evaluateWorkflowSignal(chwId, "form_submitted", payload + ("form_id" to formId))
    }

    /** Call when a SPICE rule fires (Phase 8 workflow hook). */
    fun onRuleFired(ruleId: String, payload: Map<String, String> = emptyMap()) {
        val chwId = currentCHWId ?: return
        evaluateWorkflowSignal(chwId, "rule_fired", payload + ("rule_id" to ruleId))
    }

    /**
     * Call when the CHW opens the morning routine. Re-runs the 3-tier morning
     * resolution (same as [onHomeScreenShown]) and updates [latestModule].
     */
    fun onMorningOpen() {
        val chwId = currentCHWId ?: return
        sdkScope.launch {
            refreshMorningModules(chwId)
            _latestModule.value = _morningModules.value.firstOrNull()
        }
    }

    /**
     * Core 3-tier morning resolution used by both [onHomeScreenShown] and [onMorningOpen].
     *
     * Step 1: seed the list immediately from the local cache (already-loaded from DB
     * or the in-memory [_morningCardsItems] state).
     * Step 2: attempt a live `GET /morning/cards` fetch; on success, replace the cache
     * and recompute the ordered list.
     * Step 3: fall back to the local [TriggerEvaluator] when the cache is empty.
     */
    private suspend fun refreshMorningModules(chwId: String) {
        try {
            // ── Tier 1 / Tier 2 seed: use whatever is already in the cache ──
            val cached = database.morningCardCacheDao().getAllOrderedOnce()
            if (cached.isNotEmpty()) {
                _morningCardsItems.value = cached
                _morningModules.value = resolveFromCache(cached)
            }

            if (config.backendUrl.isNotBlank()) {
                // ── Live fetch in background ──
                val syncApi = SyncApi(
                    apiService = apiService,
                    db = database,
                    sessionId = "morning-refresh",
                    chwId = chwId,
                )
                val result = syncApi.pullMorningCards(
                    chwId = chwId,
                    tenantId = config.tenantId.takeIf { it.isNotBlank() },
                )
                if (result.success) {
                    val fresh = database.morningCardCacheDao().getAllOrderedOnce()
                    _morningCardsItems.value = fresh
                    _morningModules.value = resolveFromCache(fresh)
                    Log.i(TAG, "Morning cards refreshed: ${result.count} items (gap=${fresh.count { it.source == "gap" }})")
                } else {
                    Log.d(TAG, "Morning cards live fetch skipped/failed: ${result.error}")
                }
            }

            // ── Tier 3: local trigger evaluator when cache is still empty ──
            if (_morningModules.value.isEmpty()) {
                val fallback = triggerEvaluator.evaluateMorningList(chwId)
                _morningModules.value = fallback
                Log.d(TAG, "Morning modules via local TriggerEvaluator: ${fallback.size}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshMorningModules failed: ${e.message}")
            if (_morningModules.value.isEmpty()) _morningModules.value = emptyList()
        }
    }

    /** Joins [MorningCardCacheEntity] items with [module_cache] in rank order. */
    private suspend fun resolveFromCache(cache: List<MorningCardCacheEntity>): List<ModuleEntity> {
        if (cache.isEmpty()) return emptyList()
        val byId = cache.associate { it.moduleId to it.rank }
        val allModules = database.moduleDao().getAllOrderedOnce()
        val matched = allModules.filter { it.moduleId in byId }
        val dropped = byId.keys - matched.map { it.moduleId }.toSet()
        Log.d(TAG, "resolveFromCache: cache=${cache.size} allModules=${allModules.size} " +
            "matched=${matched.size} dropped=${dropped.size}" +
            (if (dropped.isNotEmpty()) " droppedIds=$dropped" else ""))
        return matched.sortedBy { byId[it.moduleId] ?: Int.MAX_VALUE }
    }

    /** Call when SPICE surfaces a risk flag for the active patient. */
    fun onRiskFlagObserved(riskLevel: String, patientId: String? = null) {
        val chwId = currentCHWId ?: return
        val payload = mutableMapOf("risk_level" to riskLevel)
        if (patientId != null) payload["patient_id"] = patientId
        evaluateWorkflowSignal(chwId, "risk_flag_observed", payload)
    }

    /** Call when SPICE reports an equipment anomaly (BP cuff, glucometer, …). */
    fun onEquipmentAnomaly(detail: String) {
        val chwId = currentCHWId ?: return
        evaluateWorkflowSignal(chwId, "equipment_anomaly", mapOf("detail" to detail))
    }

    /**
     * Persist a module-quiz outcome locally and update [ChwModuleCompletionEntity].
     * Telemetry is emitted by the caller (the Learn flow already records
     * `quiz_completed` via [EventRecorder]).
     */
    fun onModuleQuizCompleted(
        moduleFamilyId: String,
        moduleId: String?,
        scoreFraction: Float,
        passed: Boolean,
    ) {
        val chwId = currentCHWId ?: return
        sdkScope.launch {
            try {
                val dao = database.chwModuleCompletionDao()
                val previous = dao.get(chwId, moduleFamilyId)
                val updated = buildModuleCompletion(
                    previous = previous,
                    chwId = chwId,
                    moduleFamilyId = moduleFamilyId,
                    moduleId = moduleId,
                    scoreFraction = scoreFraction,
                    passed = passed,
                    reinforcementDays = config.periodicRefreshDays,
                )
                dao.upsert(updated)
            } catch (e: Exception) {
                Log.w(TAG, "onModuleQuizCompleted persist failed: ${e.message}")
            }
        }
    }

    private fun evaluateWorkflowSignal(
        chwId: String,
        spiceEventCode: String,
        payload: Map<String, String>,
    ) {
        sdkScope.launch {
            try {
                val module = triggerEvaluator.evaluate(
                    chwId,
                    TriggerEvaluator.Signal.WorkflowEvent(
                        spiceEventCode = spiceEventCode,
                        payload = payload,
                    ),
                )
                _latestModule.value = module
            } catch (e: Exception) {
                Log.w(TAG, "Workflow trigger evaluation failed for '$spiceEventCode': ${e.message}")
            }
        }
    }

    internal fun isNetworkAvailable(): Boolean {
        val cm = config.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(net)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun registerConnectivityCallback() {
        val cm = config.context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // If the SDK initialises while OFFLINE, pre-set `networkLost = true`
        // so the next `onAvailable` callback triggers a restore. The previous
        // implementation defaulted to false → the first `onAvailable` (which
        // always fires shortly after registration to report the current
        // network) was silently ignored, and pending events shipped from
        // yesterday's session sat in Room until the 15-min periodic worker
        // fired.
        if (cm.activeNetwork == null) {
            networkLost = true
            Log.d(TAG, "Network callback registering while offline — primed for restore.")
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "onAvailable(network=$network) networkLost=$networkLost")
                if (networkLost) {
                    Log.i(TAG, "Connectivity restored — flushing pending telemetry.")
                    networkLost = false
                    onConnectivityRestored()
                }
            }
            override fun onLost(network: Network) {
                Log.i(TAG, "Connectivity lost — pending events will sync when online.")
                networkLost = true
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            Log.d(TAG, "Network callback registered. activeNetwork=${cm.activeNetwork}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    /** Releases the network callback and cancels all pending sync workers. Call before replacing the SDK instance. */
    fun shutdown() {
        val cm = config.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cb ->
            try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
            networkCallback = null
        }
        syncCoordinator.cancelAll()
        Log.d(TAG, "SDK shutdown — network callback unregistered, sync cancelled.")
    }

    /**
     * Call when connectivity is restored after an offline period.
     * Triggers OTel span flush and queues an immediate outbound + inbound sync cycle.
     * Also called automatically by the SDK's internal NetworkCallback.
     */
    fun onConnectivityRestored() {
        Log.i(TAG, "onConnectivityRestored — flushing spans and triggering sync.")
        if (config.enableTelemetry) telemetry.flushPendingSpans()
        if (config.backendUrl.isNotBlank()) {
            // Outbound flush first — gets pending telemetry to the backend
            // promptly even if the inbound bundle pull stalls or fails.
            // triggerNow() then chains outbound+inbound for full freshness.
            // WorkManager dedupes / coalesces the two outbound workers, so
            // there's no double-POST risk.
            syncCoordinator.triggerOutboundNow()
            syncCoordinator.triggerNow()
        }
    }

    /**
     * Force an immediate outbound telemetry flush — bypasses the 15-min
     * WorkManager periodic interval. Intended for SDK-internal use when a
     * quiz answer or module completion has just been recorded, and as a
     * developer hook for verifying backend ingestion during integration.
     *
     * No-op when [MicroCoachingConfig.backendUrl] is blank.
     */
    fun flushTelemetryNow() {
        if (config.backendUrl.isBlank()) {
            Log.d(TAG, "flushTelemetryNow skipped — backendUrl is blank")
            return
        }
        Log.i(TAG, "flushTelemetryNow — enqueuing outbound sync.")
        syncCoordinator.triggerOutboundNow()
    }

    // ── CHW Context Storage ───────────────────────────────────────────────────

    private val chwPrefs by lazy {
        config.context.getSharedPreferences("micro_coaching_chw_prefs", Context.MODE_PRIVATE)
    }

    /**
     * Call whenever the CHW's patient list is available (home screen or patient list screen).
     * Persists an anonymised summary of recent screenings so the AI chat can answer
     * questions like "আমার আজকের স্ক্রিনিং কারা?".
     *
     * @param chwWorkContext Anonymised summary — no patient names or IDs.
     */
    fun onCHWContextUpdated(chwWorkContext: CHWWorkContext) {
        Log.d(TAG, "CHWContext [SDK] onCHWContextUpdated — count=${chwWorkContext.screenedTodayCount}, recentPatients=${chwWorkContext.recentPatients.size}")
        sdkScope.launch { storeCHWContext(chwWorkContext) }
    }

    private fun storeCHWContext(ctx: CHWWorkContext) {
        val json = Json.encodeToString(ctx)
        chwPrefs.edit().putString("chw_work_context", json).apply()
    }

    /** Returns the last CHW work context pushed via [onCHWContextUpdated], or null if none stored. */
    fun loadCHWContext(): CHWWorkContext? {
        val json = chwPrefs.getString("chw_work_context", null) ?: return null
        return try {
            Json.decodeFromString<CHWWorkContext>(json)
        } catch (e: Exception) {
            Log.w(TAG, "CHWContext [SDK] Failed to decode CHWWorkContext: ${e.message}")
            null
        }
    }

    private fun storeLastPatientSnapshot(snapshot: PatientSnapshot) {
        val json = Json.encodeToString(snapshot)
        chwPrefs.edit().putString("last_patient_snapshot", json).apply()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "CHWContext [SDK] Persisted PatientSnapshot for patient=${snapshot.patientId}")
        }
    }

    /** Returns the snapshot from the most recent assessment, or null if none stored. */
    fun loadLastPatientSnapshot(): PatientSnapshot? {
        val json = chwPrefs.getString("last_patient_snapshot", null) ?: return null
        return try {
            Json.decodeFromString<PatientSnapshot>(json)
        } catch (e: Exception) {
            Log.w(TAG, "CHWContext [SDK] Failed to decode PatientSnapshot: ${e.message}")
            null
        }
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    class Builder(private val context: Context) {
        private var language: Language = Language.BANGLA
        private var tenantId: String = ""
        private var backendUrl: String = ""
        private var authToken: String = ""
        private var connectionTimeoutSeconds: Int = 30
        private var readTimeoutSeconds: Int = 60
        private var enableTelemetry: Boolean = false
        private var otelEndpoint: String = ""
        private var otelServiceName: String = "micro-coaching-android"
        private var otelHeaders: Map<String, String> = emptyMap()
        private var otelSamplingRate: Double = 1.0
        private var otelBatchExportIntervalMs: Long = 5_000L
        private var otelMaxBatchSize: Int = 512
        private var enableOtelDebugLogging: Boolean = false
        private var modelPath: String = ""
        private var modelDownloadStrategy: ModelDownloadStrategy = ModelDownloadStrategy.ON_FIRST_USE
        private var wifiOnlyModelDownload: Boolean = true
        private var modelProviders: List<ModelProvider> = ModelProvider.DEFAULT_ORDER
        private var huggingFaceToken: String = ""
        private var huggingFaceModelUrl: String = ModelProvider.DEFAULT_HF_MODEL_URL
        private var maxInferenceTokens: Int = 512
        private var inferenceTemperature: Float = 0.6f
        private var enableChat: Boolean = true
        private var enableVoice: Boolean = false
        private var enableLearnModule: Boolean = false
        private var enableApplyModule: Boolean = false
        private var enableMeasureModule: Boolean = false
        private var dataCallback: MicroCoachingDataCallback? = null
        private var uiTheme: CoachingUiTheme = CoachingUiTheme.SYSTEM
        private var forcedMode: CoachingMode? = null

        fun language(l: Language) = apply { language = l }
        fun tenantId(id: String) = apply { tenantId = id }
        fun backendUrl(url: String) = apply { backendUrl = url }
        /** Pass the SPICE JWT here. SDK forwards it as `Authorization: Bearer <token>`. */
        fun authToken(token: String) = apply { authToken = token }
        fun connectionTimeout(seconds: Int) = apply { connectionTimeoutSeconds = seconds }
        fun readTimeout(seconds: Int) = apply { readTimeoutSeconds = seconds }
        fun enableTelemetry(enabled: Boolean) = apply { enableTelemetry = enabled }
        fun otelEndpoint(url: String) = apply { otelEndpoint = url }
        fun otelServiceName(name: String) = apply { otelServiceName = name }
        /** HTTP headers for OTLP export. SigNoz: `"signoz-access-token"`. Grafana: `"Authorization"`. */
        fun otelHeaders(headers: Map<String, String>) = apply { otelHeaders = headers }
        fun otelSamplingRate(rate: Double) = apply { otelSamplingRate = rate.coerceIn(0.0, 1.0) }
        fun otelBatchExportIntervalMs(ms: Long) = apply { otelBatchExportIntervalMs = ms }
        fun otelMaxBatchSize(size: Int) = apply { otelMaxBatchSize = size }
        fun enableOtelDebugLogging(enabled: Boolean) = apply { enableOtelDebugLogging = enabled }
        fun modelPath(path: String) = apply { modelPath = path }
        fun modelDownloadStrategy(strategy: ModelDownloadStrategy) = apply { modelDownloadStrategy = strategy }
        fun wifiOnlyModelDownload(wifiOnly: Boolean) = apply { wifiOnlyModelDownload = wifiOnly }
        /** Override provider priority or disable a provider entirely. Default: Backend → HuggingFace. */
        fun modelProviders(providers: List<ModelProvider>) = apply { modelProviders = providers }
        /** HuggingFace Hub token for gated model downloads. Set HUGGING_FACE_TOKEN in local.properties for dev. */
        fun huggingFaceToken(token: String) = apply { huggingFaceToken = token }
        /** Override the HuggingFace model download URL. Defaults to Gemma3-1B-IT INT4. */
        fun huggingFaceModelUrl(url: String) = apply { huggingFaceModelUrl = url }
        fun maxInferenceTokens(tokens: Int) = apply { maxInferenceTokens = tokens }
        fun inferenceTemperature(temp: Float) = apply { inferenceTemperature = temp }
        fun enableChat(enabled: Boolean) = apply { enableChat = enabled }
        fun enableVoice(enabled: Boolean) = apply { enableVoice = enabled }
        fun enableLearnModule(enabled: Boolean) = apply { enableLearnModule = enabled }
        fun enableApplyModule(enabled: Boolean) = apply { enableApplyModule = enabled }
        fun enableMeasureModule(enabled: Boolean) = apply { enableMeasureModule = enabled }
        fun dataCallback(callback: MicroCoachingDataCallback) = apply { dataCallback = callback }
        /** Set the colour scheme for SDK-owned screens. Default: [CoachingUiTheme.SYSTEM]. */
        fun uiTheme(theme: CoachingUiTheme) = apply { uiTheme = theme }
        /**
         * Force a specific coaching mode regardless of network or RAM state.
         * Dev/test only — leave unset in production.
         */
        fun forceMode(mode: CoachingMode) = apply { forcedMode = mode }

        fun build(): MicroCoachingSDK {
            val config = MicroCoachingConfig(
                context = context.applicationContext,
                language = language,
                tenantId = tenantId,
                backendUrl = backendUrl,
                authToken = authToken,
                connectionTimeoutSeconds = connectionTimeoutSeconds,
                readTimeoutSeconds = readTimeoutSeconds,
                enableTelemetry = enableTelemetry,
                otelEndpoint = otelEndpoint,
                otelServiceName = otelServiceName,
                otelHeaders = otelHeaders,
                otelSamplingRate = otelSamplingRate,
                otelBatchExportIntervalMs = otelBatchExportIntervalMs,
                otelMaxBatchSize = otelMaxBatchSize,
                enableOtelDebugLogging = enableOtelDebugLogging,
                modelPath = modelPath,
                modelDownloadStrategy = modelDownloadStrategy,
                wifiOnlyModelDownload = wifiOnlyModelDownload,
                modelProviders = modelProviders,
                huggingFaceToken = huggingFaceToken,
                huggingFaceModelUrl = huggingFaceModelUrl,
                maxInferenceTokens = maxInferenceTokens,
                inferenceTemperature = inferenceTemperature,
                enableChat = enableChat,
                enableVoice = enableVoice,
                enableLearnModule = enableLearnModule,
                enableApplyModule = enableApplyModule,
                enableMeasureModule = enableMeasureModule,
                dataCallback = dataCallback,
                uiTheme = uiTheme,
                forcedMode = forcedMode,
            )
            val sdk = MicroCoachingSDK(config)
            synchronized(Companion) {
                instance?.shutdown()
                instance = sdk
            }

            Log.i(TAG, "SDK initialized — backendUrl=${config.backendUrl.isNotBlank()} " +
                "modelPath=${config.modelPath.isNotBlank()} " +
                "forcedMode=${config.forcedMode ?: "auto"}")

            // Kick off model download if configured to do so at init
            if (config.modelDownloadStrategy == ModelDownloadStrategy.ON_SDK_INIT) {
                sdk.modelManager.scheduleDownloadIfNeeded()
            }

            return sdk
        }
    }

    companion object {
        private const val TAG = "MicroCoachingSDK"

        @Volatile
        private var instance: MicroCoachingSDK? = null

        /**
         * Returns the initialized SDK singleton.
         * @throws IllegalStateException if [Builder.build] has not been called yet.
         */
        fun getInstance(): MicroCoachingSDK =
            instance ?: error(
                "MicroCoachingSDK is not initialized. " +
                    "Call MicroCoachingSDK.Builder(context).build() in Application.onCreate()."
            )

        /** Returns true if the SDK has been initialized. */
        fun isInitialized(): Boolean = instance != null
    }
}
