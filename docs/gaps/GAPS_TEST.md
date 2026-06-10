# Gap Detection — End-to-End Test Plan

> ⚠️ **SUPERSEDED — kept for reference.** This plan covers the old
> `wrong_facility_tier` (`rule_type` schema) approach, which was **reverted** —
> v3 ships no facility-tier gap. The current detection schema is
> `spice_referral_compliance`. Use:
> [DETECTION_RULE_SCHEMA.md](./DETECTION_RULE_SCHEMA.md) (schema),
> [COMPLIANCE_TEST_SPEC.md](./COMPLIANCE_TEST_SPEC.md) (current test plan),
> [GAP_CATALOG.md](./GAP_CATALOG.md) (what we detect vs. what SPICE measures).
> The SPICE-navigation (§B) and telemetry-loop sections below are still useful
> reference; the rule schema/evaluator specifics are not.

**Scope:** the flagship `wrong_facility_tier` rule, traced through the real SPICE 2.0 navigation, SDK rule dispatch, telemetry sync, and morning-card return.
**Companion docs:** [GAP_DETECTION_SDK.md](./GAP_DETECTION_SDK.md), [28_05_status.md](./28_05_status.md).
**Date:** 2026-05-28

This document walks one full loop end-to-end against the **actual** SPICE 2.0 home → assessment flow (HOUSEHOLDS / SERVICE RECIPIENT → MEMBERS → SERVICES), not the shorthand "Home → patient list → assessment" used in earlier drafts. Each step names the SPICE Activity/Fragment, the data you enter, the SDK log lines to grep for, the Room rows / events that should appear, and the backend round-trip.

The plan assumes:
- SDK branch `feat/hooks-and-gap-detection-sdk` (this branch) — composite-built into SPICE.
- SPICE 2.0 `uhis-dev` with the small `AssessmentEntityExt.kt` patch shipped on this branch.
- Backend `coaching-platform` reachable, JWT issued.

