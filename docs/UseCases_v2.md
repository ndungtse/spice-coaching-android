# MicroCoaching SDK — Use Cases & Journey (v2)

**Version:** 2.0  
**Date:** April 19, 2026  
**Status:** Aligned to post–April 16 architecture (coaching-card-first, SPICE hook–driven)  
**Supersedes:** `docs/UseCasesandEx.md`  
**Source truth:** the original CHW AI Coaching requirement document (not included in this repo).

---

> **Notation used in this document**
> `bn:"..."` — the quoted text is what the CHW actually reads. It is written here in English for clarity, but **must be displayed in Bangla** in the real app. Every string marked this way is a Bangla content requirement, not an English UI string.

---

## Cast of Characters

| Person | Role | Device | Context |
|---|---|---|---|
| **Ayesha** | Community Health Worker (SK — Shasthya Kormi) | Android ~$80 phone, 4G sometimes, often offline | Visits 6–10 patients per day in rural Bangladesh. Trained once, months ago. |
| **Rahim** | Field Supervisor | Android tablet, usually 4G | Oversees 12 CHWs, reviews coaching quality weekly. |
| **Nasrin** | Program Manager | Laptop + phone, reliable internet | Manages a district program. Tracks outcomes and compliance. |

---

## The Reinforcing Loop

The three use cases form a continuous loop — each makes the others more effective over time:

```
┌─────────────────────────────────────────────────────┐
│                                                     │
│   UC-1 LEARN ──────────────────────► UC-2 APPLY    │
│   (morning cards + quizzes)          (visit cards)  │
│        ▲                                    │        │
│        │   gap profile updated              │        │
│        │   ← what to teach next             ▼        │
│   UC-3 MEASURE ◄───────────────── telemetry events  │
│   (supervisor dashboard)                            │
│                                                     │
└─────────────────────────────────────────────────────┘
```

- **UC-1 → UC-2**: Better-trained CHWs need fewer nudges. When the coaching engine knows Ayesha already understands HTN referral thresholds, it skips that reinforcement in her UC-2 card and focuses on what she still needs.
- **UC-2 → UC-3**: Every visit interaction (card shown, point acknowledged, point skipped, time-on-screen) becomes a telemetry event logged to Room DB and synced to the Knowledge Layer.
- **UC-3 → UC-1**: The Knowledge Layer updates Ayesha's gap profile. The next morning card selection is driven by that updated profile — personalized to what she actually needs, not a generic schedule.

---

## Part 1 — First Launch: Ayesha Opens SPICE for the First Time

### What Ayesha sees

She opens SPICE after being enrolled in the program. After the normal SPICE login, the MicroCoaching SDK checks whether she has completed onboarding. She hasn't — so a **Coach Mark overlay** slides over the SPICE home screen. It highlights where coaching cards will appear and explains what the system does. One tap dismisses it.

She's taken to three **onboarding slides** (all text shown in Bangla to the CHW):
1. bn:`"A coaching card will appear each morning to help you prepare for visits."`
2. bn:`"After you submit a patient assessment, you'll see personalized counselling guidance."`
3. bn:`"Your progress is tracked so your supervisor can support you better."`

She taps "Start" on the last slide. Onboarding is marked complete and stored in Room DB. It will never show again.

### What the SDK does

```
SPICE HomeActivity.onCreate()
    │
    ▼
SDK.initialize(app, config)   ← called once from SPICE Application.onCreate()
    │   starts WorkManager sync, Room DB migration
    ▼
SDK.onMorningOpen(chwId)     ← called by SPICE HomeScreenFragment.onViewCreated()
    │
    ▼
SDK checks: onboarded? → NO
    │
    ▼
Shows CoachMarkScreen → OnboardingSlideScreen
    │   (managed by CoachingFlowActivity, launched as a dialog-style overlay)
    ▼
CHW completes onboarding → state saved to Room DB (chw_learning_state)
    │
    ▼
Proceeds to morning card flow (UC-1)
```

---

## Part 2 — UC-1: Learn — Morning Before Field Visit

### Goal

Build Ayesha's clinical knowledge and SPICE tool proficiency **before and between patient visits**, in 2–3 minute sessions she can do while walking or waiting.

### Sub-scenario A: First Morning Card — Health Domain

Ayesha opens SPICE at 7 AM. She has two patients today with known hypertension. The SDK has already fetched her morning card during the last background sync.

