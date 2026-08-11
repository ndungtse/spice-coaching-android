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

// onAssessmentSubmitted body — extracted verbatim from MicroCoachingSDK as an extension
// (behaviour-preserving). The facade keeps the public method as a thin delegate.
private const val TAG = "MicroCoachingSDK"

internal fun MicroCoachingSDK.handleAssessmentSubmitted(
    encounterId: String,
    patientId: String,
    assessmentData: Map<String, Any>,
) {
    val chwId = currentCHWId ?: return
    // Test-loop diagnostic: the key set lands here so we can grep
    // logcat in docs/gaps/GAPS_TEST.md and confirm SPICE actually
    // wrote `referralFacilityType` / `referred_site_id` etc. Values
    // are intentionally omitted — they may contain de-identified
    // clinical data that doesn't belong in logcat.
    Log.i(
        TAG,
        "onAssessmentSubmitted — encounterId='${encounterId}' " +
            "assessmentKeys=${assessmentData.keys}",
    )
    val payload = assessmentData.mapValues { it.value.toString() } +
        ("encounter_id" to encounterId) +
        ("patient_id" to patientId)
    evaluateWorkflowSignal(chwId, "assessment_submitted", payload)

    // UC-2 / UC-3: emit the assessment-level telemetry the backend expects
    // (conditional `risk_flag_observed`, stub `card_shown`). Referral
    // correctness (`spice_action_observed`) is deliberately NOT emitted
    // here — at assessment-save the CHW has not yet picked a destination
    // facility, so any referral verdict would be fabricated. That row is
    // owned entirely by [onReferralSubmitted], which sees the actual pick.
    // The render path is not yet built — `card_shown` is a placeholder
    // until the post-assessment counselling card surface exists.
    sdkScope.launch {
        try {
            val recorder = newSdkHookRecorder(chwId)
            val patientIdHash = PatientIdHasher.hash(patientId)
            val visitId = encounterId.takeIf { it.isNotBlank() }
            val villageId = assessmentData["village_id"] as? String
            val upazilaId = assessmentData["upazila_id"] as? String
            val patientTrackId = assessmentData["patient_track_id"] as? String
            val gapId = assessmentData["behavioural_gap_id"] as? String
            val riskLevel = (assessmentData["risk_level"] as? String).orEmpty()
            val networkState = if (isNetworkAvailable()) "online" else "offline"

            // Referral-compliance gaps are evaluated only at
            // [onReferralSubmitted] (the moment the `actual.*` pick exists);
            // the assessment-submit payload is flat, so no gap can fire here
            // and no `spice_action_observed` row is written. This keeps
            // assessment-save free of any (necessarily fabricated) referral
            // verdict and avoids double-counting a gap across both hooks.

            if (riskLevel.equals("HIGH", ignoreCase = true) ||
                riskLevel.equals("EMERGENCY", ignoreCase = true)
            ) {
                recorder.recordRiskFlagObserved(
                    riskLevel = riskLevel,
                    patientIdHash = patientIdHash,
                    patientVisitId = visitId,
                    patientTrackId = patientTrackId,
                    villageId = villageId,
                    upazilaId = upazilaId,
                    behaviouralGapId = gapId,
                    networkState = networkState,
                )
            }

            // STUB: post-assessment coaching surface "fires" — replace
            // with a render-path-driven emission once the visit card UI
            // is built. Marked `inferenceMode = "cached"` because that's
            // the only resolution path in scope this week.
            recorder.recordCardShown(
                cardType = "action",
                triggerType = "workflow_event",
                inferenceMode = "cached",
                patientIdHash = patientIdHash,
                patientVisitId = visitId,
                patientTrackId = patientTrackId,
                villageId = villageId,
                upazilaId = upazilaId,
                behaviouralGapId = gapId,
                networkState = networkState,
            )
        } catch (e: Exception) {
            Log.w(TAG, "onAssessmentSubmitted event emission failed: ${e.message}")
        }

        // Push the freshly-written `clinical_observed` rows immediately
        // instead of waiting for the 15-min WorkManager tick. Assessment
        // submit is a meaningful milestone (the supervisor dashboard's
        // "correct referral" tile depends on these), and a CHW with
        // network connectivity should see their action surface server-
        // side within seconds.
        //
        // Offline-safe: `flushTelemetryNow` enqueues a WorkManager job
        // with a `NetworkType.CONNECTED` constraint, so an offline
        // device queues the work and it fires the moment connectivity
        // is restored. No need to gate on `isNetworkAvailable()` here.
        flushTelemetryNow()
    }
}
