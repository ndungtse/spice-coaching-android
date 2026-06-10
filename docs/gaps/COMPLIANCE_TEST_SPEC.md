# `spice_referral_compliance` — test spec & alignment

Final test spec for the **compliance** detection schema (`evaluator:
"spice_referral_compliance"` + `when` tree) — the shape the v3 backend actually
ships (`ignored/v3/gaps.json`, `behavioural_gap.json`). Supersedes the old
`rule_type` / `wrong_facility_tier` approach, which was reverted (v3 has no
facility-tier gap).

**Companion:** [DETECTION_RULE_SCHEMA.md](./DETECTION_RULE_SCHEMA.md) (the schema).

---

## 1. What's aligned now (SDK)

The SDK parses and evaluates the compliance schema end-to-end:

| Piece | File | Status |
|---|---|---|
| Envelope parse (`evaluator`/`when`/`metadata`, `rule_type` now optional) | [DetectionRuleEnvelope.kt](../../sdk-android/src/main/java/com/medtroniclabs/microcoaching/domain/gaps/DetectionRuleEnvelope.kt) | ✅ |
| `when`-tree interpreter + all operators | [SpiceReferralComplianceEvaluator.kt](../../sdk-android/src/main/java/com/medtroniclabs/microcoaching/domain/gaps/SpiceReferralComplianceEvaluator.kt) | ✅ |
| Dispatcher routing (`isCompliance` → interpreter; legacy `rule_type` still works) | [GapRuleDispatcher.kt](../../sdk-android/src/main/java/com/medtroniclabs/microcoaching/domain/gaps/GapRuleDispatcher.kt) | ✅ |
| Unit tests (operators + the real `referral_anc_emergency_acute` tree) | [SpiceReferralComplianceEvaluatorTest.kt](../../sdk-android/src/test/java/com/medtroniclabs/microcoaching/domain/gaps/SpiceReferralComplianceEvaluatorTest.kt) | ✅ green |

**Parsing flow:** `/sync/gaps` → `detection_rule_jsonb` stored as a string →
`DetectionRuleEnvelope.parseOrNull` → if `evaluator == "spice_referral_compliance"`
(`isCompliance`), `GapRuleDispatcher` routes to `SpiceReferralComplianceEvaluator`,
which walks `when` against the compliance state and returns a
`GapDetectionResult(outcome="incorrect")` when the tree is true. Evidence is the
rule's `metadata` only — never patient clinical data.