She sees a card:

```
┌─────────────────────────────────────────┐
│  bn:"Today's Learning Card"             │
│  bn:"HTN Patient Referral Protocol"     │
│                                         │
│  bn:"Est. time: 3 minutes"              │
│                                         │
│  [bn:"Start"]      [bn:"Skip today"]    │
└─────────────────────────────────────────┘
```

She taps "Start". A lesson screen shows 3–4 scroll-through content points in Bangla. At the bottom: a 3-question quiz.

- **Q1:** "A patient's BP is 158/98. What do you do?" → She selects the right option (advise + follow-up at NCD Corner). ✓
- **Q2:** "BP is 170/110. Where do you refer?" → She selects "Community Clinic" — **wrong**. The correct answer is "UHC." The feedback overlay explains: "Individuals with BP >160/100 must be referred to Upazila Health Complex (UHC), not a Community Clinic."
- **Q3:** Another HTN protocol question. ✓

She finishes: 2/3. A badge appears. The lesson ends.

**What the SDK records internally:**
- `coaching_event: lesson_started` (module_id, chw_id)
- `coaching_event: quiz_answer` (question_id, selected_index, correct: false) for Q2
- `coaching_event: quiz_completed` (score: 67%)
- Gap entry written to `chw_gap_profile_local`: topic `ht_referral_threshold` → `score: 0.67`

This gap score will influence both what UC-1 cards Ayesha sees tomorrow **and** what her UC-2 cards emphasize during HTN patient visits.

### Sub-scenario B: Digital Proficiency Card

Ayesha recently upgraded to SPICE 2.0 which has a new "Pending Tasks" feature. The Knowledge Layer has flagged (via incoming scenario sync) that CHWs in her cohort are frequently missing incomplete task follow-ups.

Three days in, a different type of morning card appears:

```
┌─────────────────────────────────────────┐
│  bn:"SPICE 2.0 New Feature"             │
│  bn:"How to use the Pending Tasks List" │
│                                         │
│  bn:"Est. time: 2 minutes"              │
│  [bn:"Start"]      [bn:"Skip today"]    │
└─────────────────────────────────────────┘
```

This is a **digital domain** lesson — same UI, same quiz flow, but content focuses on SPICE navigation rather than clinical knowledge. It exists because UC-1 covers both health training and digital tool proficiency as equal first-class domains.

### How UC-1 happens technically

```
SDK.onMorningOpen(chwId)
    │
    ▼
MorningCardSelector.selectCard(chwId, gapProfile, today)
    │   reads chw_gap_profile_local from Room DB
    │   picks weakest topic with a lesson not shown in last 7 days
    ▼
Room DB lookup: scenario by topic
    │   if scenario found locally → render immediately (cached mode)
    │   if scenario stale or missing → fetch from Knowledge Layer (online mode)
    ▼
CoachingFlowActivity launched: ModuleReady → LessonContent → Quiz → QuizResult
    │
    ▼
On quiz complete: coaching_events written to Room DB
OutboundSyncWorker picks them up next time there's network → POSTs to Knowledge Layer
    │
    ▼
Knowledge Layer updates chw_gap_profile for Ayesha server-side
Next InboundSyncWorker run pulls updated gap profile back to device
```

### Tri-mode in UC-1

| Signal | What happens |
|---|---|
| Good network | Scenarios come from Knowledge Layer (latest content, personalized selection) |
| No network | Pre-synced scenarios from Room DB serve the card — content may be slightly less personalized but works fully offline |
| Degraded device | Cached pre-authored cards (Bangla text only, no AI generation) — quiz still works locally |

---

## How UC-1 Feeds UC-2

This is the key cross-use-case connection. The SDK maintains a local gap profile (`chw_gap_profile_local`) that summarises Ayesha's knowledge state across clinical topics.

When she arrives at a patient visit and submits an assessment, the coaching card generated for that patient is **shaped by her gap profile**:

