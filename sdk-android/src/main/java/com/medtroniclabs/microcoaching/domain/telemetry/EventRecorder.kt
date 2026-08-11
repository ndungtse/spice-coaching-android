package com.medtroniclabs.microcoaching.domain.telemetry

import android.util.Log
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

private const val TAG = "EventRecorder"

/**
 * Lenient Json instance used to parse evidence payloads from gap evaluators
 * before merging them into the event's `payload_json`. Lenient because the
 * envelope is constructed in-process by SDK code, not received over the wire.
 */
private val EVIDENCE_JSON = com.medtroniclabs.microcoaching.util.LenientJson

/**
 * Map a backend-canonical event_type to its event_family bucket. Values match
 * the deployed `CoachingEventType` / `DigitalEventType` / `EventFamily` enums
 * exactly so the backend ingestion path can deserialise into typed enums for
 * aggregation rather than falling back to `string`.
 */
fun eventFamilyFor(eventType: String): String = when (eventType) {
    // Coaching surface events. `module_quiz_attempted` lives here per the v3
    // `module_requested` (Events-Modelling 1.5) is the CHW's request for a
    // module to be added to their quota — recorded, then synced like any event.
    // `video_progress_updated` (see docs/_events/video.md) reports CHW watch
    // progress for an assigned training video — recorded, then synced like any event.
    // `module_quiz_viewed` (Events-Modelling 1.7) is the "CHW opened a quiz"
    // event — the canonical name for what was formerly `quiz_started`.
    // `module_card_viewed` moved here from `learning` per 1.7, which lists it
    // under the `coaching` family.
    // `document_viewed` fires when a knowledge source document is opened. The
    // backend's rollup keys off this exact string — renaming it empties the
    // document-usage dashboard silently.
    "card_shown", "card_skipped", "card_accepted", "counselling_used",
    "audio_played", "module_quiz_viewed", "module_quiz_attempted", "module_requested",
    "module_card_viewed", "video_progress_updated", "document_viewed" -> "coaching"
    "module_delivered", "module_completed" -> "learning"
    "risk_flag_observed", "spice_action_observed",
    "equipment_anomaly_observed" -> "clinical_observed"
    "sync_attempt", "sync_started", "sync_completed",
    "form_submit", "login_attempt", "digital_help_used",
    "chat_feedback_positive", "chat_feedback_negative" -> "digital"
    else -> "system"  // session_start, session_end, llm_inference, unknown
}

/**
 * Maps a [com.medtroniclabs.microcoaching.ui.learn.LearnModule.source] value to
 * the wire `trigger_type` per the v1.1 Events-Modelling spec:
 *  - `"gap"` (gap-driven refresher) → `"gap"`
 *  - `"fallback"` (server fallback recommendation) → `"fallback"`
 *  - null (regular training-row quiz, no morning surface) → `"workflow_event"`
 *
 * Free function (not a method) so both [EventRecorder] callers and the
 * `CoachingModuleStore` can resolve the trigger type without an instance.
 */
fun triggerTypeFor(source: String?): String = when (source) {
    "gap" -> "gap"
    "fallback" -> "fallback"
    else -> "workflow_event"
}

/**
 * Append-only coaching event recorder. The single write path for all CHW interaction events.
 *
 * Replaces [TelemetryManager] for coaching-domain events. Writes directly to Room;
 * the OutboundSyncWorker batches pending rows to POST /telemetry/events.
 *
 * One instance per coaching session — constructed with the session ID and CHW ID
 * that remain constant for the session's lifetime.
 *
 * No batching, no in-memory buffering — each call is an immediate DB insert on the
 * caller's coroutine. Callers are responsible for calling from an IO dispatcher.
 */