Operators implemented: `and`, `or`, `not`, `eq`, `neq`, `exists`, `contains_any`,
`contains_all`, `array_nonempty`, `map_key_nonempty`, `array_contains_substring`,
`missed_referral`, `mismatch_eq`, `mismatch_contains_any`, `mismatch_urgency`.
Unknown ops / malformed nodes → **false** (a rule we can't parse never fires).

---

## 2. The compliance state contract (the remaining SPICE work)

The interpreter resolves dot-paths over a state map with two branches that
**SPICE must assemble** and pass as the hook's `assessmentData`:

```
{
  "recommended": {            // rule-engine output (RMNCH ReferralResultGenerator)
    "isReferred": true,
    "referredReason": ["High risk pregnant woman", ...],
    "assessmentDetails": { "anc": { "summary": {
        "highRiskPregnantWoman": { "URGENT": ["High Fever", ...], "NON_URGENT": [...] },
        "gapsInAnc": [...] } } }
  },
  "actual": {                 // what the CHW did at referral
    "didRefer": false,
    "referralReasons": [...],
    "isUrgent": false
  }
}
```

Missing paths resolve to null → the relevant operator simply doesn't fire
(fail-safe). This is the integration gap: the current SPICE hook sends a **flat**
map, not this `{recommended, actual}` structure.

### Data-availability matrix (v3 gaps)

The v3 seed ships **45** `spice_referral_compliance` gaps across ANC, PNC, ICCM,
NCD, TB, CBS, family-planning and location/tier (see
[GAP_CATALOG.md](./GAP_CATALOG.md)). They use `contains_any`, `missed_referral`,
`mismatch_contains_any`, `mismatch_urgency`, `mismatch_eq`, `eq`, `map_key_nonempty`,
`array_contains_substring`. Their data needs reduce to a few state paths:

| State path | SPICE source | Available? |
|---|---|---|
| `recommended.assessmentDetails.anc.summary.*` | RMNCH `calculateRMNCHReferralResult` → `assessmentDetails` JSON | ✅ (assemble from assessment) |
| `recommended.referredReason`, `recommended.isReferred` | assessment referral result | ✅ |
| `actual.didRefer` | referral submission (or absence by visit close) | ⚠️ inferable |
| `actual.destinationTier` | picked facility's `HealthFacilityEntity.type` (synced from the metadata API; captured at the PHU picker) → `referral_location_*` gaps | ✅ captured (⚠️ vocab — see below) |
| `actual.referralReasons` (structured) | SPICE captures **free-text** `enteredReferredReason` only | ❌ not structured |
| `actual.isUrgent` | **not captured** by SPICE referral UI | ❌ |

**Consequence:** the `referral_location_*` (tier) gaps are now viable — their
`mismatch_eq(actual.destinationTier, recommended.referralFacilityType)` branch
fires when the CHW picks a different tier than recommended (BD NCD). `missed_referral`
is viable at visit close. `mismatch_contains_any` (on `actual.referralReasons`) and
`mismatch_urgency` (on `actual.isUrgent`) remain **blocked** until SPICE captures
structured reasons + an urgency flag. The evaluator handles all of them; the data is
the constraint.

**⚠️ Vocabulary alignment (required for the tier gaps to be correct).**
`actual.destinationTier` is the raw `health_facility.type`; the gaps compare it by
**exact `mismatch_eq`** against `recommended.referralFacilityType` (the app constants
`"Upazila Health Complex"` / `"Community Clinic"`). If the BD `health_facility.type`
values differ from those strings, every referral would mis-fire as "wrong tier".
Confirm the BD catalog values match (or add a normalisation step) before relying on
these gaps. Until a facility's `type` is synced it's blank → omitted → fail-safe
no-fire (no false positive).

**Fail-safe on absent actual (important).** The `mismatch_*` operators fire only
when the **actual** operand is *present* and differs. An **absent** actual value
→ no fire. This is a deliberate divergence from the schema's literal "differ
including one null": in this integration a null actual means *uncaptured*, not
"CHW omitted it", so firing on absent would be a **false positive** on a
correctly-handled referral. Net effect at `onReferralSubmitted` today: with
`actual.isUrgent`/`referralReasons`/`destinationTier` uncaptured (and `didRefer`
true at commit), the referral hook fires **no** gap — correctly, not spuriously —
until that actual-side data exists.

---

## 3. Unit test spec (done — `SpiceReferralComplianceEvaluatorTest`)

- Each operator in isolation: `contains_any`, `missed_referral`,
  `mismatch_contains_any`, `mismatch_urgency`, `not`/`exists`/`array_nonempty`/`map_key_nonempty`.
- Fail-safe: unknown op → no fire; envelope with no `when` → null.
- **Real v3 gap** (`referral_anc_emergency_acute`, the exact `when` tree from the
  seed) parsed via `DetectionRuleEnvelope.parseOrNull`, asserting:
  - fires when urgent ANC referral recommended but **not made** (`missed_referral`);
  - fires on **urgency mismatch** even when the CHW referred;
  - does **not** fire when the CHW referred urgently with matching reasons;
  - does **not** fire when no urgent condition was recommended (precondition false).

Run: `./gradlew :sdk-android:testDebugUnitTest --tests "com.medtroniclabs.microcoaching.domain.gaps.*"`

---

## 4. End-to-end test (pending SPICE state assembly)

Once SPICE assembles `{recommended, actual}` and calls the SDK hook:

1. The 45 compliance gaps already sync in the v3 shape (no change).
   Confirm `/sync/gaps` returns them and `with_rules ≥ 1`.
2. RMNCH ANC assessment with an URGENT condition (e.g. "High Fever") recommending
   referral; CHW does **not** refer → at visit close, hook fires with
   `recommended.isReferred=true`, `actual.didRefer=false`.
3. Expect: `ComplianceEval: referral_anc_emergency_acute FIRED` and one gap-tagged
   `spice_action_observed` (`outcome=incorrect`).
4. Negative: CHW refers urgently with matching reasons → no fire.
5. Backend `chw_behavioural_gap_state.occurrence_count` increments; corrective
   morning card surfaces.

---

## 5. Status summary

- ✅ **SDK**: parses + evaluates the compliance schema for all 45 v3 gaps; the
  real v3 ANC gap (`referral_anc_emergency_acute`) is covered by passing unit tests.
- ⏳ **SPICE**: must assemble the `{recommended, actual}` state for the RMNCH
  pathway and pass it to the hook. `actual.isUrgent` + structured
  `actual.referralReasons` are not captured today → `missed_referral` is the
  first end-to-end-viable gap; urgency/reason mismatches need SPICE capture.
- ↩️ **Reverted**: the `wrong_facility_tier` (`rule_type`) work in both repos —
  v3 has no facility-tier gap. `DETECTION_RULE_SCHEMA.md` was kept.