- If her gap score for `ht_referral_threshold` is low → her UC-2 coaching card for a hypertensive patient will include a reminder about referral thresholds alongside the counselling points.
- If her gap score is high (she's mastered it) → the card focuses only on counselling, with no redundant reinforcement.

The backend (Knowledge Layer) does this matching: `ContextPack = patient_snapshot + chw_gap_profile + scenario`. The better UC-1 does its job, the more targeted UC-2 becomes.

---

## Part 3 — UC-2: Apply — During a Patient Visit

### Goal

Provide Ayesha with **personalized, Bangla counselling guidance** immediately after she submits a patient assessment in SPICE — tailored to that specific patient's readings, profile, and her own knowledge gaps.

### Sub-scenario A: Post-Assessment Coaching Card (main path)

**Patient:** Fatima, 58 years old. Enrolled for hypertension. Current BP: 158/98. Irregular medication adherence. Heavy physical work.

Ayesha fills in the monthly assessment in SPICE as usual — BP, symptoms, medication compliance — then taps **Submit**. The normal SPICE risk card appears ("Risk Level: HIGH"). Below it, a new section slides in:

```
┌──────────────────────────────────────────────┐
│  bn:"Counselling Guidance"                   │
│  ────────────────────────────────────────    │
│  1. bn:"Fatima's physical work is good for   │
│        her health. But eating less salt      │
│        will help keep her BP under control." │
│     [✓ bn:"I told her"]                      │
│                                              │
│  2. bn:"Best time to take medication is      │
│        before work. Missing doses raises     │
│        the risk of heart attack."            │
│     [✓ bn:"I told her"]                      │
│                                              │
│  3. bn:"If BP goes above 160/100, refer to   │
│        UHC — go today."                      │
│     [ ] bn:"I told her"                      │
└──────────────────────────────────────────────┘
```

Point 3 is specifically shaped by Ayesha's gap: `ht_referral_threshold` is still weak → the backend included a referral reminder in this card.

Ayesha reads each point aloud to Fatima and taps "Done" as she goes. She takes 94 seconds total.

**What the SDK records:**
- `coaching_event: card_shown` (patient_visit_id, scenario_id, ai_mode=online)
- `coaching_event: counselling_point_acknowledged` ×2 (point_index=0, point_index=1)
- `coaching_event: card_dismissed` (points_acknowledged=2, total_points=3, time_on_screen_ms=94000)

Point 3 was not tapped — that's noted. After a few such visits, the Knowledge Layer will flag "referral point acknowledgment low" and create a new UC-1 card reinforcing it.

### Sub-scenario B: Offline Visit (Cached Mode)

No network signal in the field. Ayesha submits an assessment for a diabetic patient. The SDK can't reach the Knowledge Layer. Mode selector: **Cached**.

The card that appears is a **pre-authored Bangla card** downloaded during the last sync, matched by `risk_level + primary_condition` (HIGH + diabetes). It's not personalized to this specific patient's socio-demographic profile, but:

- It's in Bangla.
- It's clinically validated by the SME team (no hallucinations possible — it's static text).
- It gives Ayesha 3 relevant counselling points to work with.
- The event is still logged to Room DB and will sync later.

CHWs in rural Bangladesh will hit this path regularly. The cached cards are a first-class offline product, not a fallback footnote.

### Sub-scenario C: Mid-Visit Risk Flag

Ayesha is mid-assessment. She enters BP: 185/115. SPICE's rule-based logic fires a risk alert immediately. Simultaneously:

```
SPICE detects BP > threshold
    │
    ▼
MicroCoachingSDK.onRiskFlagObserved(chwId, patientId, riskType=HYPERTENSION_CRISIS)
    │
    ▼
SDK shows a compact warning card (does NOT replace the SPICE risk alert):
  bn:"This reading indicates a hypertensive crisis.
      Refer to UHC immediately. Arrange transport."
```

Note: the SDK card is **coaching guidance**, not a clinical decision. SPICE's rule-based referral flag still fires normally. The SDK adds the counselling framing alongside it.

### How the SPICE hooks work

```
SPICE AssessmentHolderActivity.onAssessmentSubmit()
    │
    ▼
MicroCoachingSDK.onAssessmentSummaryReady(
    chwId = "ayesha_001",
    patientId = "sha256(raw_id)",   ← always hashed, never raw
    riskLevel = "HIGH",
    primaryCondition = "HYPERTENSION",
    assessmentData = { bp_systolic: 158, bp_diastolic: 98,
                       medication_adherent: false, occupation: "physical_labour" }
)
    │
    ▼
ModeSelector.selectMode(networkQuality, batteryLevel)
    │   ONLINE → CoachingDecisionEngine sends ContextPack to Knowledge Layer
    │   EDGE   → Gemma on-device bounded generation (same structured output)
    │   CACHED → ScenarioLookup by riskLevel + primaryCondition
    ▼
Coaching card rendered below SPICE risk result card
    │   (SDK inserts ComposeView into R.id.coaching_container,
    │    a slot SPICE exposes in its AssessmentSummaryFragment layout)
    ▼
CHW acknowledges points → coaching_events → Room DB → sync queue
```