class EventRecorder(
    private val dao: CoachingEventDao,
    val sessionId: String,
    private val chwId: String,
    private val sdkVersion: String = BuildConfig.SDK_VERSION,
    /**
     * Optional host-app version string. When non-null it is written to the
     * `sdk_version` column instead of [sdkVersion] — preserving the exact value
     * `LearnViewModel.recordEvent` historically wrote (the SPICE host
     * `versionName`, not the SDK's BuildConfig). Null for every other recorder,
     * which keep emitting [BuildConfig.SDK_VERSION].
     */
    private val appVersionName: String? = null,
) {

    suspend fun recordSessionStart() {
        dao.insert(build(eventType = "session_start"))
        Log.d(TAG, "[$sessionId] session_start saved — chw=$chwId")
    }

    suspend fun recordSessionEnd() {
        dao.insert(build(eventType = "session_end"))
        Log.d(TAG, "[$sessionId] session_end saved — chw=$chwId")
    }

    suspend fun recordCardViewed(
        moduleFamilyId: String,
        clinicalDomain: String,
        cardType: String,
        triggerType: String,
        inferenceMode: String,
        moduleId: String? = null,
        moduleVersion: Int? = null,
        cardFamilyId: String? = null,
    ) {
        // Backend canonical for v3 module-sourced cards.
        dao.insert(
            build(
                eventType = "module_card_viewed",
                clinicalDomain = clinicalDomain,
                cardType = cardType,
                triggerType = triggerType,
                inferenceMode = inferenceMode,
                moduleFamilyId = moduleFamilyId,
                moduleId = moduleId,
                moduleVersion = moduleVersion,
                cardFamilyId = cardFamilyId,
            )
        )
        Log.d(TAG, "[$sessionId] module_card_viewed saved — module=$moduleFamilyId domain=$clinicalDomain mode=$inferenceMode card=$cardFamilyId")
    }

    suspend fun recordCardDismissed(
        moduleFamilyId: String,
        clinicalDomain: String,
        cardType: String,
        triggerType: String? = null,
        patientIdHash: String? = null,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
        behaviouralGapId: String? = null,
        networkState: String? = null,
    ) {
        // Backend uses `card_skipped` as the canonical dismissed-from-surface event.
        dao.insert(
            build(
                eventType = "card_skipped",
                moduleFamilyId = moduleFamilyId,
                clinicalDomain = clinicalDomain,
                cardType = cardType,
                triggerType = triggerType,
                outcome = "skip",
                networkState = networkState,
                behaviouralGapId = behaviouralGapId,
                patientIdHash = patientIdHash,
                patientVisitId = patientVisitId,
                patientTrackId = patientTrackId,
                villageId = villageId,
                upazilaId = upazilaId,
            )
        )
        Log.d(TAG, "[$sessionId] card_skipped saved — module=$moduleFamilyId")
    }

    suspend fun recordCardAccepted(
        moduleFamilyId: String,
        clinicalDomain: String,
        cardType: String,
        inferenceMode: String,
        triggerType: String? = null,
        patientIdHash: String? = null,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
        behaviouralGapId: String? = null,
        networkState: String? = null,
    ) {
        dao.insert(
            build(
                eventType = "card_accepted",
                moduleFamilyId = moduleFamilyId,
                clinicalDomain = clinicalDomain,
                cardType = cardType,
                inferenceMode = inferenceMode,
                triggerType = triggerType,
                outcome = "accepted",
                networkState = networkState,
                behaviouralGapId = behaviouralGapId,
                patientIdHash = patientIdHash,
                patientVisitId = patientVisitId,
                patientTrackId = patientTrackId,
                villageId = villageId,
                upazilaId = upazilaId,
            )
        )
        Log.d(TAG, "[$sessionId] card_accepted saved — module=$moduleFamilyId")
    }

    suspend fun recordCounsellingUsed(
        moduleFamilyId: String,
        clinicalDomain: String,
        fallbackUsed: Boolean = false,
        validatorStatus: String? = null,
        triggerType: String? = null,
        patientIdHash: String? = null,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
        behaviouralGapId: String? = null,
        networkState: String? = null,
    ) {
        dao.insert(
            build(
                eventType = "counselling_used",
                moduleFamilyId = moduleFamilyId,
                clinicalDomain = clinicalDomain,
                triggerType = triggerType,
                outcome = "used",
                validatorStatus = validatorStatus,
                fallbackUsed = fallbackUsed,
                networkState = networkState,
                behaviouralGapId = behaviouralGapId,
                patientIdHash = patientIdHash,
                patientVisitId = patientVisitId,
                patientTrackId = patientTrackId,
                villageId = villageId,
                upazilaId = upazilaId,
            )
        )
        Log.d(TAG, "[$sessionId] counselling_used saved — module=$moduleFamilyId fallback=$fallbackUsed validator=$validatorStatus")
    }

    /**
     * Records one row per chat response generation, per the v1.1 Events
     * Modelling spec (`event_family: "digital"`, `event_type: "digital_help_used"`,
     * `trigger_type: "workflow_event"`).
     *
     * Emitted on every assistant turn — both happy path and refusal path. On
     * refusals, [payloadJson] carries the structured detail (refusal_outcome,
     * top retrieval score, chunk IDs) so downstream tuning can mine refusal
     * patterns without a separate event family.
     *
     * @param inferenceMode `"edge"` for on-device Gemma, `"online"` for the
     *   backend RAG path, `"cached"` for pre-authored fallback.
     * @param validatorStatus `"pass"` | `"fail"` | `null` — output of the B4
     *   validator. Null when validation didn't run (e.g. L1 scope refusal).
     * @param fallbackUsed `true` when L4 fell back to a quiz `explanation_bn`
     *   or any other pre-authored Bangla string in place of LLM output.
     * @param networkState `"online"` | `"offline"` | `"restored"` — ConnectivityManager
     *   snapshot at emission time.
     * @param payloadJson Optional structured detail for refusal rows. Pass
     *   `null` on the happy path.
     * @param moduleId Version-specific UUID of the module that grounded the
     *   served response (backend `module.id`). Events-Modelling v1.2 made this
     *   mandatory for served turns — it is the module "used for forming the
     *   response of the query" (the top cited module from the RAG response, or
     *   the dominant grounding chunk's module on the on-device path). Left
     *   `null` on refusal / inference-error / empty-response turns, where no
     *   module formed a response. `module_family_id` stays null per the spec.
     */
    suspend fun recordDigitalHelpUsed(
        inferenceMode: String,
        validatorStatus: String? = null,
        fallbackUsed: Boolean = false,
        networkState: String? = null,
        payloadJson: String? = null,
        moduleId: String? = null,
    ) {
        dao.insert(
            build(
                eventType = "digital_help_used",
                triggerType = "workflow_event",
                inferenceMode = inferenceMode,
                validatorStatus = validatorStatus,
                fallbackUsed = fallbackUsed,
                networkState = networkState,
                payloadJson = payloadJson,
                moduleId = moduleId,
            )
        )
        Log.d(TAG, "[$sessionId] digital_help_used saved — module=$moduleId mode=$inferenceMode validator=$validatorStatus fallback=$fallbackUsed network=$networkState")
    }

    /**
     * Records CHW feedback on a single chat response (thumbs up / down), per
     * Events Modelling 1.4 — `event_family: "digital"`,
     * `event_type: "chat_feedback_positive"` | `"chat_feedback_negative"`,
     * `trigger_type: "workflow_event"`.
     *
     * The rated response and the CHW's original question are mirrored into
     * `payload_json` (`{"question": …, "response": …}`, Events-Modelling 1.7) and
     * the response context (`moduleId`, `inferenceMode`, `validatorStatus`,
     * `fallbackUsed`, `networkState`) is echoed from the original
     * [recordDigitalHelpUsed] turn so analytics can segment feedback by the
     * pipeline that produced the answer without a message-store join.
     *
     * Analytics-only: the rating rides the `telemetry/events` sync and is not
     * persisted locally, so it does not survive a history reload.
     *
     * @param positive `true` for thumbs-up, `false` for thumbs-down.
     * @param responseJson The rated response **object as a JSON string** (the RAG
     *   response, or the offline-constructed equivalent), stored under
     *   `payload_json.response`.
     * @param feedbackText Optional free-text detail the CHW typed in the
     *   thumbs-down sheet, mirrored into `payload_json.feedback`. Null/blank on
     *   thumbs-up or when no detail was given.
     * @param question The CHW's original question, mirrored into
     *   `payload_json.question`. Null for history-loaded turns (no meta), where
     *   it is simply omitted.
     */
    suspend fun recordChatFeedback(
        positive: Boolean,
        responseJson: String,
        feedbackText: String? = null,
        question: String? = null,
        moduleId: String? = null,
        inferenceMode: String? = null,
        validatorStatus: String? = null,
        fallbackUsed: Boolean? = null,
        networkState: String? = null,
    ) {
        val eventType = if (positive) "chat_feedback_positive" else "chat_feedback_negative"
        dao.insert(
            build(
                eventType = eventType,
                triggerType = "workflow_event",
                inferenceMode = inferenceMode,
                validatorStatus = validatorStatus,
                fallbackUsed = fallbackUsed ?: false,
                networkState = networkState,
                payloadJson = buildChatResponsePayload(responseJson, feedbackText, question),
                moduleId = moduleId,
            )
        )
        Log.d(TAG, "[$sessionId] $eventType saved — module=$moduleId mode=$inferenceMode network=$networkState hasFeedback=${!feedbackText.isNullOrBlank()}")
    }

    suspend fun recordModuleStarted(
        moduleFamilyId: String,
        clinicalDomain: String,
    ) {
        // Backend canonical event for module surfacing is `module_delivered`.
        dao.insert(
            build(
                eventType = "module_delivered",
                moduleFamilyId = moduleFamilyId,
                clinicalDomain = clinicalDomain,
            )
        )
        Log.d(TAG, "[$sessionId] module_delivered saved — module=$moduleFamilyId")
    }

    suspend fun recordModuleCompleted(
        moduleFamilyId: String,
        clinicalDomain: String,
    ) {
        dao.insert(
            build(
                eventType = "module_completed",
                moduleFamilyId = moduleFamilyId,
                clinicalDomain = clinicalDomain,
            )
        )
        Log.d(TAG, "[$sessionId] module_completed saved — module=$moduleFamilyId")
    }

    /**
     * Distinct from [recordModuleCompleted]: emitted when the CHW finishes the
     * quiz portion of a module regardless of whether the module is fully
     * completed. Carries the score so the backend can update
     * `chw_module_completion`.
     */
    suspend fun recordQuizCompleted(
        moduleFamilyId: String,
        moduleId: String?,
        moduleVersion: Int?,
        quizScorePct: Float,
        passed: Boolean,
        behaviouralGapId: String? = null,
    ) {
        // Backend canonical event for a finished quiz attempt is
        // `module_quiz_attempted`; the outcome carries pass/fail.
        dao.insert(
            build(
                eventType = "module_quiz_attempted",
                cardType = "quiz",
                outcome = if (passed) "correct" else "wrong",
                moduleFamilyId = moduleFamilyId,
                moduleId = moduleId,
                moduleVersion = moduleVersion,
                quizScorePct = quizScorePct,
                behaviouralGapId = behaviouralGapId,
            )
        )
        Log.d(TAG, "[$sessionId] module_quiz_attempted saved — module=$moduleFamilyId score=$quizScorePct passed=$passed gap=$behaviouralGapId")
    }

    /**
     * Records a `module_requested` event (Events-Modelling 1.5) — the CHW's
     * request for a module to be added to their quota, or a free-text
     * suggestion for a module that doesn't exist yet. Backend family: `coaching`.
     *
     * Exactly one of [moduleId] (an existing catalogue module) or
     * [requestedModuleName] (a new-topic suggestion) is set; [reason] is
     * optional. Both non-column fields ride in `payload_json` per the spec
     * (`{"requested_module_name": …, "reason": …}`). The row is written to the
     * outbound queue and synced by the OutboundSyncWorker like any other event —
     * so the request survives offline and ships on the next flush.
     */
    suspend fun recordModuleRequested(
        moduleId: String? = null,
        moduleFamilyId: String? = null,
        requestedModuleName: String? = null,
        reason: String? = null,
    ) {
        val payload = buildJsonObject {
            requestedModuleName?.takeIf { it.isNotBlank() }?.let { put("requested_module_name", it) }
            reason?.takeIf { it.isNotBlank() }?.let { put("reason", it) }
        }.let { if (it.isEmpty()) null else it.toString() }
        val networkState = if (MicroCoachingSDK.getInstance().isNetworkAvailable()) "online" else "offline"
        dao.insert(
            build(
                eventType = "module_requested",
                moduleId = moduleId,
                moduleFamilyId = moduleFamilyId,
                networkState = networkState,
                payloadJson = payload,
            ),
        )
        Log.d(TAG, "[$sessionId] module_requested saved — moduleId=$moduleId name=$requestedModuleName")
    }

    /**
     * Records a `video_progress_updated` event (see docs/_events/video.md) — the
     * CHW's watch progress for an assigned training video. Backend family:
     * `coaching`. Replaces the removed `PUT /sync/video-progress` route; the row
     * is written to the outbound queue and shipped on the next telemetry flush,
     * so progress survives offline and is merged **monotonically** server-side.
     *
     * [sourceDocumentId] is the canonical video id. Callers should emit on the
     * throttled cadence in video.md (every 10–15 s or ≥5% delta, on pause /
     * background / screen-exit, and a final completion event with
     * [percentWatched] = 100 and [completed] = true).
     */
    suspend fun recordVideoProgress(
        sourceDocumentId: String,
        lastPositionMs: Long,
        percentWatched: Double,
        completed: Boolean,
    ) {
        val payload = buildJsonObject {
            put("source_document_id", sourceDocumentId)
            put("last_position_ms", lastPositionMs)
            put("percent_watched", percentWatched)
            put("completed", completed)
        }.toString()
        val networkState = if (MicroCoachingSDK.getInstance().isNetworkAvailable()) "online" else "offline"
        dao.insert(
            build(
                eventType = "video_progress_updated",
                triggerType = "workflow_event",
                outcome = if (completed) "completed" else null,
                networkState = networkState,
                payloadJson = payload,
            ),
        )
        Log.d(
            TAG,
            "[$sessionId] video_progress_updated saved — video=$sourceDocumentId " +
                "pct=$percentWatched completed=$completed",
        )
    }

    /**
     * Records a knowledge source document being opened in the previewer. Backend
     * family: `coaching`. Queued like any event, so a view recorded offline ships
     * on the next flush.
     *
     * A knowledge document belongs to no module, so every module-, quiz-, card-
     * and patient-scoped column stays null and only the document rides in
     * `payload_json`. One row per view — [build] mints a fresh `event_id` each
     * call, which is what makes a repeat open increment the count instead of
     * deduping against the previous one. No title is sent; the server resolves it
     * and ignores a client-supplied value.
     *
     * [sourceDocumentId] must be a UUID — the analytics query drops rows whose
     * payload id doesn't parse.
     */
    suspend fun recordDocumentViewed(sourceDocumentId: String) {
        val payload = buildJsonObject {
            put("source_document_id", sourceDocumentId)
        }.toString()
        val networkState = if (MicroCoachingSDK.getInstance().isNetworkAvailable()) "online" else "offline"
        dao.insert(
            build(
                eventType = "document_viewed",
                networkState = networkState,
                payloadJson = payload,
            ),
        )
        Log.d(TAG, "[$sessionId] document_viewed saved — document=$sourceDocumentId network=$networkState")
    }

    // ── Patient-interaction events (UC-2 / clinical_observed family) ──────────

    /**
     * Records the `spice_action_observed` event fired after a SPICE patient
     * assessment submits successfully. Backend family: `clinical_observed`.
     *
     * Per v1.1 events spec, this row captures whether the CHW referred the
     * patient correctly along three axes — `correctReferral` (headline),
     * `correctReferralLocation`, `correctReferralType`. The headline value
     * is also written to the top-level `outcome` column as `"correct"` /
     * `"wrong"` for fast aggregation.
     *
     * `payload_json` is built as a strict object here (rather than the
     * `raw`-wrapping branch in [SyncPayloadMapper]) so the backend ingests
     * the expected key shape directly.
     */
    suspend fun recordSpiceActionObserved(
        patientIdHash: String,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
        behaviouralGapId: String? = null,
        correctReferral: Boolean,
        correctReferralLocation: Boolean,
        correctReferralType: Boolean,
        networkState: String? = null,
        /**
         * Optional override of the top-level `outcome` column. When null, falls
         * back to "correct"/"wrong" derived from [correctReferral]. The gap
         * dispatcher passes "incorrect" per design §1.
         */
        outcome: String? = null,
        /**
         * The `rule_type` of the fired gap evaluator (e.g. `wrong_facility_tier`).
         * Included in `payload_json` for QA replay per GAP_DETECTION_SDK.md §1
         * "non-state fields ship to ClickHouse".
         */
        ruleType: String? = null,
        /**
         * JSON-serialised evidence map from the evaluator's `buildEvidence`,
         * merged into `payload_json` under the `evidence` key. PII must already
         * be hashed by the caller — see [com.medtroniclabs.microcoaching.domain.gaps.evidence.EvidenceBuilder].
         */
        evidenceJson: String? = null,
    ) {
        val evidenceObject = evidenceJson
            ?.takeIf { it.isNotBlank() && it != "{}" }
            ?.let { raw ->
                runCatching { EVIDENCE_JSON.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            }
        val payload = buildJsonObject {
            if (behaviouralGapId != null) put("behavioural_gap_id", behaviouralGapId)
            put("correctReferral", correctReferral)
            put("correctReferralLocation", correctReferralLocation)
            put("correctReferralType", correctReferralType)
            if (ruleType != null) put("rule_type", ruleType)
            if (evidenceObject != null) put("evidence", evidenceObject)
        }.toString()
        val resolvedOutcome = outcome ?: if (correctReferral) "correct" else "wrong"
        dao.insert(
            build(
                eventType = "spice_action_observed",
                triggerType = "workflow_event",
                outcome = resolvedOutcome,
                networkState = networkState,
                payloadJson = payload,
                behaviouralGapId = behaviouralGapId,
                patientIdHash = patientIdHash,
                patientVisitId = patientVisitId,
                patientTrackId = patientTrackId,
                villageId = villageId,
                upazilaId = upazilaId,
            ),
        )
        Log.d(
            TAG,
            "[$sessionId] spice_action_observed saved — outcome=$resolvedOutcome rule=$ruleType gap=$behaviouralGapId",
        )
    }

    /**
     * Records the `risk_flag_observed` event fired when SPICE surfaces a
     * high-risk threshold (BP crisis, etc.). Backend family: `clinical_observed`.
     *
     * Emitted both from the post-assessment path (when `risk_level` lands as
     * HIGH/EMERGENCY) and from the mid-visit hook
     * ([com.medtroniclabs.microcoaching.MicroCoachingSDK.onRiskFlagObserved]).
     */
    suspend fun recordRiskFlagObserved(
        riskLevel: String,
        patientIdHash: String? = null,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
        behaviouralGapId: String? = null,
        networkState: String? = null,
    ) {
        val payload = buildJsonObject {
            put("risk_level", riskLevel)
            if (behaviouralGapId != null) put("behavioural_gap_id", behaviouralGapId)
        }.toString()
        dao.insert(
            build(
                eventType = "risk_flag_observed",
                triggerType = "workflow_event",
                networkState = networkState,
                payloadJson = payload,
                behaviouralGapId = behaviouralGapId,
                patientIdHash = patientIdHash,
                patientVisitId = patientVisitId,
                patientTrackId = patientTrackId,
                villageId = villageId,
                upazilaId = upazilaId,
            ),
        )
        Log.d(TAG, "[$sessionId] risk_flag_observed saved — risk=$riskLevel gap=$behaviouralGapId")
    }

    /**
     * Records the `card_shown` event when a coaching surface fires on the CHW's
     * device. Backend family: `coaching`. Emitted from the post-assessment hook
     * ([com.medtroniclabs.microcoaching.sdk.hooks.AssessmentSubmittedHandler]),
     * which carries the patient context the surface was triggered from.
     *
     * Distinct from `module_card_viewed`, which reports a lesson card being
     * paged through inside a module.
     */
    suspend fun recordCardShown(
        cardType: String,
        triggerType: String,
        inferenceMode: String? = null,
        moduleFamilyId: String? = null,
        moduleId: String? = null,
        clinicalDomain: String? = null,
        patientIdHash: String? = null,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
        behaviouralGapId: String? = null,
        networkState: String? = null,
    ) {
        dao.insert(
            build(
                eventType = "card_shown",
                clinicalDomain = clinicalDomain,
                cardType = cardType,
                triggerType = triggerType,
                inferenceMode = inferenceMode,
                moduleFamilyId = moduleFamilyId,
                moduleId = moduleId,
                networkState = networkState,
                behaviouralGapId = behaviouralGapId,
                patientIdHash = patientIdHash,
                patientVisitId = patientVisitId,
                patientTrackId = patientTrackId,
                villageId = villageId,
                upazilaId = upazilaId,
            ),
        )
        Log.d(
            TAG,
            "[$sessionId] card_shown saved — cardType=$cardType trigger=$triggerType mode=$inferenceMode",
        )
    }

    /**
     * Generic coaching/learning event write — the single path the learn flow
     * (module delivery, lesson cards, quiz attempts) records through. Mirrors
     * the parameter surface, outcome derivation, and network-state fallback of
     * the former `LearnViewModel.recordEvent`, so rows are byte-identical.
     *
     * `outcomeOverride` wins; otherwise a `module_quiz_attempted` row with a
     * known [isCorrect] derives `"correct"`/`"wrong"` (per-question rows stay in
     * sync with the aggregate finishQuiz path). When [networkState] is null it
     * defaults to the SDK's ConnectivityManager snapshot so every event family
     * shares one vocabulary. Wrapped in try/catch — a telemetry failure must
     * never break the learn flow.
     */
    suspend fun recordCoachingEvent(
        eventType: String,
        clinicalDomain: String? = null,
        cardType: String? = null,
        quizQuestionId: String? = null,
        selectedOption: Int? = null,
        isCorrect: Boolean? = null,
        moduleFamilyId: String? = null,
        moduleId: String? = null,
        moduleVersion: Int? = null,
        cardFamilyId: String? = null,
        quizFamilyId: String? = null,
        quizScorePct: Float? = null,
        outcomeOverride: String? = null,
        behaviouralGapId: String? = null,
        triggerType: String? = null,
        inferenceMode: String? = null,
        networkState: String? = null,
    ) {
        try {
            val outcome = outcomeOverride ?: when {
                eventType == "module_quiz_attempted" && isCorrect != null ->
                    if (isCorrect) "correct" else "wrong"
                else -> null
            }
            val resolvedNetworkState = networkState
                ?: if (MicroCoachingSDK.getInstance().isNetworkAvailable()) "online" else "offline"

            dao.insert(
                build(
                    eventType = eventType,
                    clinicalDomain = clinicalDomain,
                    cardType = cardType,
                    triggerType = triggerType,
                    inferenceMode = inferenceMode,
                    quizQuestionId = quizQuestionId,
                    selectedOption = selectedOption,
                    isCorrect = isCorrect,
                    outcome = outcome,
                    networkState = resolvedNetworkState,
                    moduleFamilyId = moduleFamilyId,
                    moduleId = moduleId,
                    cardFamilyId = cardFamilyId,
                    quizFamilyId = quizFamilyId,
                    moduleVersion = moduleVersion,
                    quizScorePct = quizScorePct,
                    behaviouralGapId = behaviouralGapId,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record event '$eventType': ${e.message}")
        }
    }

    // ── Private builder ───────────────────────────────────────────────────────

    /**
     * `payload_json` for the `chat_feedback_*` events — the rated response object
     * as a JSON string (`response`; the RAG response or its offline-constructed
     * equivalent), the CHW's original [question] (Events-Modelling 1.7), plus the
     * CHW's optional free-text detail (`feedback`) when they typed one in the
     * thumbs-down sheet (Events Modelling 1.5, confirmed backend passthrough).
     */
    private fun buildChatResponsePayload(
        response: String,
        feedbackText: String? = null,
        question: String? = null,
    ): String =
        buildJsonObject {
            if (!question.isNullOrBlank()) put("question", question)
            put("response", response)
            if (!feedbackText.isNullOrBlank()) put("feedback", feedbackText)
        }.toString()

    private fun build(
        eventType: String,
        clinicalDomain: String? = null,
        cardType: String? = null,
        triggerType: String? = null,
        inferenceMode: String? = null,
        quizQuestionId: String? = null,
        selectedOption: Int? = null,
        isCorrect: Boolean? = null,
        outcome: String? = null,
        validatorStatus: String? = null,
        fallbackUsed: Boolean? = null,
        networkState: String? = null,
        moduleFamilyId: String? = null,
        moduleId: String? = null,
        cardFamilyId: String? = null,
        quizFamilyId: String? = null,
        moduleVersion: Int? = null,
        quizScorePct: Float? = null,
        behaviouralGapId: String? = null,
        payloadJson: String? = null,
        // ── Patient-interaction context (UC-2 / clinical_observed) ─────────
        patientIdHash: String? = null,
        patientVisitId: String? = null,
        patientTrackId: String? = null,
        villageId: String? = null,
        upazilaId: String? = null,
    ) = CoachingEventEntity(
        eventId = UUID.randomUUID().toString(),
        sdkVersion = appVersionName ?: sdkVersion,
        eventFamily = eventFamilyFor(eventType),
        sessionId = sessionId,
        chwId = chwId,
        eventType = eventType,
        clinicalDomain = clinicalDomain,
        cardType = cardType,
        triggerType = triggerType,
        inferenceMode = inferenceMode,
        quizQuestionId = quizQuestionId,
        selectedOption = selectedOption,
        isCorrect = isCorrect,
        outcome = outcome,
        validatorStatus = validatorStatus,
        fallbackUsed = fallbackUsed,
        networkState = networkState,
        moduleFamilyId = moduleFamilyId,
        moduleId = moduleId,
        cardFamilyId = cardFamilyId,
        quizFamilyId = quizFamilyId,
        moduleVersion = moduleVersion,
        quizScorePct = quizScorePct,
        behaviouralGapId = behaviouralGapId,
        payloadJson = payloadJson,
        patientIdHash = patientIdHash,
        patientVisitId = patientVisitId,
        patientTrackId = patientTrackId,
        villageId = villageId,
        upazilaId = upazilaId,
    )
}