> **Read §F before running.** There are two real-world findings the test plan turns on (the SDK assessmentData map is narrower than `GAP_DETECTION_SDK.md` assumed, and the picked facility's tier is currently not in it at all). One of them gates whether the flagship rule can fire end-to-end in this iteration.

---

## §A. Prerequisites

| Item | What to verify |
|---|---|
| Device / emulator | Android 9+ with USB debugging; `adb` reachable |
| SPICE 2.0 build | `uhis-dev` or your integration branch, **with the `AssessmentEntityExt` patch on this SDK branch applied** (forwards `referralFacilityType` to the SDK map) |
| MicroCoaching SDK | Composite-built into SPICE — see [docs/sdk-setup-guide.md](../sdk-setup-guide.md) |
| Backend (coaching-platform) | Reachable from the device; `backendUrl` set in `MicroCoachingConfig` |
| Auth | A valid `authToken` for a known CHW; same CHW will be tagged on every event |
| Logcat filter | Pre-set: `tag:GapRuleDispatcher tag:WrongFacilityTierEval tag:EventRecorder tag:SyncApi tag:VisitCompletedHandler tag:MicroCoachingSDK tag:AssessmentEntityExt tag:ReferralResolver` |
| Room inspector | Android Studio's App Inspection → Database Inspector connected to the SPICE process, looking at `microcoaching.db` |
| Backend logs | Tail `coaching-platform` logs on `POST /telemetry/events` and `GET /morning/cards` |

---

## §B. The real SPICE 2.0 navigation

Confirmed by reading the SPICE codebase from `SpiceBaseApplication.kt` outward. The home screen shows tiles **HOUSEHOLDS · DASHBOARD · SERVICE RECIPIENT · MY PATIENTS · COACHING** ([HomeScreenFragment.kt:285–446](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/home/HomeScreenFragment.kt)). Two entry points converge on the same assessment path:

```
HOUSEHOLDS                        SERVICE RECIPIENT
    │                                   │
    ▼                                   ▼
HouseholdSearchActivity           ServicesActivity
    │                                   │
    ▼                                   │
HouseholdSummaryActivity                │
    │  (member list)                    │
    ▼                                   ▼
       MemberSummaryActivity  ◄─────────┘
                │
                ▼  (FAB "Services" tap)
       AssessmentToolsActivity
                │
                ▼  (CHW picks "NCD")
       ToolsMenuFragment.startAssessmentActivity()
                │
                ▼
       AssessmentActivity              ← form filled here
                │
                ▼  (submit → assessmentSaveLiveData → SUCCESS)
       AssessmentActivity.notifyMicroCoachingSDK()   line 832
                │
                ▼
       MicroCoachingSDK.onAssessmentSubmitted()      ← the hook fires here
```

Key file:line references:

- Home tile rendering — [HomeScreenFragment.kt:285–309](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/home/HomeScreenFragment.kt#L285)
- Tile routing — [HomeScreenFragment.kt:312–446](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/home/HomeScreenFragment.kt#L312)
- HOUSEHOLDS → HouseholdSearch → HouseholdSummary — [HouseholdSearchActivity.kt:84–87](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/household/HouseholdSearchActivity.kt#L84)
- HouseholdSummary → MemberSummary — [HouseholdSummaryActivity.kt:243–247](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/household/summary/HouseholdSummaryActivity.kt#L243)
- MemberSummary → AssessmentTools (the "Services" FAB) — [MemberSummaryActivity.kt:134](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/household/summary/MemberSummaryActivity.kt#L134)
- SERVICE RECIPIENT → MemberSummary — [ServicesActivity.kt:336](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/services/ServicesActivity.kt#L336)
- AssessmentTools → AssessmentActivity — [ToolsMenuFragment.kt:233–250](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/home/ToolsMenuFragment.kt#L233)
- SDK hook call site — [AssessmentActivity.kt:854–861](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/assessment/AssessmentActivity.kt#L854) (also [CbsActivity.kt:179](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/cbs/activity/CbsActivity.kt#L179) for CBS pathway)

`MY PATIENTS` is a longitudinal follow-up surface, not used in this test.

---

## §C. What's actually in the SDK `assessmentData` map

This is the corrected reality after reading [AssessmentEntityExt.kt](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/microcoaching/AssessmentEntityExt.kt). With this branch's small SPICE-side patch applied, the SDK receives:

| Key | Source | Always present? |
|---|---|---|
| `patient_id` | `AssessmentEntity.patientId` | when non-null |
| `village_id` | `AssessmentEntity.villageId` | ✓ |
| `upazila_id` | resolved by caller via `VillageEntity.chiefdomId` | when resolvable |
| `assessment_type` | `AssessmentEntity.assessmentType` (e.g. `"NCD"`) | ✓ |
| `is_referred` | `AssessmentEntity.isReferred` | ✓ |
| `referral_status` | `AssessmentEntity.referralStatus.name` | ✓ |
| `referred_reason` | comma-joined `AssessmentEntity.referredReason` (medical reasons: `"Blood Pressure,Blood Glucose"`) | when non-empty |
| `system_referral_status` | `AssessmentViewModel.referralStatus` | when referral triggered |
| `system_referral_reasons` | comma-joined `AssessmentViewModel.referralReason` (medical reasons, same shape as above) | when referral triggered |
| **`referralFacilityType`** | parsed out of `AssessmentEntity.assessmentDetails` JSON — value is the expected tier `"Upazila Health Complex"` or `"Community Clinic"` written by `ReferralResultGenerator` | when referral triggered |
| `childReferralFacilityType` | same source, paediatric pathway | only paediatric |
| **`picked_facility_type`** | parsed from `assessmentDetails["pickedFacilityType"]` — Path A. **SDK reader is in place; SPICE-side writer is not yet wired** (see §F.2). Stays absent until SPICE captures the picker tier. | not yet — see §F.2 |

**Keys NOT in the SDK map today** (the SDK previously assumed they would be):

- `referred_site_id` — the picked facility's UUID lives in `ReferPatientResult.referredSiteId` ([ReferPatientResult.kt](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/data/ReferPatientResult.kt)) but is not surfaced into `toSdkAssessmentMap`. Optional — only used for hashing into evidence; the rule fires without it.
- `bpLog`, `cvdRiskLevel`, `fbsBloodGlucose`, `dangerSigns`, …: none of these flow into the SDK map today. Each future evaluator (BP threshold, glucose threshold, danger signs) will need its own forward in `AssessmentEntityExt` — see §F.3.

The remaining blocker for the flagship `wrong_facility_tier` rule is **`picked_facility_type`** — see §F.2.

---

## §D. Seed the gap rule on the backend

The SDK consumes `BehaviouralGapSyncPayload` rows from `/sync/gaps`. Before testing, publish exactly one rule for the flagship gap:

```sql
UPDATE behavioural_gap
SET detection_rule_jsonb = '{
  "schema_version": 1,
  "rule_type": "wrong_facility_tier",
  "params": {},
  "match": {
    "spice_event_codes": ["assessment_submitted", "NCDAssessmentCreation"],
    "assessment_types": ["NCD"]
  }
}'::jsonb
WHERE gap_code = 'incorrect_referral_destination';
```

Confirm with `SELECT gap_code, detection_rule_jsonb FROM behavioural_gap WHERE gap_code = 'incorrect_referral_destination';`.

---

## §E. Step-by-step test

### E.1 Start the app and observe initial sync

**Action**: cold-start SPICE → log in as the test CHW → land on home (HOUSEHOLDS / DASHBOARD / SERVICE RECIPIENT / MY PATIENTS / COACHING tiles).

**Expected logcat** (sync worker fires within 5–15 seconds):

```
I/MicroCoachingSDK : onHomeScreenShown called — chwId=<id>
I/SyncApi          : Gaps sync OK: gaps=N (with_rules=1) states=… completions=… server_time=…
I/SyncApi          : Morning cards sync OK: items=K gap=…
```

`with_rules=1` confirms the rule envelope arrived. If it's `0`, the backend seed didn't publish properly — fix before continuing.

**Expected Room state**:

| Table | Row to find | Why |
|---|---|---|
| `behavioural_gap_cache` | `gap_code='incorrect_referral_destination'`, `detection_rule IS NOT NULL` | Rule arrived locally |

Note: there is **no** `facility_cache` table — facility sync was removed when we chose Path A. The picked tier comes through as a string in the SDK map (`picked_facility_type`), not via a local lookup.

### E.2 Navigate HOUSEHOLDS → MemberSummary → Services → Assessment

**Action**:
1. Tap **HOUSEHOLDS** on home.
2. Search for or open the test household.
3. Open the test member.
4. Tap the FAB / **Services** → `AssessmentToolsActivity` opens.
5. Pick **NCD** → assessment form opens.

(`SERVICE RECIPIENT` is an alternative entry — pick a registered service recipient and skip to step 4. Either path is valid.)

### E.3 Fill the assessment to trigger Upazila escalation

Enter values that push BP above the Upazila threshold (`UPAZILA_UPPER_LIMIT_SYSTOLIC=160`, `UPAZILA_UPPER_LIMIT_DIASTOLIC=100` per [AssessmentDefinedParams.kt:526–527](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/assessment/AssessmentDefinedParams.kt#L526)):

| Field | Value |
|---|---|
| Age | 45 |
| Sex | Female |
| BP — systolic | **165** |
| BP — diastolic | **105** |
| BMI | 26 |
| RBS / FBS | normal (e.g. RBS 6.0 mmol/L) |
| Phq-4 | low |

**Expected SPICE behaviour** (driven by [ReferralResultGenerator.computeReferralResultForBDNCD line 668–684](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/assessment/referrallogic/ReferralResultGenerator.kt#L668)):

- BP is over both thresholds → `referredReasonList += "Blood Pressure"`.
- BP is over Upazila escalation → `assessmentDetails["referralFacilityType"] = "Upazila Health Complex"`.
- `referralStatus = "Referred"`.
- SPICE prompts the referral screen to pick a facility — **pick one tagged Community Clinic** (intentional mismatch).
- Confirm referral → `is_referred = true`, `referredReason = ["Blood Pressure"]`, `referredSiteId = "<community-clinic-uuid>"`.

### E.4 Submit and inspect logs

`assessmentSaveLiveData.SUCCESS` fires → [`notifyMicroCoachingSDK(...)`](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/assessment/AssessmentActivity.kt#L832) builds the SDK map and calls `onAssessmentSubmitted`.

**Expected logcat sequence**:

```
D/AssessmentEntityExt : toSdkAssessmentMap keys=[patient_id, village_id, upazila_id,
                        assessment_type, is_referred, referral_status,
                        referred_reason, system_referral_status,
                        system_referral_reasons, referralFacilityType]
I/MicroCoachingSDK    : onAssessmentSubmitted — encounterId='' assessmentKeys=[…]
D/ReferralResolver    : path=A(real) systemStatus=Referred shouldRefer=true isReferred=true
                        systemLocation=null actualLocation=null …
                        → correctReferral=true location=true type=true
D/WrongFacilityTierEval : Skip incorrect_referral_destination: no picked_facility_type
                          in assessmentData (SPICE-side picker capture not yet wired —
                          see GAPS_TEST.md §F.2)
D/GapRuleDispatcher   : Gap dispatch: 0/1 fired — spice_event=assessment_submitted
                        assessment=NCD registered=[wrong_facility_tier, …]
D/EventRecorder       : [sdk-hook] spice_action_observed saved — outcome=correct rule=null gap=null
                        (fallback emission — no rule fired)
```

**This is the current expected outcome on stock SPICE 2.0 — see §F item 2.** The flagship rule does not fire because SPICE has surfaced the *expected* tier but not the *picked* tier; the evaluator skips with a precise diagnostic log instead of false-firing or crashing.

### E.5 Forced-fire mode (until §F.2 SPICE wiring lands)

To demonstrate that the rest of the loop works, stage `pickedFacilityType` into the assessment JSON for the test session. Two ways:

**Option A — inject into `assessmentDetails` at save time (recommended)**. In `AssessmentViewModel.getAssessmentDetails` ([line ~1040](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/assessment/viewmodel/AssessmentViewModel.kt#L1040)), before `convertGivenMapToString`, drop in a test-only line:

```kotlin
// TEST-ONLY — remove before merging
map["pickedFacilityType"] = "Community Clinic"
```

`AssessmentEntityExt.extractPickedFacilityTierFromDetails` will pick it up and forward as `picked_facility_type`. Re-run §E.3–E.4. Logs should flip to:

```
I/WrongFacilityTierEval : incorrect_referral_destination FIRED —
                          expected=Upazila Health Complex actual=Community Clinic
I/GapRuleDispatcher     : Gap dispatch: 1/1 fired
                          (incorrect_referral_destination=incorrect)
D/EventRecorder         : [sdk-hook] spice_action_observed saved —
                          outcome=incorrect rule=wrong_facility_tier gap=<uuid>
```

**Option B — backend stub events**. Skip the SPICE-side path entirely and `curl POST /telemetry/events` with a hand-built payload tagged with `behavioural_gap_id`. Useful for backend-only verification but doesn't exercise the SDK rule path.

### E.6 Verify Room rows

Database Inspector → `coaching_event` table. The most recent row from a fired-gap run looks like:

| Column | Expected |
|---|---|
| `event_type` | `spice_action_observed` |
| `event_family` | `clinical_observed` |
| `outcome` | `incorrect` |
| `behavioural_gap_id` | UUID of `incorrect_referral_destination` |
| `payload_json` | JSON containing `rule_type:"wrong_facility_tier"`, `evidence.expected_tier`, `evidence.actual_tier`, `evidence.facility_id_hash` |
| `patient_id_hash` | SHA-256 hex (never the raw id) |
| `patient_visit_id` | `NULL` until visit close — see §E.8 (BUG-5) |
| `session_id` | `sdk-hook` |
| `sync_status` | `pending` → `synced` after the worker runs |

A second row appears for `risk_flag_observed` (BP HIGH).

### E.7 Verify backend round-trip

`flushTelemetryNow()` schedules a one-shot outbound sync.

**SDK log**:

```
I/SyncApi            : Pushing pending events — coaching_events=2 …
I/OutboundSyncWorker : Outbound sync OK: synced=N rejected=0
```

**Backend** (`coaching-platform`):

```
INFO telemetry: accepted event_type=spice_action_observed family=clinical_observed
               chw_id=… behavioural_gap_id=<uuid> outcome=incorrect
               rule_type=wrong_facility_tier
```

**Postgres**:

```sql
SELECT occurrence_count, failed_attempts_count, last_observed_at
FROM   chw_behavioural_gap_state
WHERE  chw_id = '<test chw>'
  AND  behavioural_gap_id = (SELECT id FROM behavioural_gap
                              WHERE gap_code = 'incorrect_referral_destination');
```

Both counts increment, `last_observed_at ≈ now`.

### E.8 Close the visit (`onVisitCompleted`)

SPICE does **not** currently call this. Trigger manually for the test:

```kotlin
MicroCoachingSDK.getInstance().onVisitCompleted("visit-test-1")
```

**Expected logs**:

```
I/VisitCompletedHandler : Visit close: backfilled patient_visit_id on N event(s)
                          for chw=… visit=visit-test-1
D/EventRecorder         : [sdk-hook] session_end saved
```

**Room**: the previously-`NULL` `patient_visit_id` on pending `sdk-hook` rows now equals `"visit-test-1"`. A new `session_end` row appears.

### E.9 Wait for next morning-card refresh

Open home again (`onHomeScreenShown`) or call `onMorningOpen()`.

**Expected**:

```
I/SyncApi          : Morning cards sync OK: items=N gap=1
I/MicroCoachingSDK : Morning cards refreshed: N items (gap=1)
```

Inspect `morning_card_cache`: the top row should have `source='gap'` and `behavioural_gap_id` matching the fired gap. Top card on the home banner should be the corrective module mapped to `incorrect_referral_destination`.

### E.10 Negative-path test (no gap fires)

Repeat §E.3 but pick an **Upazila Health Complex** facility (matching SPICE's expected tier). Logs should show:

```
D/WrongFacilityTierEval : incorrect_referral_destination: tier match —
                          expected=Upazila Health Complex actual=Upazila Health Complex (no signal)
D/GapRuleDispatcher     : Gap dispatch: 0/1 fired
D/EventRecorder         : [sdk-hook] spice_action_observed saved — outcome=correct rule=null gap=null
```

No gap row in `chw_behavioural_gap_state`. No corrective morning card on next refresh.

---

## §F. Findings flagged by this code search

### F.1 SPICE-side patches shipped on this branch — `AssessmentEntityExt.kt`

`ReferralResultGenerator` writes the *expected* facility tier into `assessmentDetails` (JSON column on `AssessmentEntity`), but the original `toSdkAssessmentMap()` didn't surface it to the SDK. This branch adds two parse-and-forward steps to [AssessmentEntityExt.kt](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/microcoaching/AssessmentEntityExt.kt):

1. **`referralFacilityType`** (or `childReferralFacilityType` on paeds) — parsed out of `assessmentDetails` JSON, forwarded under the same key. This is the *expected* tier.
2. **`picked_facility_type`** — parsed from `assessmentDetails["pickedFacilityType"]` when present. **Reader is in place; SPICE-side writer is not yet wired** — see F.2.
3. A `Log.d("AssessmentEntityExt", "toSdkAssessmentMap keys=…")` so the test plan can verify the map contents without a debugger.

No other SPICE behaviour changes. Existing keys are untouched.

### F.2 Path A — the remaining SPICE wiring

The SDK is **Path A ready**: `WrongFacilityTierEvaluator` reads `picked_facility_type` straight from the assessment map and compares against `referralFacilityType`. No DAO, no facility cache, no lookup.

The remaining work is **SPICE-side**: at the referral-picker confirmation, capture the tier of the facility the CHW selected and write it into `assessmentDetails["pickedFacilityType"]`. Once that lands, the SDK fires without any further SDK change.

What still has to happen on SPICE (out of scope for this PR — separate ticket):

| Step | File | Snippet |
|---|---|---|
| 1. Capture tier at picker | [ReferPatientFragment.kt:251–264](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/mypatients/fragment/ReferPatientFragment.kt) — spinner `onItemSelected` | `viewModel.referToSelectedTier = …` (source TBD — see §F.2.a) |
| 2. Hold it on the viewmodel | [ReferPatientViewModel.kt:22–28](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/mypatients/viewmodel/ReferPatientViewModel.kt) | `var referToSelectedTier: String? = null` |
| 3. Inject into `assessmentDetails` at assessment save | [AssessmentViewModel.kt:1040](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/ui/assessment/viewmodel/AssessmentViewModel.kt) — before `convertGivenMapToString` | `referToSelectedTier?.let { map["pickedFacilityType"] = it }` |

**§F.2.a — where does the picker get the tier from?** This is the real open question. SPICE's facility list (`/admin-service/healthfacilities-by-district-id`) currently returns rows without a `type` / `facilityType` / `level` field, and `HealthFacilityEntity` ([db/entity/HealthFacilityEntity.kt](../../../spice-2.0-android/Spice-SL/app/src/main/java/org/medtroniclabs/uhis/db/entity/HealthFacilityEntity.kt)) has no tier column. The picker UI shows facilities as a flat list with no tier grouping. Two ways forward:

- **Backend adds a tier field** to the facility API response and the SPICE-Android sync layer hydrates it onto `HealthFacilityEntity`. Then `ReferPatientHealthFacilityItem` gains a `facilityType: String?` and the picker emits it on selection. Smallest functional change, but it's a backend ticket.
- **Naming-convention inference**: many BD facilities follow naming patterns like "CHC X" vs "CC Y". Brittle and not recommended.

Recommendation: ship the SPICE wiring scaffold on a follow-up branch (steps 1–3 above), file the backend ticket for the tier field, then re-run this test plan for a real fire when both land.

Until that's done, the SDK skips with a precise diagnostic log:

```
D/WrongFacilityTierEval : Skip incorrect_referral_destination: no picked_facility_type
                          in assessmentData (SPICE-side picker capture not yet wired —
                          see GAPS_TEST.md §F.2)
```

### F.3 SDK `assessmentData` map is narrower than `GAP_DETECTION_SDK.md` assumed

The design doc described readouts like `bpLog.avgSystolic`, `rbsBloodGlucose`, `dangerSigns`. Reality (after F.1):

| Key from design doc | In SDK map today? | Where it actually lives in SPICE |
|---|---|---|
| `referralFacilityType` | ✅ (after F.1 patch) | `assessmentDetails` JSON |
| `picked_facility_type` | ⏳ (SDK reader in place; SPICE writer pending — see F.2) | needs to be written under `assessmentDetails["pickedFacilityType"]` |
| `referred_site_id` | ❌ | `ReferPatientResult.referredSiteId` (optional — only used for evidence hash) |
| `bpLog.avgSystolic` / `avgDiastolic` | ❌ | `assessmentDetails["bpLog"]` JSON |
| `rbsBloodGlucose` / `fbsBloodGlucose` | ❌ | `assessmentDetails` JSON |
| `dangerSigns` | ❌ | `assessmentDetails` JSON, key TBC (C-SDK-2) |
| `isReferred` | ✅ as `is_referred` | `AssessmentEntity.isReferred` |
| `cvdRiskLevel` | ❌ | `assessmentDetails` JSON |

The pattern is consistent: SPICE has rich clinical data in `assessmentDetails` but `toSdkAssessmentMap` is conservative. Each new evaluator (BP threshold, glucose threshold, danger signs) will need a corresponding addition to `toSdkAssessmentMap` — either as an explicit forwarded key, or by parsing the JSON column. The F.1 pattern (parse the JSON column) is the template.

### F.4 BUG-5 (`encounterId=""`) and missing `onVisitCompleted` call site

Already documented in [28_05_status.md](./28_05_status.md). Test plan §E.8 manually triggers `onVisitCompleted` to demonstrate the backfill works; SPICE needs to add the call at the natural close-visit point.

---

## §G. One-page sanity checklist

Use this during the test run. Each item is a single grep / SQL query.

- [ ] `logcat | grep "with_rules="` shows ≥ 1
- [ ] `SELECT gap_code FROM behavioural_gap_cache WHERE detection_rule IS NOT NULL` returns the test gap
- [ ] After §E.4: `logcat | grep "toSdkAssessmentMap keys"` includes `referralFacilityType`
- [ ] After §E.4 on stock SPICE: `logcat | grep "Skip incorrect_referral_destination"` shows the `no picked_facility_type …` diagnostic (expected until §F.2 SPICE wiring lands)
- [ ] After §E.5 forced-fire: `logcat | grep "incorrect_referral_destination FIRED"` shows expected vs actual
- [ ] `SELECT outcome, behavioural_gap_id FROM coaching_event ORDER BY timestamp_local DESC LIMIT 1` — `incorrect`, gap UUID present (after forced-fire)
- [ ] Backend ClickHouse increments `coaching_events`
- [ ] Backend Postgres increments `chw_behavioural_gap_state.occurrence_count`
- [ ] After §E.8: `logcat | grep "Visit close: backfilled patient_visit_id"` non-zero
- [ ] After §E.9: top morning card on home is the corrective module mapped to `incorrect_referral_destination`

If every box is checked under §E.5 forced-fire mode, the loop works end-to-end and the only remaining gate is the F.2 SPICE-side patch for a stock-SPICE fire.