---

## How UC-2 Feeds UC-3

Every interaction Ayesha has with a coaching card becomes a `coaching_event` in Room DB. These events are the raw material of UC-3.

| What happened | Event logged |
|---|---|
| Card was shown after assessment | `card_shown` (scenario_id, ai_mode, patient_visit_id) |
| She tapped "Done" on point 1 | `counselling_point_acknowledged` (point_index=0) |
| She skipped point 3 | No `acknowledged` event for point_index=2 |
| She closed the card after 94s | `card_dismissed` (points_ack=2, time_ms=94000) |
| She started but abandoned the quiz | `quiz_abandoned` |

When the sync worker runs (on next network, or every 15 min if connected), these events POST to the Knowledge Layer. The backend:
1. Stores events against Ayesha's record.
2. Updates her `chw_gap_profile` based on which points she consistently acknowledges vs. skips.
3. Makes this updated profile available to the next morning card selection (UC-1) and the next patient visit card generation (UC-2).

---

## Part 4 — UC-3: Measure — Supervisor & Program Manager View

### Goal

Give Rahim (supervisor) and Nasrin (program manager) actionable visibility into which CHWs are struggling, which clinical topics are weak, and whether coaching is actually changing CHW behaviour.

### Sub-scenario A: Rahim's Weekly Review

Rahim opens the SPICE supervisor view on his tablet. A new section loads: **"Coaching Performance"** (provided by the SDK's SupervisorCoachingFragment, embedded in SPICE's supervisor dashboard).

He sees a table:

```
Name       | Cards Shown | Points Ack. | Quiz Avg | Trend
──────────────────────────────────────────────────────
Ayesha     | 18          | 2.7 / 3     | 71%      | ↑
Mina       | 14          | 1.1 / 3     | 48%      | →
Rupa       | 6           | 1.8 / 3     | 55%      | ↓
```

Mina is only acknowledging 1.1 out of 3 counselling points on average. Rahim taps her row. A detail view shows:
- Topic breakdown: strong on diabetes protocol, weak on medication adherence counselling.
- Last 7 visits: she never acknowledges the "discuss medication adherence" point.

Rahim schedules a coaching call with Mina. He also notes that "medication adherence" is weak across 4 of his 12 CHWs — he flags this for Nasrin.

### Sub-scenario B: Telemetry Drives a New UC-1 Card

Back to Ayesha. The Knowledge Layer has been watching her data for 10 days:

- She's completed 4 morning lessons (all HTN and diabetes domain).
- Her `ht_referral_threshold` gap score has improved: 0.67 → 0.81.
- But she skipped the "medication adherence" counselling point in 5 of 7 visits.

The Knowledge Layer's gap profile update triggers a new learning scenario selection:

Next time `InboundSyncWorker` syncs, it pulls a new scenario bundle tagged: `topic: medication_adherence_counselling`. The next morning Ayesha opens SPICE:

```
┌──────────────────────────────────────────────┐
│  bn:"Today's Learning Card"                  │
│  bn:"How to talk to patients about           │
│      following their medication"             │
│                                              │
│  bn:"Est. time: 3 minutes"                   │
│  [bn:"Start"]      [bn:"Skip today"]         │
└──────────────────────────────────────────────┘
```

**This is the UC-3 → UC-1 feedback loop in action.** The system taught itself what Ayesha needs next based on real field behaviour — not a static curriculum schedule.

### Sub-scenario C: Program Manager Nasrin's District View

Nasrin opens the MicroCoaching program manager section (embedded in the SPICE supervisor portal, higher role level). She sees:

- 847 coaching cards shown across 12 CHWs this month.
- 3 topics where avg quiz scores are below 60%: maternal ANC referral, glucometer error codes, medication adherence.
- "Medication adherence" is the lowest across all Upazilas.

She plans an in-person training session. She also flags the glucometer error codes topic to the content team: the pre-authored cached cards for that topic may need updating.

