# Gap catalog — what the SDK detects vs. what SPICE measures

What behavioural gaps the SDK can detect today, how that maps onto SPICE's
assessments, and where we could expand. The detection mechanism is the
**`spice_referral_compliance`** schema — see
[DETECTION_RULE_SCHEMA.md](./DETECTION_RULE_SCHEMA.md) for the rule format and
[COMPLIANCE_TEST_SPEC.md](./COMPLIANCE_TEST_SPEC.md) for the test plan. (The older
`rule_type` / `wrong_facility_tier` *evaluator* was reverted in favour of this one
generic evaluator — which **subsumes** facility tier: v3 *does* ship facility-tier
gaps, as `spice_referral_compliance` "location" rules — see §2.)

---

## 1. How the SDK detects gaps

Each `behavioural_gap.detection_rule_jsonb` is a `spice_referral_compliance`
envelope with a `when` predicate tree. The SDK
([SpiceReferralComplianceEvaluator](../../sdk-android/src/main/java/com/medtroniclabs/microcoaching/domain/gaps/SpiceReferralComplianceEvaluator.kt))
walks that tree against a **compliance state** with two branches:

- `recommended.*` — what SPICE's suggestion engine (`ReferralResultGenerator` +
  the ANC/PNC evaluators) computed.
- `actual.*` — what the CHW actually did.

A gap **fires** (`outcome="incorrect"`) when the tree is true — i.e. the two
branches diverge. Operators supported:

| Group | Operators |
|---|---|
| Logical | `and`, `or`, `not` |
| Precondition (recommended side) | `eq`, `neq`, `exists`, `contains_any`, `contains_all`, `array_nonempty`, `map_key_nonempty`, `array_contains_substring` |
| Mismatch (recommended vs actual) | `missed_referral`, `mismatch_eq`, `mismatch_contains_any`, `mismatch_urgency` |

