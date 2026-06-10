> ⚠️ **Schema note.** The walkthrough below uses the legacy
> `rule_type: "wrong_facility_tier"` envelope, which is **superseded** by the
> `spice_referral_compliance` schema — see
> [DETECTION_RULE_SCHEMA.md](./DETECTION_RULE_SCHEMA.md) and
> [GAP_CATALOG.md](./GAP_CATALOG.md). The end-to-end **loop** it illustrates
> (gap → tagged `spice_action_observed` → backend count → morning card) is still
> accurate; only the rule shape and `WrongFacilityTierEvaluator` example are
> outdated. The current evaluator is `SpiceReferralComplianceEvaluator`.

## Who does what

| Actor | Role | Authoritative on |
| --- | --- | --- |
| **SPICE** | Records the clinical workflow (assessment values, referral choice). | The raw clinical data (`assessmentDetails` JSON, `is_referred`, `referredSiteId`, the picked-facility tier once §F.2 lands). |
| **SDK** | Evaluates rules **at the moment of action**, emits one tagged telemetry event per fired gap. **Does not pick modules.** | The `behavioural_gap_id` tag on each `spice_action_observed` event. |
| **Backend** | Counts tagged events per `(chw_id, behavioural_gap_id)`. Ranks modules for morning cards using that count + the `module.primary_gap_id` link. | The morning-card ranking and which corrective module surfaces for which CHW. |

The match is just two foreign keys: `coaching_event.behavioural_gap_id == behavioural_gap.id` (counts the gap), and `module.primary_gap_id == behavioural_gap.id` (links a module to the gap it teaches).

---

## Happy-path: CHW makes a wrong referral → next morning sees the corrective module

### Step 1 — CHW fills the assessment (SPICE owns)

CHW enters: BP 165/105, FBS 6.0 mmol/L, age 45 F.

`ReferralResultGenerator.computeReferralResultForBDNCD` runs → writes into the `assessmentDetails` JSON column:

`{
  "bpLog": { "avgSystolic": 165, "avgDiastolic": 105 },
  "referralFacilityType": "Upazila Health Complex",    // <-- expected tier
  "pickedFacilityType":   "Community Clinic"           // <-- after §F.2 wiring
}`

…and on the `AssessmentEntity` row: `isReferred = true`, `referredReason = ["Blood Pressure"]`, plus `ReferPatientResult.referredSiteId = "<community-clinic-uuid>"`.

CHW submits.

### Step 2 — SPICE hands the data to the SDK

`AssessmentActivity.notifyMicroCoachingSDK()` builds the SDK map via `AssessmentEntityExt.toSdkAssessmentMap`:

`MicroCoachingSDK.onAssessmentSubmitted(
  encounterId = "",
  patientId   = "<raw>",
  assessmentData = mapOf(
    "assessment_type"         to "NCD",
    "is_referred"             to true,
    "referral_status"         to "Referred",
    "referred_reason"         to "Blood Pressure",
    "system_referral_status"  to "Referred",
    "system_referral_reasons" to "Blood Pressure",
    "referralFacilityType"    to "Upazila Health Complex",
    "picked_facility_type"    to "Community Clinic",
    ...
  ),
)`

### Step 3 — SDK dispatcher runs rules

`GapRuleDispatcher.evaluate(...)` loads active gaps with a `detection_rule` and dispatches on `rule_type`. For each gap:

- Loads gap row: `gap_code="incorrect_referral_destination"`, `behavioural_gap_id="11111-…"`, rule is `wrong_facility_tier`.
- `WrongFacilityTierEvaluator.evaluate(...)` runs:
    - expected = `"Upazila Health Complex"` (from `referralFacilityType`)
    - actual = `"Community Clinic"` (from `picked_facility_type`)
    - mismatch → fires with `outcome="incorrect"`, evidence `{expected_tier, actual_tier, facility_id_hash}`.

### Step 4 — SDK emits one tagged event

