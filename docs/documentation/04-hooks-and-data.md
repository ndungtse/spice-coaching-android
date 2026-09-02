# 04 — Workflow Hooks & Data

**Version:** 0.6.0-SNAPSHOT · **Date:** 2026-08-31 · **Status:** Draft

How SPICE feeds clinical-workflow events into the SDK, and how it reads SDK-owned data back out. The SDK keeps its own `microcoaching.db` Room database fully separate from SPICE's database — these hooks and interfaces are the entire data boundary.

> **Note:** all hooks are no-ops if the SDK is not initialised. Guard with `if (!MicroCoachingSDK.isInitialized()) return`. The SDK wraps event recording in `runCatching`, so a telemetry failure never blocks your host flow.

> **Important:** `onHomeScreenShown(chwId)` is the **only** call that sets the current CHW id. Every other hook starts by resolving that id and silently no-ops when it is absent — so if hooks, badges, or morning cards appear to do nothing, check that `onHomeScreenShown` has run first.

---

## Workflow hooks overview

Call these from the matching points in your clinical workflow. All are on `MicroCoachingSDK.getInstance()`.

| Hook | Signature | Call when |
|---|---|---|
| `onHomeScreenShown` | `(chwId: String)` | Home screen shown. Sets the current CHW id and refreshes morning modules. |
| `onPatientSelected` | `(patientId: String)` | CHW opens a patient. |
| `onAssessmentSubmitted` | `(encounterId: String, patientId: String, assessmentData: Map<String, Any> = emptyMap())` | An assessment is saved. Primary UC-2/UC-3 trigger (workflow signals — referral correctness lives in `onReferralSubmitted`). |
| `onReferralSubmitted` | `(encounterId: String, patientId: String, referralData: Map<String, Any> = emptyMap())` | The CHW commits a referral (picks + confirms a destination). Referral-correctness evaluation happens here. See [below](#forwarding-referral-data--onreferralsubmitted). |
| `onVisitCompleted` | `(encounterId: String)` | A visit/encounter closes (backfills the visit id). |
| `onTodaysVisitsUpdated` | `(visits: List<TodaysVisit>)` | Today's scheduled visits changed — push a PII-free projection (see below). |
| `onMorningOpen` | `()` | The morning coaching surface opens. |
| `onFormSubmitted` | `(formId: String, payload: Map<String, String> = emptyMap())` | A SPICE form is submitted. |
| `onRuleFired` | `(ruleId: String, payload: Map<String, String> = emptyMap())` | A SPICE clinical rule fires. |
| `onRiskFlagObserved` | `(riskLevel: String, patientId: String? = null)` | A risk flag is raised. |
| `onEquipmentAnomaly` | `(detail: String)` | A device/equipment anomaly is detected. |
| `onModuleQuizCompleted` | `(moduleFamilyId: String, moduleId: String?, scoreFraction: Float, passed: Boolean)` | A learning quiz finishes. |
| `onModuleCardsCompleted` | `(moduleFamilyId: String, moduleId: String?)` | A module's learning cards are finished (no quiz). |
| `onCHWContextUpdated` | `(chwWorkContext: CHWWorkContext)` | CHW work context changes (see below). |
| `onConnectivityRestored` | `()` | Network came back — flush telemetry + sync. |
| `flushTelemetryNow` | `()` | Force an immediate telemetry flush. |

> **Note:** you don't have to wire every hook — all of them no-op safely. SPICE today wires `onHomeScreenShown`, `onTodaysVisitsUpdated`, `onAssessmentSubmitted`, `onReferralSubmitted`, and `onConnectivityRestored`. The rest are available for richer telemetry as the integration grows.

---

## Wiring the core hooks

```kotlin
val sdk = MicroCoachingSDK.getInstance()

// Home screen
sdk.onHomeScreenShown(chwId = session.userId)

// Patient opened
sdk.onPatientSelected(patientId = patient.patientTrackId.toString())

// Connectivity restored (e.g. from a ConnectivityManager callback / onResume)
sdk.onConnectivityRestored()
```

**SPICE reference**:
- `onHomeScreenShown(chwId)` is the first call in `HomeScreenFragment.setupCoachingSurfaces()`.
- `onConnectivityRestored()` is forwarded from `LandingActivity`'s `onResume()` via a small guarded helper:

```kotlin
private fun notifyCoachingSdkOnConnectivityRestored() {
    if (!MicroCoachingSDK.isInitialized()) return
    if (connectivityManager.isNetworkAvailable()) {
        MicroCoachingSDK.getInstance().onConnectivityRestored()
    }
}
```

---

## Forwarding assessment data

`onAssessmentSubmitted` fires at assessment **save** and drives the UC-2/UC-3 coaching signals (resolving the best coaching card, workflow telemetry). It does **not** evaluate referral correctness — that happens in [`onReferralSubmitted`](#forwarding-referral-data--onreferralsubmitted), which fires later when the CHW actually commits a referral destination. The richer the `assessmentData` map, the better the coaching-card match.

The recommended pattern (used by SPICE) is a small **mapping extension** that turns your assessment entity into the `Map<String, Any>` the SDK expects, then a guarded hook call on a background thread.

```kotlin
// 1) Map your entity → the SDK's assessmentData map
fun AssessmentEntity.toSdkAssessmentMap(
    systemReferralStatus: String? = null,
    systemReferralReasons: List<String>? = null,
    upazilaId: String? = null,
): Map<String, Any> = buildMap {
    patientId?.let { put("patient_id", it) }
    put("village_id", villageId)
    upazilaId?.let { put("upazila_id", it) }
    put("assessment_type", assessmentType)
    put("is_referred", isReferred)
    put("referral_status", referralStatus.name)
    referredReason?.let { put("referred_reason", it.joinToString(",")) }
    systemReferralStatus?.let { put("system_referral_status", it) }
    systemReferralReasons?.let { put("system_referral_reasons", it.joinToString(",")) }
    // + facility-tier keys parsed out of your assessment-details JSON
}

// 2) Call the hook on assessment success
private fun notifyMicroCoachingSDK(assessmentEntity: AssessmentEntity) {
    if (!MicroCoachingSDK.isInitialized()) return
    val chwId = currentChwIdOrEmpty()
    if (chwId.isBlank()) return

    lifecycleScope.launch(Dispatchers.IO) {
        MicroCoachingSDK.getInstance().onAssessmentSubmitted(
            encounterId = "",
            patientId = assessmentEntity.patientId.orEmpty(),
            assessmentData = assessmentEntity.toSdkAssessmentMap(/* … */),
        )
    }
}
```

**SPICE reference** — the mapping lives in `microcoaching/AssessmentEntityExt.kt`; the hook is called from `AssessmentActivity.notifyMicroCoachingSDK()` on `ResourceState.SUCCESS`, and identically from `CbsActivity`. SPICE resolves `upazilaId` from `villageId → chiefdomId` via its `MetaDataDAO`, and snapshots the mutable view-model referral fields before the IO coroutine runs:

```kotlin
private fun notifyMicroCoachingSDK(assessmentEntity: AssessmentEntity) {
    if (!MicroCoachingSDK.isInitialized()) return
    val chwId = runCatching { SecuredPreference.getUserId().toString() }.getOrDefault("")
    if (chwId.isBlank()) return

    val systemReferralStatus = viewModel.referralStatus
    val systemReferralReasons = viewModel.referralReason

    lifecycleScope.launch(Dispatchers.IO) {
        val upazilaId = runCatching {
            assessmentEntity.villageId.toLongOrNull()
                ?.let { metaDataDAO.getVillageByID(it).chiefdomId }?.toString()
        }.getOrNull()

        MicroCoachingSDK.getInstance().onAssessmentSubmitted(
            encounterId = "",
            patientId = assessmentEntity.patientId.orEmpty(),
            assessmentData = assessmentEntity.toSdkAssessmentMap(
                systemReferralStatus = systemReferralStatus,
                systemReferralReasons = systemReferralReasons,
                upazilaId = upazilaId,
            ),
        )
    }
}
```

> **Note:** SPICE passes `encounterId = ""` today — `AssessmentActivity` does not have a visit id at submit time, and the SDK accepts blank (writes a null `patient_visit_id`). `onVisitCompleted(encounterId)` exists to backfill the visit id later, but **SPICE does not call it yet**. Wire it when your workflow exposes a visit/encounter id.

### `assessmentData` — keys the SDK reads

All optional; missing keys degrade gracefully (the SDK falls back to a `risk_level`-based heuristic / condition-agnostic card).

| Key | Used for |
|---|---|
| `patient_id` | Telemetry tracing (SHA-256 hashed before any write). |
| `patient_track_id` | Patient correlation. |
| `village_id`, `upazila_id` | Geo on the coaching event row. |
| `assessment_type` | Card-type routing. |
| `risk_level`, `cvd_risk_level` | Risk-based fallback + `risk_flag_observed`. |
| `is_referred`, `referral_status`, `referred_reason` | The CHW's actual referral action/outcome. |
| `system_referral_status`, `system_referral_reasons` | What the system prescribed. |
| `referralFacilityType` / `childReferralFacilityType`, `picked_facility_type` | Facility-tier context (the compliance comparison itself runs in `onReferralSubmitted`). |
| `age`, `gender`, `bmi`, `avg_systolic`, `avg_diastolic`, `fbs_value` | Clinical vitals. |
| `is_pregnant`, `is_htn_diagnosis`, `is_diabetes_diagnosis` | Clinical flags. |
| `behavioural_gap_id`, `spice_event_code` | Gap-rule dispatch. |

> **Note:** `picked_facility_type` (the tier the CHW actually selected at the referral picker) is **not written by SPICE yet** — the parser is in place in `AssessmentEntityExt.kt` so the SDK's `wrong_facility_tier` rule fires automatically once SPICE writes the key. See [docs/gaps/GAPS_TEST.md](../gaps/GAPS_TEST.md).

---

## Forwarding referral data — `onReferralSubmitted`

Call `onReferralSubmitted(encounterId, patientId, referralData)` when the CHW **commits a referral** — picks and confirms a destination. This is the moment the "what the CHW actually did" side of the data exists, so referral-correctness evaluation runs here (not in `onAssessmentSubmitted`, which fires earlier at save time).

The `referralData` map carries two nested branches the synced compliance rules resolve paths into:

| Branch | Carries | Example keys |
|---|---|---|
| `recommended.*` | The rule-engine recommendation, nested as your rule engine produced it. | `recommended.isReferred` (Boolean), `recommended.referredReason` (List<String>), deeper paths like `recommended.assessmentDetails.anc.summary.highRiskPregnantWoman.URGENT` |
| `actual.*` | What the CHW actually did. | `actual.didRefer` (Boolean), `actual.referralReasons` (List<String>), `actual.isUrgent` (Boolean), `actual.destinationTier` (String) |

Top-level keys `spice_event_code`, `assessment_type`, `patient_track_id`, `village_id`, `upazila_id` may accompany the branches for dispatch and geo tagging. The path contract lives in `SpiceReferralComplianceEvaluator` (`domain/gaps/SpiceReferralComplianceEvaluator.kt`).

```kotlin
fun AssessmentEntity.toComplianceState(): Map<String, Any> = buildMap {
    put("recommended", buildRecommendedBranch())   // your rule engine's structured output
    put("actual", buildActualBranch())             // didRefer, destinationTier, …
    put("assessment_type", assessmentType)
    villageId?.let { put("village_id", it) }
}
```

**Fail-safe by design:** missing paths resolve to null and the rule operators treat that as "no value" — a rule whose data isn't available simply doesn't fire. An absent `actual.*` branch means "can't tell", never a false positive. Malformed or unknown rule nodes evaluate to false.

**SPICE reference** — `AssessmentActivity.notifyMicroCoachingSDK(entity, asReferral = true)` calls this hook with `entity.toComplianceState(...)` from `microcoaching/AssessmentEntityExt.kt`, on `Dispatchers.IO`, snapshotting mutable view-model fields first (same pattern as the assessment hook above).

---

## Gap detection

Gap detection is **fully internal to the SDK and always on** — there is nothing for a host to wire, enable, disable, or implement:

- There is **no Builder flag**: `enableGapDetection` exists on the config data class but has no setter and cannot be changed by a host.
- There is **no registration API**: the gap evaluators are constructed inside the SDK; hosts cannot add or replace them.
- `onAssessmentSubmitted` **does not run gap rules** — it only emits workflow signals. Referral-correctness gaps are evaluated inside [`onReferralSubmitted`](#forwarding-referral-data--onreferralsubmitted), which emits one gap-tagged `spice_action_observed` per fired gap.

The host's entire contribution is data shape: forward a well-formed `referralData` map (above) and the synced rules do the rest.

See [docs/gaps/GAP_DETECTION_SDK.md](../gaps/GAP_DETECTION_SDK.md) for the rule model and [docs/gaps/GAPS_TEST.md](../gaps/GAPS_TEST.md) for the test plan.

---

## Receiving data — push pattern

Register a `MicroCoachingDataCallback` at init to be notified of events without holding a Room dependency. All methods have default no-op bodies, so override only what you need.

```kotlin
MicroCoachingSDK.Builder(this)
    .dataCallback(object : MicroCoachingDataCallback {
        override fun onCoachingEventsReady(events: List<Map<String, Any>>) {
            // Forward to your backend if needed
        }
        override fun onModelReady() {
            Log.d("Coaching", "On-device model ready")
        }
    })
    .build()
```

| Method | Fired when |
|---|---|
| `onCoachingEventsReady(events: List<Map<String, Any>>)` | After each successful backend sync. |
| `onSyncCompleted(syncedEventCount: Int)` | After a sync cycle. |
| `onModelDownloadProgress(progressPercent: Int)` | Model download progresses (`-1` = indeterminate). |
| `onModelReady()` | Model download completed. |
| `onModelDownloadFailed(reason: String)` | Model download failed. |

> **Note:** `onModelReady()` takes **no arguments**. (Some older snippets show `onModelReady(modelPath)` — that is incorrect.)

---

## Receiving data — pull pattern

Query SDK data on demand via `CoachingDataRepository`. The SDK is DI-free, but you can expose the repository through your own Hilt graph:

```kotlin
// Your Hilt AppModule
@Provides @Singleton
fun provideCoachingDataRepository(): CoachingDataRepository =
    MicroCoachingSDK.getInstance().dataRepository

// Any ViewModel
@HiltViewModel
class MyViewModel @Inject constructor(
    private val coachingRepo: CoachingDataRepository,
) : ViewModel() {
    suspend fun load(sessionId: String) = coachingRepo.getChatHistory(sessionId)
}
```

| Method | Returns |
|---|---|
| `getChatHistory(sessionId: String)` | `List<ChatMessage>` for a session (time ascending). |
| `getAllSessionIds()` | `List<String>` distinct session ids, most recent first. |
| `getPendingCoachingEvents()` | `List<Map<String, Any>>` not-yet-synced events. |
| `markEventsSynced(eventIds: List<String>)` | Marks events synced after you forward them. |
| `exportAllData()` | `SdkDataExport(chatMessages, coachingEvents, exportedAtMs)` — full snapshot. |

> **Note:** SPICE does not currently add this Hilt binding — it is optional. Add it only if you want SPICE code to read chat history or forward coaching events through your own backend rather than the SDK's built-in sync.

---

## CHW work context

To let the chat answer questions like "how many patients did I screen today?", push a `CHWWorkContext` snapshot:

```kotlin
MicroCoachingSDK.getInstance().onCHWContextUpdated(
    CHWWorkContext(
        screenedTodayCount = 12,
        recentPatients = listOf(
            RecentPatientSummary(
                conditions = listOf("HYPERTENSION", "DIABETES"),
                riskLevel = "HIGH",
                screenedAtMs = System.currentTimeMillis(),
                villageId = "village-42",
            ),
        ),
    ),
)

val ctx = MicroCoachingSDK.getInstance().loadCHWContext()   // read back the last snapshot
```

Both `CHWWorkContext` and `RecentPatientSummary` are `@Serializable`. Only de-identified, aggregate context is stored — no patient names.

---

## Today's visits

Push a PII-free projection of the CHW's visits due **today** — the SDK matches them against synced trigger bindings to pick cold-start refresher modules:

```kotlin
MicroCoachingSDK.getInstance().onTodaysVisitsUpdated(
    visitsDueToday.map {
        TodaysVisit(
            type = it.appointmentType,          // "HH_VISIT" | "MEDICAL_REVIEW" | "REFERRED" | …
            encounterType = it.encounterType,   // "ANC" | "MALARIA" | "PNC_MOTHER" | … or null
            dueDateIso = it.dueDate,            // ISO 8601; the SDK keeps only today's
            isPregnant = it.isPregnant,         // null = unknown (constraint skipped, not failed)
            villageId = it.villageId,
        )
    },
)
```

`TodaysVisit` carries no patient identifiers — only the clinical-type signal needed to pick a module. **SPICE reference**: `HomeScreenFragment.pushTodaysVisits()` maps `FollowUpDao.getVisitsDueOn(today)` rows via a dedicated PII-free projection (`microcoaching/TodaysVisitRow.kt`).

---

## Telemetry flush hooks

| Call | Effect |
|---|---|
| `onConnectivityRestored()` | Flush pending spans + trigger an immediate sync. Idempotent. The SDK also fires this internally on its own network callback; calling it from your `onResume()` is a safe belt-and-braces. |
| `flushTelemetryNow()` | Force an immediate span flush regardless of the batch interval. |

---

## Next steps

- [05 — Model & Voice](./05-model-and-voice.md) — `onModelDownloadProgress` / `onModelReady` and the download lifecycle.
- [02 — Initialization](./02-initialization.md) — register the `dataCallback` and configure telemetry.