Unknown/malformed nodes → false (a rule we can't parse never fires). Evidence
carries only rule-level `metadata`, never patient clinical data.

---

## 2. Gaps we detect today (the v3 seed)

The v3 seed (`ignored/v3/gaps.json`) ships **45** `spice_referral_compliance`
gaps — **all** handled by the single generic evaluator, across **every**
assessment (not just RMNCH). (The `module_primary_gap_*` rows carry no
`detection_rule` and are module-linkage only; the dispatcher skips them — see §7.)

| Family | Example gap_codes | Reads (recommended branch) |
|---|---|---|
| ANC — emergency / non-emergency / gaps | `referral_anc_emergency_acute`, `…_obstetric`, `…_severe_anemia`, `referral_anc_non_emergency_anemia`, `referral_anc_gap_supplementation` | `recommended.assessmentDetails.anc.summary.highRiskPregnantWoman.{URGENT,NON_URGENT}`, `…gapsInAnc` |
| PNC — emergency / non-emergency / gaps | `referral_pnc_emergency_bleeding_infection`, `referral_pnc_non_emergency_breast`, `referral_pnc_gap_contraception` | `recommended.assessmentDetails.pncMother.motherRisks.{URGENT,NON_URGENT}`, `…pncGaps` |
| ICCM | `referral_iccm_danger_signs`, `…_respiratory`, `…_fever_malaria`, `…_diarrhoea`, `…_malnutrition` | `recommended.referredReason` |
| NCD | `referral_ncd_cardiometabolic`, `…_substance`, `…_mental_health`, `…_hiv_pregnancy` | `recommended.referredReason` |
| TB / CBS / Family planning | `referral_tb_symptoms`, `referral_cbs`, `referral_family_planning_consult` | `recommended.referredReason` |
| **Location / tier** | `referral_location_upazila`, `referral_location_community_clinic`, `referral_location_facility_selected` | `recommended.referralFacilityType` (or `…assessmentDetails.referralFacilityType`) vs `actual.destinationTier` |
| Urgency type | `referral_type_emergency`, `referral_type_non_emergency` | urgency vs `actual.isUrgent` |

**Typical shape:** an `and` of a *recommended-side precondition* (`contains_any` /
`eq` / `map_key_nonempty` on the recommendation) and an `or` of *mismatch* branches
(`missed_referral`, `mismatch_contains_any`, `mismatch_eq`, `mismatch_urgency`).
Because the mismatch branches are OR-ed with `missed_referral`, **most gaps can fire
on the `missed_referral` branch alone** (see §5). The facility-tier work that was
reverted lives on here as the `referral_location_*` gaps' `mismatch_eq` branch.

### Which SPICE assessments these apply on

Each gap **self-scopes** through its recommended-side precondition (no per-gap
config needed — see §7). An ANC gap reads `recommended.assessmentDetails.anc.*`, so
it only fires on an RMNCH/ANC assessment (an NCD assessment has no `anc` branch →
precondition false → no fire). NCD/ICCM/TB/CBS gaps key off
`recommended.referredReason`; location gaps off `recommended.referralFacilityType`.
The recommended branches come from `ReferralResultGenerator` per assessment — see §4.

---

## 3. What our gaps detect vs. what SPICE measures

### The data-availability reality

The reasons and urgency the gaps compare are **system-computed** by SPICE's
suggestion engine — the CHW is never asked to pick them. So the `recommended`
side is rich and capturable; the `actual` side is thin.

| State field | Source | Capturable? |
|---|---|---|
| `recommended.referredReason` | `ReferralResultGenerator` → stored as `referralReason` (`AssessmentViewModel.kt:353/1501`) | ✅ |
| `recommended.referralUrgency` / `…{URGENT,NON_URGENT}` | system-bucketed (`PNCAssessmentEvaluator.getUrgentReferral`, ANC equivalent); `AncPncReferralType` enum | ✅ |
| `recommended.isReferred`, `recommended.assessmentDetails.*` | assessment | ✅ |
| `actual.didRefer` | CHW follow-through — the `etPhuChange` PHU picker (present on RMNCH & BD NCD summaries) / its absence | ⚠️ inferable |
| `actual.referralReasons` | **CHW is not asked to choose reasons** in the assessment; only a free-text reason exists in the separate medical-review flow (`ReferPatientFragment.enteredReferredReason`) | ❌ |
| `actual.isUrgent` | **no CHW urgency input exists anywhere** | ❌ |

### What this means per operator

| Operator | Viable today? | Why |
|---|---|---|
| `missed_referral` | ✅ | needs `recommended.isReferred` (have) + `actual.didRefer` (PHU-pick/follow-through). The one real, capturable CHW divergence. |
| `mismatch_contains_any` (on `actual.referralReasons`) | ❌ | the CHW doesn't choose reasons — SPICE computes them. No actual-side data to diverge. |
| `mismatch_urgency` (on `actual.isUrgent`) | ❌ | no CHW urgency input exists. |

**Net:** the CHW's real deviation space in SPICE is *"did they refer, and to which
facility"* — not *"which reasons / what urgency."* So `missed_referral` is the
immediately fireable operator; `mismatch_*` need a SPICE UX change to capture CHW
reason/urgency choices.

---

## 4. What SPICE measures across assessments (capture surface)

Every assessment type runs `ReferralResultGenerator`, producing a recommended
referral status + structured reasons we could capture for new gaps:

| Assessment | menu/type | Generator method | Recommended signals |
|---|---|---|---|
| ICCM | `iccm` | `calculateIccmReferralResult` | danger signs, MUAC/nutrition, pneumonia/cough, fever/malaria, diarrhoea |
| RMNCH ANC | `rmnch` | `calculateRMNCHReferralResult` | highRiskPregnantWoman {URGENT/NON_URGENT}, gapsInAnc |
| RMNCH PNC | `rmnch` | `calculateRMNCHReferralResult` | motherRisks {URGENT/NON_URGENT}, pncGaps |
| RMNCH childhood | `rmnch` | `calculateRMNCHReferralResult` | childhood-visit signs |
| BD NCD | `ncd` (community) | `computeReferralResultForBDNCD` | BP, blood glucose, **facility tier** |
| SL NCD | `ncd` (non-community) | `calculateNCDStatus` | diabetes / smoker / alcohol / BMI / symptoms |
| TB | `TB` | `calculateTBReferralResult` | TB symptoms |
| Other symptoms | — | `calculateOtherSymptomsReferralResult` | symptoms, fever |
| CBS | `cbs` | `calculateCBSReferralResult` | CBS referral |
| Pregnancy outcome | — | `calculatePregnancyOutcomeStatus` | family-planning consult |

---

## 5. Which shipped gaps can actually fire today

The 45 gaps already exist; the practical question is **which have the actual-side
data to fire**, per the §3 viability rules. (The SDK evaluator handles all of them
regardless — this is purely a SPICE data-availability filter.)

| Gap family | Fires via | Status today |
|---|---|---|
| ANC / PNC / ICCM / NCD / TB / CBS **missed referral** | `missed_referral` branch | ✅ once SPICE assembles `recommended.{isReferred,referredReason}` + `actual.didRefer` |
| **Location / tier** (`referral_location_*`) | `mismatch_eq` on `actual.destinationTier`, OR `missed_referral` | ✅ wired — `HealthFacilityEntity.type` synced + captured at the PHU picker → `actual.destinationTier`. ⚠️ requires the BD `health_facility.type` vocabulary to match the recommended constants (exact `mismatch_eq`) — confirm before relying on it |
| **Reason mismatch** (`mismatch_contains_any`) | `mismatch_contains_any` on `actual.referralReasons` | ❌ blocked — SPICE doesn't capture CHW-chosen reasons |
| **Urgency type** (`referral_type_*`, `mismatch_urgency`) | `mismatch_urgency` on `actual.isUrgent` | ❌ blocked — no CHW urgency input |

### Recommended sequencing
1. **`missed_referral` across all families** — one operator; the recommended data is
   already produced by `ReferralResultGenerator` and `actual.didRefer` by the PHU
   follow-through. Lights up the bulk of the 45 gaps' OR-branches with no SPICE UX
   change.
2. **Location / tier** — ✅ wired: `health_facility.type` synced onto
   `HealthFacilityEntity`, captured at the PHU picker, forwarded as
   `actual.destinationTier`; the `referral_location_*` gaps' `mismatch_eq` branch
   fires. Remaining: confirm the BD `health_facility.type` vocabulary matches the
   recommended constants (or add a normaliser) — `mismatch_eq` is an exact compare.
3. **Reason / urgency mismatches** — only after SPICE adds CHW-side capture of
   referral reasons and an urgency flag (product change).

---

## 6. The prerequisite for any of these to fire

The SDK evaluator is ready. The gating work is **SPICE assembling the
`{recommended, actual}` state** and passing it to the SDK hook (the hook today
sends a flat map). See [COMPLIANCE_TEST_SPEC.md §2](./COMPLIANCE_TEST_SPEC.md) for
the state contract. `recommended.*` is assemblable from the assessment;
`actual.didRefer` from the PHU picker; `actual.referralReasons` / `actual.isUrgent`
require new SPICE capture.

---

## 7. How the SDK picks which cached gap to evaluate

There is **no pre-selection** — the dispatcher evaluates *every* eligible cached
gap and lets each rule decide for itself. This is intentional and correct:

1. **Load** — `BehaviouralGapDao.getActiveWithRules()` returns only rows where
   `status = 'active' AND detection_rule IS NOT NULL AND detection_rule != '{}'`.
   So the `module_primary_gap_*` rows (no `detection_rule`) and quiz-only gaps are
   filtered out at the query; only the ~45 rule-bearing gaps load.
2. **Iterate** — [GapRuleDispatcher](../../sdk-android/src/main/java/com/medtroniclabs/microcoaching/domain/gaps/GapRuleDispatcher.kt)
   loops every loaded gap, parses its envelope, and routes
   `isCompliance` ones to `SpiceReferralComplianceEvaluator` (legacy `rule_type`
   ones go to their per-type evaluator after the `match` filter).
3. **Self-scope** — a compliance gap needs no `match` clause: its `when`
   tree's recommended-side precondition *is* the scope. On an NCD assessment, an
   ANC gap's `contains_any recommended.assessmentDetails.anc.*` resolves to nothing
   → the `and` short-circuits → the gap doesn't fire. So iterating all gaps is
   safe; the wrong-pathway gaps simply evaluate to false.

Cost is fine: ~45 pure in-memory tree walks per assessment, no I/O. Multiple gaps
may fire on one assessment — the dispatcher returns all fired results and the
caller emits one `spice_action_observed` per result.