`EventRecorder.recordSpiceActionObserved(...)` writes a row into `coaching_event`:

`{
  "event_type": "spice_action_observed",
  "event_family": "clinical_observed",
  "behavioural_gap_id": "11111-…",      // ← THE TAG
  "outcome": "incorrect",
  "patient_id_hash": "<sha256>",
  "payload_json": {
    "rule_type": "wrong_facility_tier",
    "evidence": {
      "expected_tier": "Upazila Health Complex",
      "actual_tier":   "Community Clinic",
      "facility_id_hash": "<sha256>"
    }
  }
}`

`flushTelemetryNow()` pushes it to `POST /telemetry/events` within seconds.

### Step 5 — Backend counts (no rule evaluation here)

Backend `module_completion_worker` ingests the event, sees `behavioural_gap_id="11111-…"`, runs essentially:

`UPDATE chw_behavioural_gap_state
SET occurrence_count = occurrence_count + 1,
    failed_attempts_count = failed_attempts_count + 1,  -- because outcome=incorrect
    last_observed_at = now()
WHERE chw_id = <id> AND behavioural_gap_id = '11111-…';`

That's the entire backend logic — count, increment, never re-evaluate the rule.

### Step 6 — Backend picks the corrective module

When the SDK next pulls `GET /morning/cards/<chw_id>`, the backend's `ModuleSuggestionService` runs roughly:

- 
    
    ```sql
    -- For each gap this CHW has occurrences of, find the module that teaches it
    SELECT m.id, m.module_family_id, s.behavioural_gap_id, s.occurrence_count
    FROM   chw_behavioural_gap_state s
    JOIN   module m ON m.primary_gap_id = s.behavioural_gap_id   -- ← THE LINK
    WHERE  s.chw_id = <id>
      AND  s.occurrence_count >= threshold
    ORDER BY s.failed_attempts_count DESC, s.last_observed_at DESC;
    ```
    

For our CHW that returns the module with `primary_gap_id = "11111-…"` — the "Referral destinations: NCD escalation" module (or whatever the admin authored against that gap).

### Step 7 — SDK shows it as a morning card

`SyncApi.pullMorningCards` writes the result into `morning_card_cache`:

| module_id | source | behavioural_gap_id | rank |
| --- | --- | --- | --- |
| `mod-789` | `gap` | `11111-…` | 0 |

`HomeScreenFragment`'s morning banner renders the top-ranked row → CHW sees a card titled something like *"Refer hypertensive patients to Upazila when systolic ≥ 160"* with a Start button. They tap Start, work through cards + quiz, the SDK emits `module_quiz_attempted`/`module_completed`, and the backend marks the gap as reinforced.

---

## The two negative paths (for symmetry)

**Correct referral**: CHW picks Upazila Health Complex (matches). `WrongFacilityTierEvaluator` returns `null`. Dispatcher emits **0 fired**. The SDK falls back to a single generic `spice_action_observed` row with `outcome="correct"` and `behavioural_gap_id=NULL`. Backend has nothing to count against any gap. No morning-card change.

**Backend with no rule for this gap** (e.g. `detection_rule_jsonb={}` — quiz-only gap): `getActiveWithRules()` filters it out, dispatcher skips it. The gap can still get counted via the quiz path (`module_quiz_attempted` outcome=wrong), but never from the action path.

---

## The matching diagram in one line

`gap_code (string)
    ↓ stable lookup in behavioural_gap table
behavioural_gap.id  (UUID)  ─────────────────────────────────────────────────────────┐
    ↓ tagged on SDK telemetry                ↓ FK from module.primary_gap_id          │
coaching_event.behavioural_gap_id            module.primary_gap_id ──→ module surfaced
    ↓ counted by backend
chw_behavioural_gap_state.occurrence_count ──→ ranks the morning card`

Two foreign keys — `coaching_event.behavioural_gap_id` (the count) and `module.primary_gap_id` (the link) — both pointing at `behavioural_gap.id`. That's the whole mechanism.