**What the SDK provides to this view:** all `coaching_event` data aggregated server-side by the Knowledge Layer. The SDK itself doesn't build the program manager dashboard — it generates the events that power it.

---

## Part 5 — End-to-End: One Week in Ayesha's Life

This is the full story in compressed form:

```
Day 1 — Morning
  Ayesha opens SPICE
  → Onboarding (first time only)
  → UC-1 morning card: "HTN Referral Protocol"
  → Quiz 2/3 correct
  → Gap recorded: ht_referral_threshold = 0.67

Day 1 — Visit 1 (Fatima, HTN)
  → Assessment submitted
  → UC-2 card (online mode): 3 counselling points, tailored to Fatima
  → Point 3 (referral reminder) included because gap score is still 0.67
  → Ayesha acknowledges 2/3 points
  → Events queued in Room DB

Day 1 — Visit 2 (Rohima, Diabetes)
  → No network in field
  → UC-2 card (cached mode): pre-authored diabetes counselling card
  → 3 points, acknowledges all 3
  → Events queued

Day 1 — Evening
  → Network available → OutboundSyncWorker fires
  → 5 coaching_events sent to Knowledge Layer
  → Knowledge Layer updates Ayesha's gap profile

Day 3 — Morning
  → InboundSyncWorker ran overnight: pulled updated gap profile
  → UC-1 morning card: "SPICE 2.0 Pending Tasks" (digital domain)
    (HTN gap improving, rotating to digital domain)

Day 3 — Visit (HTN patient, mid-risk)
  → UC-2 card: ht_referral_threshold gap now 0.73
  → Referral reminder still present but shorter
  → Acknowledges 3/3

Day 5 — Morning
  → UC-1: "Malaria in Pregnancy — Referral Protocol"
    (upcoming patient has ANC flag + previous malaria history)

Day 7 — Gap profile update from Knowledge Layer
  → ht_referral_threshold improved to 0.81
  → medication_adherence counselling: skipped in 5/7 visits → flagged as gap
  → New card queued: bn:"How to talk to patients about following their medication"

Day 7 — Rahim's weekly review
  → Sees Ayesha: improving trend ↑
  → Sees medication adherence is weak across 4 CHWs
  → Plans group coaching session
```

---

## Part 6 — What the SDK Shows vs. What SPICE Owns

This distinction matters for understanding what we build vs. what SPICE already handles.

| Feature | Owner | Notes |
|---|---|---|
| CHW login + authentication | SPICE | SDK receives `chw_id` from SPICE session |
| Patient data storage and clinical records | SPICE | SDK never stores raw patient data |
| Rule-based risk flagging (e.g. BP > threshold → referral) | SPICE | SDK coaching sits alongside this, never replaces it |
| Home screen layout | SPICE | SDK injects coaching card into `R.id.coaching_card_slot` |
| Assessment form + submit | SPICE | SDK listens to `onAssessmentSummaryReady` hook callback |
| Morning coaching card | **SDK** | Triggered by `onMorningOpen`, managed entirely by SDK |
| Post-assessment coaching card | **SDK** | Triggered by `onAssessmentSummaryReady` |
| Lesson content, quiz, result screens | **SDK** | `CoachingFlowActivity` — launched as standalone |
| Counselling points display + checkbox | **SDK** | Injected below SPICE risk card via `ComposeView` |
| Offline content cache | **SDK** | Room DB, synced from Knowledge Layer |
| CHW gap profile | **SDK + Knowledge Layer** | Stored locally (Room DB) + server-side |
| Telemetry sync to backend | **SDK** | `OutboundSyncWorker` → Knowledge Layer `/telemetry/events` |
| Supervisor coaching dashboard | **SDK fragment** | Embedded in SPICE's supervisor view via `getSupervisorFragment()` |
| Program manager analytics | Knowledge Layer portal | Not in SDK — server-side aggregation |

---

## Part 7 — SPICE Hook Integration Points

