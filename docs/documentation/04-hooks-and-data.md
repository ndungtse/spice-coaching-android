# 04 — Workflow Hooks & Data

**Version:** 0.3.8-SNAPSHOT · **Date:** 2026-06-03 · **Status:** Draft

How SPICE feeds clinical-workflow events into the SDK, and how it reads SDK-owned data back out. The SDK keeps its own `microcoaching.db` Room database fully separate from SPICE's database — these hooks and interfaces are the entire data boundary.

> **Note:** all hooks are no-ops if the SDK is not initialised. Guard with `if (!MicroCoachingSDK.isInitialized()) return`. The SDK wraps event recording in `runCatching`, so a telemetry failure never blocks your host flow.

---

## Workflow hooks overview

Call these from the matching points in your clinical workflow. All are on `MicroCoachingSDK.getInstance()`.

| Hook | Signature | Call when |
|---|---|---|
| `onHomeScreenShown` | `(chwId: String)` | Home screen shown. Sets the current CHW id and refreshes morning modules. |
| `onPatientSelected` | `(patientId: String)` | CHW opens a patient. |
| `onAssessmentSubmitted` | `(encounterId: String, patientId: String, assessmentData: Map<String, Any> = emptyMap())` | An assessment is saved. Primary UC-2/UC-3 trigger. |
| `onVisitCompleted` | `(encounterId: String)` | A visit/encounter closes (backfills the visit id). |
| `onMorningOpen` | `()` | The morning coaching surface opens. |
| `onFormSubmitted` | `(formId: String, payload: Map<String, String> = emptyMap())` | A SPICE form is submitted. |
| `onRuleFired` | `(ruleId: String, payload: Map<String, String> = emptyMap())` | A SPICE clinical rule fires. |
| `onRiskFlagObserved` | `(riskLevel: String, patientId: String? = null)` | A risk flag is raised. |
| `onEquipmentAnomaly` | `(detail: String)` | A device/equipment anomaly is detected. |
| `onModuleQuizCompleted` | `(moduleFamilyId: String, moduleId: String?, scoreFraction: Float, passed: Boolean)` | A learning quiz finishes. |
| `onCHWContextUpdated` | `(chwWorkContext: CHWWorkContext)` | CHW work context changes (see below). |
| `onConnectivityRestored` | `()` | Network came back — flush telemetry + sync. |
| `flushTelemetryNow` | `()` | Force an immediate telemetry flush. |

> **Note:** you don't have to wire every hook. SPICE today wires `onHomeScreenShown`, `onAssessmentSubmitted`, and `onConnectivityRestored`. The rest are available for richer telemetry as the integration grows.

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

`onAssessmentSubmitted` is the most important hook — it drives the UC-2/UC-3 coaching signals and gap detection. The richer the `assessmentData` map, the better the SDK can compute referral correctness.

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
| `system_referral_status`, `system_referral_reasons` | What the system prescribed — enables 3-axis referral-correctness. |
| `referralFacilityType` / `childReferralFacilityType`, `picked_facility_type` | `wrong_facility_tier` gap rule. |
| `age`, `gender`, `bmi`, `avg_systolic`, `avg_diastolic`, `fbs_value` | Clinical vitals. |
| `is_pregnant`, `is_htn_diagnosis`, `is_diabetes_diagnosis` | Clinical flags. |
| `behavioural_gap_id`, `spice_event_code` | Gap-rule dispatch. |

> **Note:** `picked_facility_type` (the tier the CHW actually selected at the referral picker) is **not written by SPICE yet** — the parser is in place in `AssessmentEntityExt.kt` so the SDK's `wrong_facility_tier` rule fires automatically once SPICE writes the key. See [docs/gaps/GAPS_TEST.md](../gaps/GAPS_TEST.md).

---

## Gap detection

When `enableGapDetection = true` (the default), `onAssessmentSubmitted` iterates the synced gap-detection rules and emits one `spice_action_observed` per fired gap (tagged with its `behavioural_gap_id`). When `false`, the SDK falls back to a single referral-only emission — useful as a kill switch if a rule evaluator misbehaves in the field.

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

## Telemetry flush hooks

| Call | Effect |
|---|---|
| `onConnectivityRestored()` | Flush pending spans + trigger an immediate sync. Idempotent. The SDK also fires this internally on its own network callback; calling it from your `onResume()` is a safe belt-and-braces. |
| `flushTelemetryNow()` | Force an immediate span flush regardless of the batch interval. |

---

## Next steps

- [05 — Model & Voice](./05-model-and-voice.md) — `onModelDownloadProgress` / `onModelReady` and the download lifecycle.
- [02 — Initialization](./02-initialization.md) — register the `dataCallback` and configure telemetry.
