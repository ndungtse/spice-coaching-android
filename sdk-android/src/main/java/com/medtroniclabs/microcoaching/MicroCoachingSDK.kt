package com.medtroniclabs.microcoaching

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.medtroniclabs.microcoaching.data.asset.AssetCache
import com.medtroniclabs.microcoaching.data.db.MICRO_COACHING_ROOM_VERSION
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.sortedForDisplay
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.sync.SyncPrefs
import com.medtroniclabs.microcoaching.sync.SyncStatusStore
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.ActionGapLink
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.GapStateConfig
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.MorningSourcesConfig
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.OnDeviceMorningGenerator
import com.medtroniclabs.microcoaching.domain.morning.MorningModuleResolver
import com.medtroniclabs.microcoaching.domain.triggers.TriggerEvaluator
import com.medtroniclabs.microcoaching.progress.buildModuleCompletion
import com.medtroniclabs.microcoaching.sdk.chat.ChatKnowledgeIndexBootstrap
import com.medtroniclabs.microcoaching.sdk.context.ChwContextStore
import com.medtroniclabs.microcoaching.sdk.hooks.handleAssessmentSubmitted
import com.medtroniclabs.microcoaching.sdk.hooks.handleReferralSubmitted
import com.medtroniclabs.microcoaching.sdk.morning.MorningSurfaceCoordinator
import com.medtroniclabs.microcoaching.sdk.morning.PersonaPolicy
import com.medtroniclabs.microcoaching.sdk.morning.SkippedRefresherStore
import com.medtroniclabs.microcoaching.sdk.runtime.SdkNetworkMonitor
import com.medtroniclabs.microcoaching.ai.model.ModelCatalog
import com.medtroniclabs.microcoaching.ai.model.ModelProvider
import com.medtroniclabs.microcoaching.ai.model.ModelVariant
import com.medtroniclabs.microcoaching.data.repository.ChatRepositoryImpl
import com.medtroniclabs.microcoaching.data.repository.CoachingEventRepositoryImpl
import com.medtroniclabs.microcoaching.ai.model.ModelManager
import com.medtroniclabs.microcoaching.domain.context.CHWWorkContext
import com.medtroniclabs.microcoaching.domain.context.PatientSnapshot
import com.medtroniclabs.microcoaching.domain.context.TodaysVisit
import com.medtroniclabs.microcoaching.domain.gaps.BpAboveThresholdEvaluator
import com.medtroniclabs.microcoaching.domain.gaps.GapRuleDispatcher
import com.medtroniclabs.microcoaching.domain.gaps.GlucoseAboveThresholdEvaluator
import com.medtroniclabs.microcoaching.domain.gaps.MissingDangerSignsEvaluator
import com.medtroniclabs.microcoaching.domain.gaps.WrongFacilityTierEvaluator
import com.medtroniclabs.microcoaching.domain.gaps.evidence.EvidenceBuilder
import com.medtroniclabs.microcoaching.domain.lifecycle.VisitCompletedHandler
import com.medtroniclabs.microcoaching.ai.translation.OnDeviceTranslator
import com.medtroniclabs.microcoaching.domain.decision.CoachingMode
import com.medtroniclabs.microcoaching.network.CoachingApiService
import com.medtroniclabs.microcoaching.network.NetworkModule
import com.medtroniclabs.microcoaching.sdk.CoachingDataRepository
import com.medtroniclabs.microcoaching.sdk.MicroCoachingDataCallback
import com.medtroniclabs.microcoaching.sdk.SdkDataExport
import com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder
import com.medtroniclabs.microcoaching.domain.telemetry.PatientIdHasher
import com.medtroniclabs.microcoaching.domain.telemetry.TelemetryManager
import com.medtroniclabs.microcoaching.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import com.medtroniclabs.microcoaching.domain.validation.OutputValidator
import  com.medtroniclabs.microcoaching.ai.voice.stt.SttModelManager
import com.medtroniclabs.microcoaching.ai.voice.VoiceInputController
import com.medtroniclabs.microcoaching.ai.retrieval.ModuleKnowledgeIndex
import com.medtroniclabs.microcoaching.ai.retrieval.RetrievalHintOverlay
import com.medtroniclabs.microcoaching.ai.translation.TranslationModelState
import com.medtroniclabs.microcoaching.ai.voice.OfflineSttEngine
import com.medtroniclabs.microcoaching.ai.voice.AndroidSpeechRecognizerEngine
import com.medtroniclabs.microcoaching.ai.voice.ChatVoiceInputController
import com.medtroniclabs.microcoaching.domain.system.DeviceCapability
import com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore
import com.medtroniclabs.microcoaching.domain.config.LearningPoints
import com.medtroniclabs.microcoaching.ui.learn.QuizRetryGate



/** Startup health snapshot returned by [MicroCoachingSDK.checkHealth]. */
data class SdkHealthReport(
    val isModelPresent: Boolean,
    val modelFileSizeBytes: Long,
    val modelStateName: String,
    val morningCardCount: Int,
)

/**
 * Singleton entry point for the SDK. Build once in `Application.onCreate()` via
 * [Builder], then read it anywhere with [getInstance]. Hosts can also inject
 * [dataRepository] ([CoachingDataRepository]).
 */
class MicroCoachingSDK private constructor(val config: MicroCoachingConfig) {

