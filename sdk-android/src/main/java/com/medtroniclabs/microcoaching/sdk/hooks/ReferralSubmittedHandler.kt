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

// onReferralSubmitted body — extracted verbatim from MicroCoachingSDK as an extension
// (behaviour-preserving). The facade keeps the public method as a thin delegate.
private const val TAG = "MicroCoachingSDK"

internal fun MicroCoachingSDK.handleReferralSubmitted(
    encounterId: String,
    patientId: String,
    referralData: Map<String, Any>,
) {
    val chwId = currentCHWId ?: return
    // Log the tier comparison inputs *in words* (not just correct=true/false)
    // so referral_location_* (Upazila vs Community Clinic) can be validated
    // from logcat. Facility tier is not PII.
    val recommendedTier = (referralData["recommended"] as? Map<*, *>)?.get("referralFacilityType")
    val actualTier = (referralData["actual"] as? Map<*, *>)?.get("destinationTier")
    Log.i(
        TAG,
        "onReferralSubmitted — encounterId='${encounterId}' " +
            "recommendedTier='${recommendedTier}' actualTier='${actualTier}' " +
            "referralKeys=${referralData.keys}",
    )
    if (!config.enableGapDetection) {
        Log.d(TAG, "onReferralSubmitted: gap detection disabled — skipping")
        return
    }

    sdkScope.launch {
        try {
            val recorder = newSdkHookRecorder(chwId)
            val patientIdHash = PatientIdHasher.hash(patientId)
            val visitId = encounterId.takeIf { it.isNotBlank() }
            val villageId = referralData["village_id"] as? String
            val upazilaId = referralData["upazila_id"] as? String
            val patientTrackId = referralData["patient_track_id"] as? String
            val networkState = if (isNetworkAvailable()) "online" else "offline"

            val spiceEventCode = (referralData["spice_event_code"] as? String)
                ?: "referral_submitted"
            val assessmentType = referralData["assessment_type"] as? String
            val firedGaps =
                gapRuleDispatcher.evaluate(referralData, spiceEventCode, assessmentType)

            for (result in firedGaps) {
                // Every row here is a *fired* compliance gap, i.e. the CHW's
                // referral diverged from the recommendation → incorrect. The
                // legacy three-axis `correctReferral*` is reason-based and
                // tier-blind, so it would contradict `outcome=incorrect` (it
                // returns true when only the facility tier is wrong). Report
                // false uniformly; the precise dimension is in `evidence`
                // (e.g. tier="location"). The generic "correct" baseline is
                // still emitted by onAssessmentSubmitted at save time.
                recorder.recordSpiceActionObserved(
                    patientIdHash = patientIdHash,
                    patientVisitId = visitId,
                    patientTrackId = patientTrackId,
                    villageId = villageId,
                    upazilaId = upazilaId,
                    behaviouralGapId = result.gapId,
                    correctReferral = false,
                    correctReferralLocation = false,
                    correctReferralType = false,
                    networkState = networkState,
                    outcome = result.outcome,
                    ruleType = result.ruleType,
                    evidenceJson = EvidenceBuilder.toJsonString(result.evidence),
                )
            }
            if (firedGaps.isEmpty()) {
                // No gap fired. Before claiming a CORRECT referral, confirm the
                // tier comparison could actually run. If the CHW referred and a
                // recommended tier exists but the ACTUAL destination tier is
                // missing/blank (e.g. the picked facility's tier isn't populated
                // locally — the known facility-tier data gap), the
                // referral_location_* rules log "no signal" and don't fire. That
                // is NOT the same as "correct": recording correct here turns
                // missing data into a false positive (the wrong-facility-referral
                // bug). Skip the positive emission and warn instead — never assert
                // a correct referral the SDK couldn't actually verify.
                val actualMap = referralData["actual"] as? Map<*, *>
                val didRefer = actualMap?.get("didRefer") == true
                val actualTierPresent =
                    (actualMap?.get("destinationTier") as? String)?.isNotBlank() == true
                val recommendedTierPresent =
                    ((referralData["recommended"] as? Map<*, *>)?.get("referralFacilityType") as? String)
                        ?.isNotBlank() == true

                if (didRefer && recommendedTierPresent && !actualTierPresent) {
                    Log.w(
                        TAG,
                        "onReferralSubmitted: referral made and a recommended tier exists, but " +
                            "actual.destinationTier is missing/blank — NOT recording a correct " +
                            "referral (tier comparison could not run; facility tier likely " +
                            "unpopulated). recommendedTier='$recommendedTier' actualTier='$actualTier'",
                    )
                } else {
                    // Genuinely no divergence to report → emit the single positive
                    // `spice_action_observed` row (gap_id = null) so the supervisor
                    // "correct referral" tile sees a correct data point. This is the
                    // only place a `correctReferral = true` row originates now that
                    // the assessment hook no longer fabricates one.
                    recorder.recordSpiceActionObserved(
                        patientIdHash = patientIdHash,
                        patientVisitId = visitId,
                        patientTrackId = patientTrackId,
                        villageId = villageId,
                        upazilaId = upazilaId,
                        behaviouralGapId = null,
                        correctReferral = true,
                        correctReferralLocation = true,
                        correctReferralType = true,
                        networkState = networkState,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onReferralSubmitted event emission failed: ${e.message}")
        }
        flushTelemetryNow()

        // The referral just changed this CHW's compliance-gap state — a wrong
        // referral opens a new action-gap refresher (e.g. "Maternal & Neonatal
        // Referral Process"), a correct one can resolve a prior one. Re-run the
        // morning resolution NOW so the on-device generator folds in the
        // freshly-recorded `spice_action_observed` row and the refresher list /
        // featured card update immediately — instead of waiting for the next
        // onHomeScreenShown / sync tick.
        morningModuleResolver.refresh(chwId)
        coachingModuleStore.invalidate()
    }
}