| SPICE Location | SDK Method Called | SDK Response |
|---|---|---|
| `Application.onCreate()` | `MicroCoachingSDK.initialize(app, config)` | Start Room DB, register sync workers, load gap profile |
| `HomeScreenFragment.onViewCreated()` | `SDK.onMorningOpen(chwId)` | Show morning coaching card if one is ready; trigger UC-1 flow |
| `AssessmentHolderActivity.onAssessmentSubmit()` | `SDK.onAssessmentSummaryReady(chwId, patientId, riskLevel, assessmentData)` | Generate and inject coaching card into `R.id.coaching_container` |
| `PatientViewFragment.onPatientLoaded()` | `SDK.onPatientContextLoaded(chwId, patientId)` | *(UC-2 pre-visit context card — Phase E, deferred for MVP)* |
| Risk flag fires in SPICE | `SDK.onRiskFlagObserved(chwId, patientId, riskType)` | Show compact escalation coaching card alongside SPICE alert |
| `BaseActivity.onResume()` | `SDK.onConnectivityChanged(isConnected)` | Trigger sync if network restored; switch mode selector state |
| `BaseActivity.onStop()` | `SDK.onVisitCompleted(chwId, patientId, encounterId)` | Flush pending coaching events for this visit to Room DB |

> **Status:** Most hooks are `TEAM-CONFIRM` pending SPICE sandbox access. Contracts are defined; implementation awaits SPICE team confirmation of exact callback signatures. See Open Questions below.

---

## Part 8 — Tri-Mode AI Decision

The SDK selects the AI mode automatically based on runtime signal:

```kotlin
fun selectMode(networkQuality: NetworkQuality, batteryLevel: Int): CoachingMode {
    return when {
        networkQuality == GOOD && batteryLevel > 20 -> CoachingMode.ONLINE
        networkQuality == POOR && batteryLevel > 30 -> CoachingMode.EDGE
        else -> CoachingMode.CACHED
    }
}
```

| Mode | Engine | Requires Network | Output Quality | Latency |
|---|---|---|---|---|
| **Online** | Knowledge Layer → Gemini | Yes (good) | Personalized to patient + CHW gap profile | 800ms–2s |
| **Edge** | Gemma 3 1B INT4 on-device | No | Good — same bounded structure, less personalized | 1.5s–4s |
| **Cached** | Pre-authored Bangla cards from Room DB | No | Fixed — no hallucination risk, always safe | <100ms |

**Important:** All three modes produce the same coaching card structure (title, 3 counselling points with checkboxes, optional referral note). The CHW sees the same UI regardless of mode. The difference is only in content quality and freshness.

Edge mode and cached mode both go through the same `OutputValidator` before display: no diagnostic language, no drug names, no dosage instructions.

---

## Part 9 — Privacy & Data Rules

- **Patient ID is always hashed**: `patient_id = SHA-256(raw_spice_id)`. Raw patient IDs never leave SPICE.
- **No medical readings in SDK events**: `coaching_events` contain `scenario_id`, `ai_mode`, `points_acknowledged` — not the BP value or patient name.
- **No prompt text in logs**: `LlmTrace` records model_id, latency_ms, token counts, validator_outcome — not the prompt content.
- **Gap profile is CHW-level, not patient-level**: `chw_gap_profile` tracks the CHW's knowledge state across topics; it is not a patient record.
- **Room DB encrypted at rest**: SQLCipher with per-installation key.
- **Edge (Gemma) responses are ephemeral**: not stored, only the CHW's acknowledgment events are persisted.

---

## Open Questions

| # | Question | Which UC affected | Status |
|---|---|---|---|
| Q1 | Which SPICE hook callback signatures are confirmed? | UC-2, UC-3 | TEAM-CONFIRM |
| Q2 | Exact assessment field names in SPICE callback (bp_systolic, etc.) | UC-2 | TEAM-CONFIRM |
| Q3 | Does `patient_track_id` exist in all SPICE assessment contexts? | UC-2 | TEAM-CONFIRM |
| Q4 | How does SDK authenticate to Knowledge Layer? (SPICE bearer token re-use?) | UC-1, UC-2 | TEAM-CONFIRM |
| Q5 | `POST /telemetry/events` endpoint — is it live in knowledge-layer yet? | UC-3 | Check knowledge-layer/ |
| Q6 | Is `R.id.coaching_container` slot agreed with SPICE Android team? | UC-2 | TEAM-CONFIRM |
| Q7 | Pre-visit patient context card (onPatientContextLoaded) — in MVP scope? | UC-2 | Deferred (Phase E) |
| Q8 | Bangla voice IT-help — in MVP scope? | UC-1 | Deferred (Phase H) |
