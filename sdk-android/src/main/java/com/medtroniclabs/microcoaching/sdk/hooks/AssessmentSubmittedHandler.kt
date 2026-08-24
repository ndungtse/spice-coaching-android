package com.medtroniclabs.microcoaching.sdk.hooks

import com.medtroniclabs.microcoaching.MicroCoachingSDK
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
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.ActionGapLink
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.GapStateConfig
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.MorningSourcesConfig
import com.medtroniclabs.microcoaching.domain.gaps.ondevice.OnDeviceMorningGenerator
import com.medtroniclabs.microcoaching.domain.morning.MorningModuleResolver
import com.medtroniclabs.microcoaching.domain.triggers.TriggerEvaluator
import com.medtroniclabs.microcoaching.progress.buildModuleCompletion
import com.medtroniclabs.microcoaching.sdk.context.ChwContextStore
import com.medtroniclabs.microcoaching.sdk.morning.MorningSurfaceCoordinator
import com.medtroniclabs.microcoaching.sdk.morning.PersonaPolicy
import com.medtroniclabs.microcoaching.sdk.morning.SkippedRefresherStore
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
import kotlinx.coroutines.flow.first
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
import com.medtroniclabs.microcoaching.ai.model.ModelState
import com.medtroniclabs.microcoaching.ai.translation.TranslationModelState
import com.medtroniclabs.microcoaching.ai.voice.OfflineSttEngine
import com.medtroniclabs.microcoaching.ai.voice.AndroidSpeechRecognizerEngine
import com.medtroniclabs.microcoaching.ai.voice.ChatVoiceInputController
import com.medtroniclabs.microcoaching.domain.system.DeviceCapability
import com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore
import com.medtroniclabs.microcoaching.domain.config.LearningPoints
import com.medtroniclabs.microcoaching.ui.learn.QuizRetryGate

// onAssessmentSubmitted body. The facade keeps the public method as a thin delegate.
private const val TAG = "MicroCoachingSDK"

internal fun MicroCoachingSDK.handleAssessmentSubmitted(
    encounterId: String,
    patientId: String,
    assessmentData: Map<String, Any>,
) {
    val chwId = currentCHWId ?: return
    // The key set alone confirms from logcat whether SPICE actually wrote
    // `referralFacilityType` / `referred_site_id` etc. Values are omitted —
    // they may carry de-identified clinical data that doesn't belong there.
    Log.i(
        TAG,
        "onAssessmentSubmitted — encounterId='${encounterId}' " +
            "assessmentKeys=${assessmentData.keys}",
    )
    val payload = assessmentData.mapValues { it.value.toString() } +
        ("encounter_id" to encounterId) +
        ("patient_id" to patientId)
    evaluateWorkflowSignal(chwId, "assessment_submitted", payload)

    // No telemetry is written here. Referral correctness
    // (`spice_action_observed`) is owned entirely by [onReferralSubmitted],
    // which sees the destination facility the CHW actually picked — at
    // assessment-save that pick does not exist yet, so any verdict written
    // here would be fabricated.
}