    // CoroutineExceptionHandler: an uncaught exception in SDK background work
    // must never take the HOST app down. Without it, a transient failure in an
    // init-block collector (e.g. a DB read racing a sync delete) propagated as
    // a fatal crash on a worker thread — an SDK is a guest in the host process.
    internal val sdkScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "Uncaught error in SDK background scope", throwable)
            },
    )

    /**
     * One coaching session id per app process (generated once with the singleton;
     * not reset on logout / CHW switch). Tags every learn-flow event so the backend
     * groups a CHW's module + quiz interactions together.
     */
    val coachingSessionId: String = UUID.randomUUID().toString()

    /**
     * `true` below the 3 GB-RAM threshold: too small for the on-device Gemma model,
     * so chat runs retrieval-only (BM25 over the module corpus, no LLM). Probed once
     * via [DeviceCapability]; overridable with [MicroCoachingConfig.forceLowEndMode] (QA).
     */
    val isLowEndDevice: Boolean =
        config.forceLowEndMode
            ?: DeviceCapability
                .isLowEndDevice(config.context)

    /**
     * Active language for SDK UI and LLM prompts. Set via [Builder.language];
     * changeable at runtime with [setLanguage] (takes effect on the next SDK screen open).
     */
    @Volatile
    var language: Language = config.language
        private set

    /** Change language at runtime without rebuilding. Applies to LLM prompts now, UI on next screen open. */
    fun setLanguage(language: Language) {
        this.language = language
        // The EN↔BN pack is needed in both languages (BN for the Gemma round-trip,
        // EN to translate chat to/from the bn-only backend), so ensure it either way.
        sdkScope.launch { translator.ensureModelReady() }
    }

    /**
     * Update the bearer token for all backend calls without rebuilding. The auth
     * interceptor reads it per request, so it applies from the next call. Prefer this
     * over [Builder.build] on login / token refresh (a rebuild reconstructs every
     * service; this is one volatile write).
     */
    fun updateAuthToken(token: String) {
        config.authToken = token
        Log.i(TAG, "Auth token updated (length=${token.length}).")
    }

    private val _morningModules = MutableStateFlow<List<ModuleEntity>>(emptyList())
    /** Modules currently published. Populated by [onHomeScreenShown]. */
    val morningModules: StateFlow<List<ModuleEntity>> = _morningModules.asStateFlow()

    private val _morningRefresherDismissed = MutableStateFlow(false)
    /**
     * True when the CHW has completed or skipped the morning refresher in the current
     * home-screen session. Resets to false on each [onHomeScreenShown] call.
     * Observed by the SPICE home screen banner to hide MorningCard / LearnCard.
     */
    val morningRefresherDismissed: StateFlow<Boolean> = _morningRefresherDismissed.asStateFlow()

    /** Hide the morning banner for this session (called from RefresherContent on Done/Skip). */
    fun dismissMorningRefresher() {
        _morningRefresherDismissed.value = true
    }

    private val _morningCardsItems = MutableStateFlow<List<MorningCardCacheEntity>>(emptyList())
    /**
     * Last-known backend-prioritised morning-card list (populated after a
     * `GET /morning/cards`). [QuickLearnViewModel] / [LearnViewModel] read it to
     * attach each module's `behaviouralGapId` / `source`.
     */
    val morningCardsItems: StateFlow<List<MorningCardCacheEntity>> = _morningCardsItems.asStateFlow()

    // ── Skipped-refresher set (persisted per CHW) — owned by [SkippedRefresherStore] ──
    // `chwPrefs` is passed as a provider so the store never forces that lazy at construction.
    private val skippedRefresherStore = SkippedRefresherStore(sdkScope, { chwPrefs }, { currentCHWId })

    /** The skipped-refresher family-id set (used by [QuickLearnViewModel] to hide skipped banners). */
    internal val skippedRefresherFamilyIds: StateFlow<Set<String>> = skippedRefresherStore.familyIds

    /**
     * Number of refreshers the CHW skipped this home session. The SPICE host observes this to
     * render a count badge on the "Coaching" home-grid tile.
     */
    val skippedRefresherCount: StateFlow<Int> = skippedRefresherStore.count

    // ── Home-tile assignment indicators (MED-I629) ────────────────────────────
    // Two independent booleans the SPICE "Coaching" tile overlays as corner
    // icons (video play + notification bell). Both are `Eagerly` so the tile
    // never flashes a stale `false` before a collector attaches.

    /** Backs [hasIncompleteAssignedVideos]; updated at the single [currentCHWId] write site. */
    private val _currentChwIdFlow = MutableStateFlow<String?>(null)

    /**
     * Home-tile video indicator: true while the current CHW has at least one
     * assigned video not yet completed. Re-subscribes to the new CHW's row set
     * on a user switch; an EXISTS query keeps this off the hot path (no rows
     * loaded). Emits `false` until a CHW is known / when none is set.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hasIncompleteAssignedVideos: StateFlow<Boolean> =
        _currentChwIdFlow
            .flatMapLatest { id ->
                if (id.isNullOrBlank()) flowOf(false)
                else database.assignedVideoDao().hasIncompleteFlow(id)
            }
            .distinctUntilChanged()
            .stateIn(sdkScope, SharingStarted.Eagerly, false)

    /**
     * Home-tile module indicator: true while the current CHW has incomplete
     * assigned Training modules. Passthrough of the store's `Eagerly` projection.
     */
    val hasIncompleteTrainingModules: StateFlow<Boolean>
        get() = coachingModuleStore.hasIncompleteTrainingModules

    /** Record that the CHW skipped a refresher (Skip button or swipe-away). Persisted per CHW. */
    fun markRefresherSkipped(moduleFamilyId: String) = skippedRefresherStore.markSkipped(moduleFamilyId)

    /** Drop a refresher from the skipped set once the CHW has completed it. */
    fun clearRefresherSkipped(moduleFamilyId: String) = skippedRefresherStore.clearSkipped(moduleFamilyId)

    /** Keep only skipped ids still in the active refresher pool. See [SkippedRefresherStore.retainActive]. */
    fun retainActiveSkippedRefreshers(activeFamilyIds: Set<String>) =
        skippedRefresherStore.retainActive(activeFamilyIds)

    private fun loadSkippedRefreshers(chwId: String) = skippedRefresherStore.load(chwId)

    private val _latestModule = MutableStateFlow<ModuleEntity?>(null)
    /**
     * The most recent module surfaced by a workflow- or gap-trigger evaluation.
     * Host surfaces (e.g. SPICE's assessment-summary banner) collect this to
     * decide when to show the coaching card.
     */
    val latestModule: StateFlow<ModuleEntity?> = _latestModule.asStateFlow()

    // ── Service graph (declared leaf → dependent) ─────────────────────────────
    // `database` heads the graph and is eager: every domain object below and the
    // init-block collectors touch it, so constructing it first (before init{})
    // makes a null-delegate NPE structurally impossible and lets the compiler
    // enforce the ordering of the eager evaluators that follow. Room's handle is
    // inert until the first query, so there's no launch-time I/O.

    /** SDK-owned Room database — separate from SPICE's NCDMergerDatabase. */
    internal val database: MicroCoachingDatabase = MicroCoachingDatabase.getInstance(config.context)

    private val triggerEvaluator: TriggerEvaluator = TriggerEvaluator(
        triggerDao = database.triggerDefinitionDao(),
        bindingDao = database.moduleTriggerBindingDao(),
        moduleDao = database.moduleDao(),
        gapProfileDao = database.chwGapProfileDao(),
        behaviouralGapDao = database.behaviouralGapDao(),
        configDao = database.configThresholdDao(),
        config = config,
    )

    /**
     * `moduleFamilyId → real action-gap id` resolved on-device by
     * [onDeviceMorningGenerator]. When the backend's morning card carries only a
     * `module_primary_gap_*` placeholder (no rule), [coachingModuleStore] overlays
     * this to keep the refresher surfaced even after the module is completed.
     * Empty until the generator runs.
     */
    private val _onDeviceActionGapLinks = MutableStateFlow<Map<String, ActionGapLink>>(emptyMap())

    /**
     * On-device replica of `GET /morning/cards` — derives gap state from cached
     * events and writes the refresher set into `morning_card_cache`. Used by
     * [morningModuleResolver] when the live morning endpoint is unavailable.
     */
    private val onDeviceMorningGenerator: OnDeviceMorningGenerator = OnDeviceMorningGenerator(
        database = database,
        config = GapStateConfig(
            passThreshold = config.quizPassThreshold / 100f,
            escalationFailureCount = config.escalationFailureCount,
            escalationWindowDays = config.escalationWindowDays,
            occurrenceWindowDays = config.triggerWindowDays,
        ),
        sources = MorningSourcesConfig(
            quiz = config.refresherTuning.enableQuizRefreshers,
            gap = config.refresherTuning.enableGapRefreshers,
            referral = config.refresherTuning.enableReferralRefreshers,
            visit = config.refresherTuning.enableVisitRefreshers,
        ),
        publishActionGapLinks = { _onDeviceActionGapLinks.value = it },
        loadTodaysVisits = { loadTodaysVisits() },
    )

    /** 4-tier morning-module resolution, extracted from this class (F9). */
    internal val morningModuleResolver: MorningModuleResolver by lazy {
        MorningModuleResolver(
            database = database,
            config = config,
            apiService = apiService,
            onDeviceMorningGenerator = onDeviceMorningGenerator,
            langCode = { if (language == Language.ENGLISH) "en" else "bn" },
            morningModules = _morningModules,
            morningCardsItems = _morningCardsItems,
        )
    }

    /**
     * Single source of truth for the categorised module lists (refreshers /
     * training) and the one featured refresher pick, shared by the home card and
     * the modules screen (they sit in different Activities, so it lives here, not a
     * ViewModel). Lazy so its eager Room collectors start only post-construction.
     */
    internal val coachingModuleStore: CoachingModuleStore by lazy {
        CoachingModuleStore(
            database = database,
            scope = sdkScope,
            chwIdProvider = { currentCHWId },
            langProvider = { if (language == Language.ENGLISH) "en" else "bn" },
            skippedIds = skippedRefresherStore.familyIds,
            onDeviceActionGapLinks = _onDeviceActionGapLinks.asStateFlow(),
        )
    }

    /**
     * Resolved learning-points weights (per-correct-answer multiplier, module-
     * completion total, etc.), derived live from the cached `config_threshold`
     * rows synced via `GET /sync/config`. Falls back to the documented v3
     * defaults per key when a row is missing or its value isn't an integer.
     *
     * Shared across surfaces: the in-quiz "+N XP" burst reads
     * [LearningPoints.quizScoreMultiplier]; the result screen total comes from
     * [LearningPoints.moduleQuizXp]. Lazy so its eager collector starts only
     * post-construction (avoiding the lazy-`database` init-order hazard).
     */
    val learningPoints: StateFlow<LearningPoints> by lazy {
        database.configThresholdDao().getGlobalFlow()
            .map { LearningPoints.from(it) }
            .stateIn(sdkScope, SharingStarted.Eagerly, LearningPoints())
    }

    /**
     * Admin-configured quiz-reattempt window in **days**, derived from the cached
     * `config_threshold` rows; falls back to [QuizRetryGate.QUIZ_RETRY_WINDOW_DAYS]
     * (7). Read by `LearnViewModel.canRetryActiveQuiz` to gate "Try Again". Lazy so
     * its eager collector starts post-construction.
     */
    val quizReattemptValidityDays: StateFlow<Long> by lazy {
        database.configThresholdDao().getGlobalFlow()
            .map { rows ->
                QuizRetryGate.resolveValidityDays(
                    rows.firstOrNull { it.key == QuizRetryGate.KEY_QUIZ_REATTEMPT_VALIDITY_DAYS }?.value,
                )
            }
            .stateIn(sdkScope, SharingStarted.Eagerly, QuizRetryGate.QUIZ_RETRY_WINDOW_DAYS)
    }

    /**
     * Featured refresher from [coachingModuleStore], mapped back to a [ModuleEntity]
     * so the home card + modules banner share one pick (always agree). Advances on
     * skip; null once every refresher is skipped (or for a PO — see below).
     */
    val selectedMorningModule: StateFlow<ModuleEntity?> by lazy {
        coachingModuleStore.selectedMorningCard
            // PO has no refreshers — never surface a morning card. This hides the
            // SPICE home MorningCard/LearnCard banner (its `if (current != null)`
            // guard) without the host needing its own role check.
            .map { lm ->
                if (personaPolicy.suppressesRefreshers) null
                else lm?.moduleFamilyId?.let { database.moduleDao().getByFamilyId(it) }
            }
            .stateIn(sdkScope, SharingStarted.Eagerly, null)
    }

    /**
     * Per-`rule_type` gap dispatcher with the four pilot evaluators. Only
     * [WrongFacilityTierEvaluator] is wired end-to-end today; the other three are
     * skeletons returning null. Gated by [MicroCoachingConfig.enableGapDetection]
     * — when false, [onAssessmentSubmitted] keeps its single-event emission.
     */
    internal val gapRuleDispatcher: GapRuleDispatcher = GapRuleDispatcher(
        gapDao = database.behaviouralGapDao(),
        evaluators = mapOf(
            WrongFacilityTierEvaluator().let { it.ruleType to it },
            BpAboveThresholdEvaluator().let { it.ruleType to it },
            GlucoseAboveThresholdEvaluator().let { it.ruleType to it },
            MissingDangerSignsEvaluator().let { it.ruleType to it },
        ),
    )

    /** TP-7 — visit-close handler. See [onVisitCompleted]. */
    private val visitCompletedHandler: VisitCompletedHandler =
        VisitCompletedHandler(coachingEventDao = database.coachingEventDao())

    // Teardown-sensitive services keep an explicit Lazy handle so [shutdown] can
    // release them only if actually created (touching the property would force-init).

    private val telemetryLazy = lazy { TelemetryManager(config) }

    /** OTel telemetry manager. Inactive if [MicroCoachingConfig.enableTelemetry] is false. */
    val telemetry: TelemetryManager by telemetryLazy

    /**
     * An [EventRecorder] for the SDK's hook-originated events (session id
     * `"sdk-hook"` so they group in the backend). Built per-call because
     * [EventRecorder] captures `chwId` at construction and [currentCHWId] may not
     * be set yet at SDK init.
     */
    internal fun newSdkHookRecorder(chwId: String): EventRecorder =
        EventRecorder(
            dao = database.coachingEventDao(),
            sessionId = "sdk-hook",
            chwId = chwId,
        )

    /** Sync orchestrator — schedules and triggers outbound + inbound WorkManager jobs. */
    val syncCoordinator: SyncCoordinator by lazy { SyncCoordinator(config.context) }

    private val syncPrefs: SyncPrefs by lazy { SyncPrefs(config.context) }

    /**
     * Offline cache for remote assets (thumbnails, lesson-card images): resolves an
     * asset key to a local file, downloading + persisting on first online view so it
     * renders offline thereafter. See [AssetCache].
     */
    val assetCache: AssetCache by lazy {
        AssetCache(config.context, database.cachedAssetDao(), sdkScope, minFreeBytes = config.minFreeStorageBytes)
    }

    private val translatorLazy = lazy { OnDeviceTranslator() }

    /** On-device EN↔BN translator. Active whenever [language] is [Language.BANGLA]. */
    val translator: OnDeviceTranslator by translatorLazy

    /** Lazily-built BM25 index over the on-device module corpus — see [ChatKnowledgeIndexBootstrap]. */
    private val chatIndexBootstrap = ChatKnowledgeIndexBootstrap(
        scope = sdkScope,
        config = config,
        moduleDao = { database.moduleDao() },
        retiredFamilyIds = { syncPrefs.retiredFamilyIds },
    )

    /**
     * In-memory BM25 index over the on-device module corpus; [ChatViewModel] uses it
     * to ground chat answers in cards / quiz items. Built lazily on the first chat
     * open ([ensureChatKnowledgeIndex]), not at SDK init. `empty()` until then.
     */
    val chatKnowledgeIndex: StateFlow<ModuleKnowledgeIndex> = chatIndexBootstrap.index

    /**
     * Start (once) the background collector that builds and maintains [chatKnowledgeIndex].
     * Called by [ChatViewModel] on chat open. Idempotent. See [ChatKnowledgeIndexBootstrap].
     */
    fun ensureChatKnowledgeIndex() = chatIndexBootstrap.ensure()

    /** Connectivity signal + callback lifecycle — see [SdkNetworkMonitor]. */
    private val networkMonitor = SdkNetworkMonitor(config.context) { onConnectivityRestored() }

    /**
     * Reactive SDK-observed connectivity (`true` when a network has internet). UI
     * collects this to grey out chat source chips on drop, instead of polling
     * [isNetworkAvailable] each recomposition.
     */
    val networkAvailable: StateFlow<Boolean> = networkMonitor.available

    /**
     * Per-domain outcome of the last inbound sync run, so each UI section can scope its
     * failure state to the table it reads rather than to "the sync" as a whole.
     * See [com.medtroniclabs.microcoaching.sync.SyncStatusStore].
     */
    val syncStatus: SyncStatusStore = SyncStatusStore()

    // ── Lazy services the `init` block forces ─────────────────────────────────
    // `init` launches coroutines that touch modelManager/sttModelManager (BANGLA)
    // and translator, so their lazy delegates must be declared ABOVE `init` — else
    // the IO dispatcher could resume a launched body before the field is assigned.

    private val modelManagerLazy = lazy { ModelManager(config) }
    private val sttModelManagerLazy = lazy { SttModelManager(config) }

    /** Lazy-initialized model lifecycle manager (download, verify, state). */
    val modelManager: ModelManager by modelManagerLazy

    /**
     * The on-device model this SDK will download/load — the source of truth for its
     * display name, param count, [ModelVariant.sizeInBytes], runtime and RAM class.
     * Hosts use it to render an accurate download-size label.
     */
    fun selectedModelVariant(): ModelVariant = config.selectedModelVariant()

    /**
     * On-device models the host may offer in a picker. Returns only runnable
     * variants unless [includeNonRunnable] is true (those display but won't load
     * until their runtime is bundled). Select one via [Builder.selectedModel].
     */
    fun availableModels(includeNonRunnable: Boolean = false): List<ModelVariant> =
        if (includeNonRunnable) ModelCatalog.ALLOWLIST
        else ModelCatalog.ALLOWLIST.filter { ModelCatalog.isRunnable(it) }

    /**
     * Manager for the optional offline Bengali STT model (sherpa-onnx). Observe
     * [SttModelManager.state] to render the download banner; the default voice
     * controller ([Builder.enableVoice]) auto-triggers the download on offline
     * Bengali dictation when the model isn't on disk.
     */
    val sttModelManager: SttModelManager by sttModelManagerLazy

    // Declared before `init` (not lazy): the init event-count collector reads
    // [isProgramOfficer] to skip refresher refilters for a PO.
    private val _persona = MutableStateFlow(config.persona)
    /**
     * Active coaching persona (SK / PO), seeded from [MicroCoachingConfig.persona]
     * at build time. SDK home UI branches on this; [CoachingPersona.UNKNOWN] is
     * treated as SK. The host normally sets it once via the Builder.
     */
    val persona: StateFlow<CoachingPersona> = _persona.asStateFlow()

    /** Runtime persona override (tests / late role resolution). Builder is the usual path. */
    fun setPersona(persona: CoachingPersona) {
        _persona.value = persona
    }

    /** The persona invariant (PO ⇒ no refreshers/morning) in one place — see [PersonaPolicy]. */
    private val personaPolicy = PersonaPolicy { _persona.value }

    /**
     * Morning-surface orchestration (refresh → invalidate triad, PO no-op, CHW-switch clear).
     * Collaborators passed as providers so this never forces the lazy service graph at
     * construction. See [MorningSurfaceCoordinator].
     */
    private val morningCoordinator = MorningSurfaceCoordinator(
        scope = sdkScope,
        store = { coachingModuleStore },
        resolver = { morningModuleResolver },
        morningCardCacheDao = { database.morningCardCacheDao() },
        personaPolicy = personaPolicy,
        flush = { flushTelemetryNow() },
        onMorningResolved = { _latestModule.value = _morningModules.value.firstOrNull() },
    )

    init {
        if (config.backendUrl.isNotBlank()) {
            // On a destructive Room migration, clear inbound watermarks so the next
            // pull returns a full snapshot — else the prefs watermark outlives the
            // wiped tables and progress never rehydrates.
            if (syncPrefs.lastKnownRoomVersion < MICRO_COACHING_ROOM_VERSION) {
                if (syncPrefs.lastKnownRoomVersion > 0) {
                    Log.i(
                        TAG,
                        "Room schema bumped ${syncPrefs.lastKnownRoomVersion} → $MICRO_COACHING_ROOM_VERSION " +
                            "— clearing inbound sync watermarks to force a full re-sync.",
                    )
                    syncPrefs.resetWatermarksOnly()
                }
                syncPrefs.lastKnownRoomVersion = MICRO_COACHING_ROOM_VERSION
            }
            syncCoordinator.schedulePeriodic()
            networkMonitor.register()
        }
        // EN↔BN pack: a background download CHECK (the model only loads on first
        // translate()), kept in init because the inbound FAQ bn→en backfill needs it
        // before chat opens. Needed in both languages (BN for the Gemma round-trip,
        // EN to translate chat to/from the bn-only backend).
        sdkScope.launch { translator.ensureModelReady() }
        // The Bengali STT (voice) download is no longer kicked off here. It now
        // starts when the chat opens — ChatViewModel.autoStartOnDevicePacks() —
        // so the pack downloads in parallel with the AI model on the coaching
        // setup screen instead of only after the model reaches Ready. The trigger
        // is idempotent (SttModelManager.triggerBengaliDownload no-ops when the
        // pack is present/in-flight), and the mic-tap fallback in
        // ChatVoiceInputController still covers first-dictation.

        // The chat BM25 index is NOT built here — deferred to first chat open
        // (ensureChatKnowledgeIndex). Eager build meant a full-corpus parse +
        // permanent retention at launch even for sessions that never open chat.

        // Re-apply the morning reinforce filter on each coaching_event insert so the
        // home banner walks to the next unmastered module. `drop(1)` skips the cold
        // emission (onHomeScreenShown already published); distinctUntilChanged +
        // conflate + throttle coalesce a quiz-answer burst (and Room's COUNT(*)
        // re-emitting from bulk markSynced) into one refilter of the latest state.
        sdkScope.launch {
            database.coachingEventDao().getEventCountFlow()
                .drop(1)
                .distinctUntilChanged()
                .conflate()
                .collect {
                    if (personaPolicy.suppressesRefreshers) return@collect  // PO has no refreshers to refilter
                    val chwId = currentCHWId ?: return@collect
                    // Best-effort: a failed refilter (e.g. a read racing a sync
                    // write) must not kill this collector — the next event
                    // emission retries naturally.
                    runCatching { refilterMorningModules(chwId) }
                        .onFailure { Log.w(TAG, "refilterMorningModules failed: ${it.message}") }
                    delay(RECOMPUTE_THROTTLE_MS)
                }
        }
    }

    /**
     * The CHW identity last supplied by [onHomeScreenShown].
     * Read by [CoachingFlowActivity] when it is launched without an explicit chwId extra.
     */
    @Volatile
    var currentCHWId: String? = null
        internal set

    private val okHttpClientLazy = lazy { NetworkModule.createOkHttpClient(config) }

    /**
     * The single OkHttpClient behind [apiService]. Owned here so [shutdown] can
     * evict its pool + stop its dispatcher on re-init; sync workers reuse
     * [apiService] rather than build a client per run.
     */
    internal val okHttpClient: OkHttpClient by okHttpClientLazy

    /** Retrofit API service for the Knowledge Layer backend. */
    val apiService: CoachingApiService by lazy { NetworkModule.createApiService(config, okHttpClient) }

    /**
     * Observable lifecycle of the on-device translation pack. UI renders a status
     * chip on `Downloading`/`Failed`; hosts can observe it for a top-level banner.
     */
    val translationModelState: StateFlow<TranslationModelState>
        get() = translator.state

    /**
     * Speech-to-text controller for the chat mic. When [Builder.enableVoice] is
     * true, auto-populated with [AndroidSpeechRecognizerEngine] (platform
     * `SpeechRecognizer`, EN + BN). Hosts can override via
     * [Builder.voiceInputController] or reassign this field (e.g. an
     * offline-Bengali sherpa fallback).
     */
    @Volatile
    var voiceInputController: VoiceInputController? = null

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

    // ── SPICE workflow event hooks ────────────────────────────────────────────

    /**
     * Call when the CHW opens the home screen. Stores [chwId], surfaces cached
     * modules immediately, then fetches morning cards in the background. Resolution:
     * `morning_card_cache` join → else [TriggerEvaluator] rows → else empty.
     */
    fun onHomeScreenShown(chwId: String) {
        // Detect a CHW switch (different user signed in on this device) before we
        // overwrite currentCHWId — used to isolate the previous user's morning cards.
        val switchedUser = currentCHWId != null && currentCHWId != chwId
        currentCHWId = chwId
        _currentChwIdFlow.value = chwId  // re-key the home-tile video indicator (MED-I629) to this CHW
        _morningRefresherDismissed.value = false  // reset banner visibility each time the screen opens
        // Load this CHW's persisted skipped-refresher set (survives restart / re-login).
        // Skips are per CHW, so this also swaps the set on a user switch.
        loadSkippedRefreshers(chwId)
        morningCoordinator.onHomeShown(chwId, switchedUser)
    }

    /**
     * Back-compat synchronous accessor for the featured morning module. Prefer
     * collecting the [selectedMorningModule] flow so the card advances live on
     * skip / progress.
     */
    fun getSelectedMorningModule(): ModuleEntity? = selectedMorningModule.value

    /** Lightweight health snapshot — useful for startup logging. */
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
     * Call after a successful assessment submission — the primary Apply trigger:
     * resolves the best coaching card and surfaces it via [latestModule].
     *
     * @param encounterId SPICE encounter id (telemetry tracing).
     * @param patientId raw SPICE patient id (SHA-256 hashed before any backend call).
     * @param assessmentData optional vitals / clinical flags. Recognised keys:
     *   `patient_track_id`, `age`, `gender`, `avg_systolic`, `avg_diastolic`, `bmi`,
     *   `cvd_risk_level`, `fbs_value`, `is_pregnant`, `is_htn_diagnosis`,
     *   `is_diabetes_diagnosis`, `upazila_id`. Empty → condition-agnostic cached card.
     */
    fun onAssessmentSubmitted(
        encounterId: String,
        patientId: String,
        assessmentData: Map<String, Any> = emptyMap(),
    ) = handleAssessmentSubmitted(encounterId, patientId, assessmentData)

    /**
     * Call when the CHW **commits a referral** (picks + confirms a destination).
     * This is when the `actual.*` side exists, so `spice_referral_compliance` gaps
     * are evaluated here — [onAssessmentSubmitted] fires earlier (at save) and can't.
     *
     * @param referralData compliance state carrying both the `recommended.*`
     *   (rule-engine) and `actual.*` (CHW's pick) branches; SPICE assembles it.
     *
     * Emits one gap-tagged `spice_action_observed` per fired gap (no generic row —
     * the assessment hook already wrote that). Compliance gaps must fire at THIS
     * hook only, or the backend's `(chw_id, behavioural_gap_id)` count double-counts.
     */
    fun onReferralSubmitted(
        encounterId: String,
        patientId: String,
        referralData: Map<String, Any> = emptyMap(),
    ) = handleReferralSubmitted(encounterId, patientId, referralData)

    /**
     * Call when the CHW finishes a patient visit. Backfills `patient_visit_id` on
     * this visit's still-pending coaching_event rows (written by
     * [onAssessmentSubmitted] before the encounterId was final), emits `session_end`,
     * and triggers a sync push.
     *
     * @param encounterId SPICE encounter id; blank values are skipped.
     */
    fun onVisitCompleted(encounterId: String) {
        val chwId = currentCHWId ?: return
        sdkScope.launch {
            val recorder = newSdkHookRecorder(chwId)
            visitCompletedHandler.handle(
                chwId = chwId,
                encounterId = encounterId,
                recorder = recorder,
                flush = { flushTelemetryNow() },
            )
        }
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
        morningCoordinator.onMorningOpen(chwId)
    }

    /**
     * Force-refresh the morning refreshers: flush pending telemetry, re-pull
     * `GET /morning/cards`, re-run the on-device generator, recompute the store.
     * Used by host pull-to-refresh and by quiz completion. No-op until a CHW id is
     * known; safe to call repeatedly (offline falls back to the on-device generator).
     */
    fun refreshRefreshers() {
        val chwId = currentCHWId ?: return
        morningCoordinator.refreshAfterQuiz(chwId)
    }

    /**
     * Fire-and-forget morning refilter on the SDK scope — for UI call-sites (e.g. a
     * sheet's dismiss) whose lifecycle ends before the refilter completes, so the
     * work survives the surface (an ad-hoc `MainScope()` per call leaked).
     */
    fun refilterMorningModulesAsync(chwId: String) = morningCoordinator.refilterAsync(chwId)

    internal suspend fun refilterMorningModules(chwId: String) = morningCoordinator.refilter(chwId)

    /** Call when SPICE surfaces a risk flag for the active patient. */
    fun onRiskFlagObserved(riskLevel: String, patientId: String? = null) {
        val chwId = currentCHWId ?: return
        val payload = mutableMapOf("risk_level" to riskLevel)
        if (patientId != null) payload["patient_id"] = patientId
        evaluateWorkflowSignal(chwId, "risk_flag_observed", payload)

        // Also emit the wire-level `risk_flag_observed` row for the backend's
        // clinical_observed feed (mid-visit escalation fires through here too).
        sdkScope.launch {
            try {
                val recorder = newSdkHookRecorder(chwId)
                val patientIdHash = patientId?.let { PatientIdHasher.hash(it) }
                recorder.recordRiskFlagObserved(
                    riskLevel = riskLevel,
                    patientIdHash = patientIdHash,
                    networkState = if (isNetworkAvailable()) "online" else "offline",
                )
            } catch (e: Exception) {
                Log.w(TAG, "onRiskFlagObserved event emission failed: ${e.message}")
            }
            // Time-sensitive — push immediately (offline-safe; WorkManager queues).
            flushTelemetryNow()
        }
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

    internal fun evaluateWorkflowSignal(
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

    internal fun isNetworkAvailable(): Boolean = networkMonitor.isAvailable()

    /**
     * Full teardown of this instance: unregister connectivity, cancel sync workers,
     * cancel [sdkScope] (stopping the init collectors + eager `stateIn` pipelines),
     * then release every service holding threads / native memory (telemetry, model
     * managers, translator, voice engines, HTTP client). Only actually-created
     * services are released (`Lazy` handles are checked, so shutdown never
     * force-initialises one). Called by [Builder.build] on the replaced instance so
     * re-init can't leak the old graph; idempotent, and the instance is inert after.
     */
    fun shutdown() {
        networkMonitor.unregister()
        syncCoordinator.cancelAll()

        // Stop the init-block collectors and every eager stateIn on this instance
        // BEFORE releasing the services they touch.
        sdkScope.cancel()

        if (telemetryLazy.isInitialized()) {
            runCatching { telemetryLazy.value.shutdown() }
                .onFailure { Log.w(TAG, "telemetry.shutdown threw: ${it.message}") }
        }
        if (modelManagerLazy.isInitialized()) {
            runCatching { modelManagerLazy.value.close() }
                .onFailure { Log.w(TAG, "modelManager.close threw: ${it.message}") }
        }
        if (sttModelManagerLazy.isInitialized()) {
            runCatching { sttModelManagerLazy.value.close() }
                .onFailure { Log.w(TAG, "sttModelManager.close threw: ${it.message}") }
        }
        if (translatorLazy.isInitialized()) {
            runCatching { translatorLazy.value.close() }
                .onFailure { Log.w(TAG, "translator.close threw: ${it.message}") }
        }
        runCatching { voiceInputController?.release() }
            .onFailure { Log.w(TAG, "voiceInputController.release threw: ${it.message}") }
        voiceInputController = null
        if (okHttpClientLazy.isInitialized()) {
            runCatching {
                okHttpClientLazy.value.dispatcher.executorService.shutdown()
                okHttpClientLazy.value.connectionPool.evictAll()
            }.onFailure { Log.w(TAG, "okHttpClient teardown threw: ${it.message}") }
        }

        Log.d(TAG, "SDK shutdown — scope cancelled, services released, sync cancelled.")
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
            // triggerNow() chains outbound → inbound, so pending telemetry ships
            // before the catalogue refresh.
            syncCoordinator.triggerNow()
        }
    }

    /**
     * Force an immediate outbound telemetry flush (bypasses the 15-min periodic
     * interval) — used after a quiz answer / module completion. No-op when
     * [MicroCoachingConfig.backendUrl] is blank.
     */
    fun flushTelemetryNow() {
        if (config.backendUrl.isBlank()) {
            Log.d(TAG, "flushTelemetryNow skipped — backendUrl is blank")
            return
        }
        Log.i(TAG, "flushTelemetryNow — enqueuing outbound sync.")
        syncCoordinator.triggerOutboundNow()
    }

    /**
     * Remove the cached PO dashboard snapshot (MED-I516 AC6). The host (spice) calls
     * this on user logout. Fire-and-forget on [sdkScope]; safe to call when empty.
     * The dashboard cache is also chwId-guarded, so cross-user leakage cannot occur
     * even before this runs.
     */
    fun clearDashboardCache() {
        sdkScope.launch {
            runCatching {
                MicroCoachingDatabase.getInstance(config.context).dashboardCacheDao().clear()
            }.onFailure { Log.w(TAG, "clearDashboardCache threw: ${it.message}") }
        }
    }

    /**
     * Force an immediate **full-catalogue** inbound resync (host pull-to-refresh):
     * clears the modules watermark so the next `/sync/modules` pull fetches the full
     * catalogue + assignment set — this is what surfaces a newly-assigned but
     * unchanged module before the daily reconcile. Chains outbound → inbound so
     * pending telemetry still ships. No-op when [MicroCoachingConfig.backendUrl] is blank.
     */
    fun triggerFullInboundSync() {
        if (config.backendUrl.isBlank()) {
            Log.d(TAG, "triggerFullInboundSync skipped — backendUrl is blank")
            return
        }
        Log.i(TAG, "triggerFullInboundSync — clearing modules watermark and enqueuing full inbound sync.")
        SyncPrefs(config.context).modulesWatermark = null
        syncCoordinator.triggerNow()
    }

    // ── CHW Context Storage ───────────────────────────────────────────────────

    private val chwPrefs by lazy {
        config.context.getSharedPreferences(com.medtroniclabs.microcoaching.util.PrefsNames.CHW, Context.MODE_PRIVATE)
    }

    // ── CHW work-context / visits / snapshot — owned by [ChwContextStore] ──────
    private val chwContextStore = ChwContextStore(sdkScope, { chwPrefs })

    /** Persist the host's anonymised CHW work context (recent screenings). See [ChwContextStore]. */
    fun onCHWContextUpdated(chwWorkContext: CHWWorkContext) = chwContextStore.updateContext(chwWorkContext)

    /** Returns the last CHW work context pushed via [onCHWContextUpdated], or null if none stored. */
    fun loadCHWContext(): CHWWorkContext? = chwContextStore.loadContext()

    /** Persist the CHW's patient visits due today (cold-start refresher source). See [ChwContextStore]. */
    fun onTodaysVisitsUpdated(visits: List<TodaysVisit>) = chwContextStore.updateTodaysVisits(visits)

    /** Returns the visits last pushed via [onTodaysVisitsUpdated], or empty if none/undecodable. */
    fun loadTodaysVisits(): List<TodaysVisit> = chwContextStore.loadTodaysVisits()

    /** Returns the snapshot from the most recent assessment, or null if none stored. */
    fun loadLastPatientSnapshot(): PatientSnapshot? = chwContextStore.loadLastPatientSnapshot()

    // ── Builder ───────────────────────────────────────────────────────────────

    class Builder(private val context: Context) {
        private var language: Language = Language.BANGLA
        private var tenantId: String = ""
        private var persona: CoachingPersona = CoachingPersona.SK
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
        private var wifiOnlyModelDownload: Boolean = false
        private var modelProviders: List<ModelProvider> = ModelProvider.DEFAULT_ORDER
        private var huggingFaceToken: String = ""
        private var huggingFaceModelUrl: String = ""
        private var selectedModelId: String = ModelCatalog.DEFAULT_ID
        // Keep in sync with MicroCoachingConfig defaults — see the KDoc there for
        // why maxInferenceTokens is a TOTAL (input+output) window, not an output cap.
        private var maxInferenceTokens: Int = 1536
        private var inferenceTemperature: Float = 0.3f
        private var chatScopeStrictness: ChatScopeStrictness = ChatScopeStrictness.ExtendedClinical
        private var chatTuning: ChatTuning = ChatTuning()
        private var refresherTuning: RefresherTuning = RefresherTuning()
        private var forceLowEndMode: Boolean? = null
        private var enableChat: Boolean = true
        private var enableIncompleteModuleReminder: Boolean = true
        private var enableRetrievalHintFixtureOverlay: Boolean = false
        private var enableVoice: Boolean = false
        private var enableLearnModule: Boolean = false
        private var enableApplyModule: Boolean = false
        private var enableMeasureModule: Boolean = false
        private var dataCallback: MicroCoachingDataCallback? = null
        private var uiTheme: CoachingUiTheme = CoachingUiTheme.SYSTEM
        private var forcedMode: CoachingMode? = null
        // Keep in sync with MicroCoachingConfig.minFreeStorageBytes default (512 MB).
        private var minFreeStorageBytes: Long = 512L * 1024 * 1024
        private var voiceInputController: VoiceInputController? = null
        private var offlineSttEngineFactory:
            ((android.content.Context, java.io.File) -> OfflineSttEngine)? = null

        fun language(l: Language) = apply { language = l }
        fun tenantId(id: String) = apply { tenantId = id }
        /** Active coaching persona (SK / PO). Resolve from the user's role; default SK. */
        fun persona(p: CoachingPersona) = apply { persona = p }
        fun backendUrl(url: String) = apply { backendUrl = url }
        /** Pass SPICE's stored login token here. SDK forwards it verbatim as `Authorization`. */
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
        /** Override provider priority or disable a provider entirely. Default: Backend → HuggingFace → Kaggle. */
        fun modelProviders(providers: List<ModelProvider>) = apply { modelProviders = providers }
        /** HuggingFace Hub token for gated model downloads. Set HUGGING_FACE_TOKEN in local.properties for dev. */
        fun huggingFaceToken(token: String) = apply { huggingFaceToken = token }
        /**
         * Optional override of the model download URL. Leave unset to use the
         * [selectedModel]'s catalog URL. Set only to point at a file not in the
         * allowlist — the on-disk filename still comes from the selected variant.
         */
        fun huggingFaceModelUrl(url: String) = apply { huggingFaceModelUrl = url }
        /**
         * Pick which on-device model to download/load — an `id` from
         * [com.medtroniclabs.microcoaching.ai.model.ModelCatalog.ALLOWLIST]
         * (e.g. `"gemma3-270m-it-q8-task"`, `"gemma3-1b-it-int4-task"`).
         * Default: [com.medtroniclabs.microcoaching.ai.model.ModelCatalog.DEFAULT_ID].
         * An unknown id falls back to the default; a non-MediaPipe variant is
         * accepted but warns (it can't load until the LiteRT-LM runtime is re-added).
         */
        fun selectedModel(id: String) = apply {
            val variant = ModelCatalog.byId(id)
            when {
                variant == null ->
                    Log.w(TAG, "selectedModel('$id') is not in the allowlist — using default '${ModelCatalog.DEFAULT_ID}'")
                !ModelCatalog.isRunnable(variant) ->
                    Log.w(TAG, "selectedModel('$id') runtime=${variant.runtime} is not bundled — it won't load until the LiteRT-LM runtime is re-added")
            }
            selectedModelId = variant?.id ?: ModelCatalog.DEFAULT_ID
        }
        fun maxInferenceTokens(tokens: Int) = apply { maxInferenceTokens = tokens }
        fun inferenceTemperature(temp: Float) = apply { inferenceTemperature = temp }
        /**
         * Pick the chat refusal strictness. Default is [ChatScopeStrictness.ExtendedClinical] —
         * the on-device LLM is consulted for retrieval-miss questions instead of a hard
         * keyword-classifier refusal. Use [ChatScopeStrictness.Strict] to preserve the
         * legacy keyword-only behaviour.
         */
        fun chatScopeStrictness(strictness: ChatScopeStrictness) = apply { chatScopeStrictness = strictness }
        /**
         * Tune the offline-chat retrieval and refusal gates (BM25 threshold,
         * groundedness floor, strong-retrieval bypass, stream cap, length cap,
         * drug/dosage guards). See [ChatTuning] for each knob. Leave unset to use
         * the SDK defaults, which favour showing the model's answer when BM25
         * retrieval is confident.
         */
        fun chatTuning(tuning: ChatTuning) = apply { chatTuning = tuning }
        /**
         * Configure the refresher sources (quiz / gap / referral / visit toggles). A
         * refresher presents its full to-reinforce set; the legacy quiz-subset sampling
         * fields are deprecated no-ops. See [RefresherTuning]. Leave unset for defaults.
         */
        fun refresherTuning(tuning: RefresherTuning) = apply { refresherTuning = tuning }
        /**
         * Override automatic low-end-device detection. Default `null` lets the
         * SDK probe total RAM (< 3 GB → low-end, retrieval-only chat). Pass
         * `true` to force the retrieval-only path on capable hardware (QA), or
         * `false` to force the LLM path on a low-RAM device (only safe when an
         * external runtime guarantees the Gemma model can load).
         */
        fun forceLowEndMode(force: Boolean?) = apply { forceLowEndMode = force }
        fun enableChat(enabled: Boolean) = apply { enableChat = enabled }
        /**
         * Toggle the incomplete-assigned-modules reminder popup (MED-1529 Req 2).
         * Default true. Pass false as a host kill switch.
         */
        fun enableIncompleteModuleReminder(enabled: Boolean) =
            apply { enableIncompleteModuleReminder = enabled }
        /**
         * Merge hand-authored per-card hints from `assets/retrieval/overlays/`
         * before building the chat index. Benchmark / QA only — off in production.
         */
        fun enableRetrievalHintFixtureOverlay(enabled: Boolean) =
            apply { enableRetrievalHintFixtureOverlay = enabled }
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

        /**
         * Supply a custom speech-to-text controller for the chat mic.
         *
         * When unset, the SDK auto-installs
         * [com.medtroniclabs.microcoaching.ai.voice.AndroidSpeechRecognizerEngine]
         * if [enableVoice] is `true`. Use this hook when you need a different
         * engine (e.g. an offline Bengali sherpa-onnx impl).
         */
        fun voiceInputController(
            controller: VoiceInputController,
        ) = apply { voiceInputController = controller }

        /**
         * Supply a factory that builds the offline Bengali STT engine when needed.
         *
         * The SDK's default voice controller routes Bengali through the platform
         * `SpeechRecognizer` when online, and falls back to this engine only
         * when the device is offline AND the sherpa-onnx model files are on
         * disk. The factory is called lazily — no sherpa-onnx classes are
         * touched until the first offline-Bengali dictation.
         *
         * Hosts that include the optional `:sdk-android-sherpa` module just
         * pass [com.medtroniclabs.microcoaching.sherpa.SherpaOnnxStt.factory].
         * Hosts that skip the module get the platform engine only — same
         * online behaviour for both languages, no offline Bengali.
         */
        fun offlineSttEngineFactory(
            factory: (android.content.Context, java.io.File) -> OfflineSttEngine,
        ) = apply { offlineSttEngineFactory = factory }

        /**
         * Minimum free device storage (bytes) to keep available. Downloads that
         * would leave less than this are refused with an insufficient-storage
         * error instead of filling the disk. Default 512 MB; e.g. pass
         * `1_073_741_824L` for a 1 GB floor. Negative values are clamped to 0.
         */
        fun minFreeStorageBytes(bytes: Long) = apply {
            minFreeStorageBytes = bytes.coerceAtLeast(0L)
        }

        fun build(): MicroCoachingSDK {
            val config = MicroCoachingConfig(
                context = context.applicationContext,
                language = language,
                tenantId = tenantId,
                persona = persona,
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
                selectedModelId = selectedModelId,
                maxInferenceTokens = maxInferenceTokens,
                inferenceTemperature = inferenceTemperature,
                chatScopeStrictness = chatScopeStrictness,
                chatTuning = chatTuning,
                refresherTuning = refresherTuning,
                forceLowEndMode = forceLowEndMode,
                enableChat = enableChat,
                enableIncompleteModuleReminder = enableIncompleteModuleReminder,
                enableRetrievalHintFixtureOverlay = enableRetrievalHintFixtureOverlay,
                enableVoice = enableVoice,
                enableLearnModule = enableLearnModule,
                enableApplyModule = enableApplyModule,
                enableMeasureModule = enableMeasureModule,
                dataCallback = dataCallback,
                uiTheme = uiTheme,
                forcedMode = forcedMode,
                minFreeStorageBytes = minFreeStorageBytes,
            )
            // Tear down the outgoing instance BEFORE constructing the new one: the
            // new init{} schedules periodic sync under the same unique work names
            // the old shutdown() cancels, so the reverse order would cancel the
            // fresh sync on every re-init.
            val sdk = synchronized(Companion) {
                instance?.shutdown()
                MicroCoachingSDK(config).also { instance = it }
            }

            // Wire the chat mic: host-supplied controller wins, else
            // ChatVoiceInputController (platform SpeechRecognizer for EN / online BN,
            // an optional host-supplied sherpa factory for offline BN).
            sdk.voiceInputController = voiceInputController
                ?: if (config.enableVoice) {
                    val androidEngine =
                        AndroidSpeechRecognizerEngine(
                            config.context,
                        )
                    val offlineFactory = offlineSttEngineFactory?.let { hostFactory ->
                        { modelDir: java.io.File -> hostFactory(config.context, modelDir) }
                    }
                    ChatVoiceInputController(
                        appContext = config.context,
                        androidEngine = androidEngine,
                        sttModelManager = sdk.sttModelManager,
                        offlineEngineFactory = offlineFactory,
                    )
                } else null

            Log.i(TAG, "SDK initialized — backendUrl=${config.backendUrl.isNotBlank()} " +
                "modelPath=${config.modelPath.isNotBlank()} " +
                "forcedMode=${config.forcedMode ?: "auto"}")

            // Kick off the model download if configured for init — skipped on
            // low-end devices (they never use the AI model).
            if (config.modelDownloadStrategy == ModelDownloadStrategy.ON_SDK_INIT &&
                !sdk.isLowEndDevice
            ) {
                sdk.modelManager.scheduleDownloadIfNeeded()
            }

            return sdk
        }
    }

    companion object {
        private const val TAG = "MicroCoachingSDK"

        /**
         * Min gap between morning-refilter runs. With `conflate()`, coalesces an
         * invalidation burst (quiz streak, bulk markSynced) into one recompute.
         */
        private const val RECOMPUTE_THROTTLE_MS = 500L

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
