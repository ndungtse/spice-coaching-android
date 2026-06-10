# MicroCoaching Android SDK — Implementation Realignment Guide

**Version:** 1.0  
**Date:** April 16, 2026  
**Context:** SDK implementation realignment  
**Source docs:** the MicroCoaching architecture and data-design documents that drive this implementation (not included in this repo).

---

## 0. Why This Document Exists

After reviewing the latest architecture documents, there is a gap between:

- **What we built so far** — a chat-first SDK with on-device Gemma inference, model download pipeline, onboarding flow, and a learn module with local scenarios
- **What the architecture actually requires** — a scenario-driven coaching platform embedded in SPICE, centered on coaching cards (not chat), triggered by SPICE workflow events, backed by a backend knowledge layer

This document maps the gap, identifies what to keep, what to pivot, and what to build next.

---

## 1. The Real-World Use Case (Plain Language)

### Who is the user?

**Ayesha** — a Community Health Worker (CHW) in rural Bangladesh. She has a low-end Android phone, sometimes has 4G, sometimes is offline. She visits 6-10 patients per day. She uses the **SPICE app** to screen and assess patients for non-communicable diseases (hypertension, diabetes, etc.) and maternal health.

### What problem are we solving?

Ayesha was trained once, months ago. She forgets clinical protocols. She doesn't always know the right counselling to give a specific patient. She sometimes struggles with the SPICE app itself (digital proficiency). There's no way for her supervisors to know where she's struggling.

### What does MicroCoaching do?

It's an **invisible coaching layer inside SPICE** that:

1. **Teaches her** (UC-1 Learn) — bite-sized morning lessons and quizzes about clinical topics and SPICE usage, personalized to her knowledge gaps
2. **Helps her in the moment** (UC-2 Apply) — after she submits a patient assessment, shows a coaching card with Bangla counselling points tailored to that specific patient
3. **Measures her progress** (UC-3 Measure) — logs all coaching interactions so supervisors can see who needs help and program managers can improve training

### How does it connect to SPICE?

The SDK is embedded inside the SPICE Android app as an `.aar` library. It doesn't own patient data, clinical rules, or workflows. SPICE tells the SDK about events (morning open, assessment submitted, risk flagged), and the SDK decides whether to show coaching content.

### How does it connect to the Knowledge Layer backend?

The Knowledge Layer (`knowledge-layer/`) is the backend that:
- Ingests training PDFs and extracts **scenarios** (bounded knowledge units, not raw text chunks)
- Stores scenarios with embeddings in PostgreSQL (pgvector)
- Generates coaching cards by matching a patient's context to the right scenario, then running it through Gemini/Ollama
- Validates AI output (no diagnosis, no drug names, no dosage)
- Serves scenario sync bundles for offline use on devices
- Ingests telemetry events from devices and updates CHW gap profiles
- Powers supervisor and program manager dashboards

**The mobile SDK uses a tri-mode AI approach.** The architecture defines three runtime modes (Architecture v2 Section 11.2, `VALID INTERPRETATION`):
1. **Online** (primary): SDK sends context to backend → backend picks scenario + generates coaching card via Gemini → SDK displays it
2. **Edge** (offline fallback with AI): On-device constrained generation using Gemma (PoC-validated in `gemma-2b-kotlin`) — provides AI-quality responses when the backend is unreachable but the device is capable
3. **Cached** (safe fallback): SDK uses locally-cached scenarios with pre-authored Bangla cards — no AI call, guaranteed safe, works on all devices

The PoC app (`gemma-2b-kotlin/docs/MicroCoaching_SDK_PoC_v1.md`) validated that Gemma 3 1B INT4 runs on budget 3GB devices (~800 MB model, ~4s cold start), offline Bengali STT works via SherpaOnnx, and Bengali TTS is available via Android OS. These capabilities are real and architecture-approved — they are the reason the SDK can provide meaningful offline coaching beyond static cards.

---

## 2. Current Implementation — What We Have

### What's working well

| Component | Status | Verdict |
|-----------|--------|---------|
| `MicroCoachingSDK` entry point | Builder pattern, singleton | **Keep** — good foundation, needs hook expansion |
| `MicroCoachingConfig` | All config in one place | **Keep** — matches "no hardcoded constants" rule |
| Room Database structure | 7 entities, 7 DAOs | **Partially keep** — entities need realignment to data design v1.1 |
| Learn module (UC-1) | Scenarios → Lessons → Quiz → Results | **Keep core flow** — needs backend sync instead of local-only seeds |
| Coaching event logging | Append-only events with sync status | **Keep** — matches architecture pattern |
| On-device inference engines | GemmaService (MediaPipe) + LiteRtLmService (LiteRT), both PoC-validated | **Keep + realign** — valuable for edge mode; change from chat to bounded coaching generation |
| Model download pipeline | WorkManager + SHA-256 verification, multiple providers | **Keep + realign** — solid infrastructure for delivering Gemma + STT models; decouple from chat flow |
| `InferenceRouter` + `LLMService` interface | Runtime engine selection | **Keep + expand** — becomes the tri-mode selector (online/edge/cached) |
| FAB + onboarding | Working UI entry point | **Keep for now** — may be replaced by coaching card surfaces |
| Telemetry (OpenTelemetry) | OTLP/HTTP export, privacy-safe | **Pivot** — architecture uses ClickHouse events, not OTel spans directly |
| Sample app integration pattern | Clean SDK init + hook calls | **Keep** — good reference for SPICE integration |

### What needs pivoting

| Component | Current State | Required State | Action |
|-----------|--------------|----------------|--------|
| **Chat UI** | Full streaming chat with Gemma | Bounded coaching cards, NOT free-form chat | **Replace primary surface** — chat becomes IT-help only (deferred) |
| **On-device Gemma inference** | MediaPipe + LiteRT, chat-focused prompts | Edge mode for constrained coaching generation (not chat) | **Realign** — keep engines, change usage from chat to bounded coaching card generation with validation |
| **Model download pipeline** | WorkManager + HuggingFace, chat-focused | Same infrastructure, serves edge mode model delivery | **Realign** — keep infrastructure, decouple from chat, lower priority than sync engine but still needed for offline AI |
| **Scenario seeds (local JSON)** | Hardcoded in assets | Scenarios come from backend via sync | **Replace with sync-based population** |
| **Room entities** | Mix of chat-first + event entities | Must match Data Design v1.1 exactly | **Realign schema** |
| **Network layer** | Basic Retrofit stubs | Full sync protocol + coaching API | **Expand significantly** |
| **No sync engine** | Only event queueing | Full bidirectional sync with backend | **Build from scratch** |

### What's missing entirely

| Component | Why It's Needed |
|-----------|----------------|
| **Sync engine** (WorkManager-based) | Offline-first requires device→backend event sync + backend→device scenario sync |
| **Coaching card UI** | The primary UI surface is cards, not chat. Cards show counselling points, quiz questions, walkthrough steps |
| **ContextPack builder** | Must assemble scenario + patient snapshot + CHW context for backend AI calls |
| **PatientSnapshot builder** | Must parse SPICE patient data into the format the backend expects |
| **Coaching decision engine** | Decides: show card? which scenario? online/cached? fallback? |
| **Output validator (on-device)** | Validates AI-generated content before display (no diagnosis, no drug names) |
| **Backend API client** | POST /coaching/counselling, POST /telemetry/events, GET /scenarios/sync |
| **Digital proficiency capture** | Observe sync success/failure, form submissions |
| **Gap profile local mirror** | Local copy of CHW's knowledge gaps, drives morning card selection |

---

## 3. Architecture Reality Check

### The correct mental model

```
SPICE (existing app) ──events──> SDK (our library) ──sync──> Knowledge Layer (backend)
                                   │                              │
                                   │ shows coaching cards          │ returns scenarios,
                                   │ to the CHW                   │ coaching cards,
                                   │                              │ gap corrections
                                   │                              │
                                   ├── Room DB (local cache) ─────┘
                                   │
                                   └── On-device AI (edge mode)
                                       ├── Gemma (constrained coaching generation)
                                       ├── SherpaOnnx STT (Bangla voice input — staged)
                                       └── Android TTS (Bangla audio output — staged)
```

### The SDK is NOT primarily:
- A standalone AI app — it's embedded inside SPICE
- A free-form chat client — primary surface is bounded coaching cards
- An independent clinical decision system — SPICE owns clinical rules

### The SDK IS:
- A SPICE plugin that listens to workflow events
- A local-first data cache that works offline
- A coaching card renderer (primary UI surface)
- A sync agent that exchanges events and content with the backend
- A quiz/learning flow runner using cached content
- An on-device AI host for edge/offline scenarios (Gemma for constrained generation, STT for voice input, TTS for Bangla audio)
- A model lifecycle manager (download, verify, load on-device models when edge mode is needed)

### Three runtime modes (Architecture v2 Section 11.2 — `VALID INTERPRETATION`)

| Mode | When | What happens | AI involved? |
|------|------|-------------|-------------|
| **Online** | Network available, policy permits | SDK sends ContextPack to `POST /coaching/counselling` → backend picks scenario, runs Gemini, validates → returns coaching card | Yes — backend Gemini |
| **Edge** | Offline or slow connection, capable device (3GB+ RAM) | SDK uses on-device Gemma to generate constrained coaching content from cached scenario + context pack. Output validated locally before display | Yes — on-device Gemma |
| **Cached** | Offline + low-end device, or any AI failure/validation failure | SDK looks up locally-cached scenario by domain/action, displays pre-authored Bangla card. No AI call — guaranteed safe | No — static content |

**Mode selection logic** (from DDD v2 Section 7.6):
1. If network available and policy allows → **Online**
2. If offline and device capability sufficient → **Edge** (constrained, not free-form)
3. Otherwise → **Cached** (reviewed Bangla cards)

Edge mode is **not free-form chat** — it uses the same bounded generation pattern as online: match scenario → build context pack → generate within constraints → validate output → fallback to cached if validation fails.

### Bangla voice capabilities (Architecture v2 Section 16 — `REQUIRED`, staged delivery)

The requirement document explicitly mandates Bangla STT and TTS. The PoC validated:
- **STT**: SherpaOnnx Zipformer — offline Bengali speech recognition (~90 MB model, 15-25% WER)
- **TTS**: Android OS built-in `TextToSpeech` — Bangla `bn-BD` confirmed offline
- **Staged delivery**: Full voice flows are `REQ-DEFERRED-MVP` but the architecture must include voice-capable interface contracts and a migration path from text-only to full voice support

---

## 4. How Use Cases Actually Work

### UC-1 Learn (Morning Briefing + Quizzes)

**Trigger:** Ayesha opens SPICE in the morning → `onMorningOpen(chwId)`

**What happens:**
1. SDK reads local gap profile → finds Ayesha's weakest topics
2. SDK reads learning path → finds next unstarted scenario
3. SDK selects up to 3 coaching cards (skippable)
4. If online: can optionally call `GET /scenarios/morning/{chw_id}` for personalized selection
5. If offline: uses local gap profile + cached scenarios
6. Shows cards → Ayesha reads lesson → takes quiz
7. Quiz graded locally (answers are in cache) → events logged to Room
8. Events sync to backend later → backend updates gap profile

**What the SDK needs:**
- Local scenario cache (populated via sync)
- Local quiz question cache
- Local gap profile mirror
- Morning card selection logic (deterministic, not AI)
- Learn UI (we already have this!)
- Event logging

### UC-2 Apply (Post-Assessment Counselling)

**Trigger:** Ayesha submits a patient assessment → `onAssessmentSummaryReady(data)`

**What happens:**
1. SDK parses assessment response → builds PatientSnapshot (BP, glucose, risk, pregnancy status)
2. SDK checks: is there a relevant coaching scenario for this patient's condition?
3. **If online**: builds ContextPack → sends to `POST /coaching/counselling` → backend returns Bangla coaching card
4. **If offline + capable device**: builds ContextPack locally → Gemma generates constrained coaching response from cached scenario + context → validated on-device before display
5. **If offline + low-end device (or validation fails)**: looks up cached scenario by clinical domain + action type → shows pre-authored Bangla card
6. Coaching card appears below SPICE's risk result — shows 2-3 counselling talking points
7. Ayesha marks each point as "done" → events logged

**What the SDK needs:**
- PatientSnapshot builder (parse SPICE data)
- ContextPack builder (scenario + snapshot + CHW context)
- Mode selector (online / edge / cached)
- Backend API client (`POST /coaching/counselling`)
- On-device inference via Gemma (edge mode — same bounded generation, local execution)
- Output validator (check AI response before display — applies to BOTH backend and edge responses)
- Coaching card UI (NOT chat — structured card with checkboxes)
- Fallback to cached Bangla card on any failure

### UC-3 Measure (Analytics)

**This is mostly backend + dashboard, but the SDK provides the data:**
- Every card_shown, card_skipped, card_accepted, quiz_answered event
- Digital proficiency signals (sync attempts, form submissions)
- All events sync to backend → ClickHouse → materialized views → dashboards

---

## 5. Room DB Realignment

### Current entities vs. required entities (Data Design v1.1)

| Required Entity | Current State | Action |
|----------------|--------------|--------|
| `coaching_event` (Section 4.1) | `CoachingEventEntity` exists but incomplete | **Update** — add missing fields (validatorStatus, fallbackUsed, villageId, upazilaId, triggerType, inferenceMode, networkState) |
| `llm_trace` (Section 4.2) | Not implemented | **Add** — needed for AI observability |
| `digital_proficiency_event` (Section 4.3) | Not implemented | **Add** — sync_attempt, form_submit, digital_help_used |
| `chw_gap_profile_local` (Section 4.4) | `ChwGapProfileEntity` exists but incomplete | **Update** — add quiz counters, counselling counters, gap classification fields |
| `scenario_cache` (Section 4.5) | `ScenarioCacheEntity` exists but incomplete | **Update** — add triggerConditionsJson, banglaCardJson, dangerSignsJson, referralThresholdJson |
| `quiz_question_cache` (Section 4.6) | `QuizQuestionCacheEntity` exists but incomplete | **Update** — add explanationBangla, difficulty, validated flag |
| `learning_path_local` (Section 4.7) | `LearningPathEntity` exists but incomplete | **Update** — add track field (clinical/spice_digital), sequenceOrder |
| `chat_message` | Implemented | **Keep for now** — may be useful for IT-help later, but not primary |
| `telemetry_queue` | OTel span queue | **Replace** — events go directly to coaching_event table, sync handles upload |

---

## 6. Backend API Endpoints the SDK Needs

These are the Knowledge Layer endpoints the SDK must call:

| Endpoint | Method | When | Purpose |
|----------|--------|------|---------|
| `/coaching/counselling` | POST | After assessment, risk flag | Send ContextPack, get coaching card |
| `/telemetry/events` | POST | On sync (every 15 min + connectivity restore) | Upload coaching_events, llm_traces, digital_events |
| `/scenarios/sync` | GET | On sync | Download new/updated scenarios, quizzes, config |
| `/scenarios/morning/{chw_id}` | GET | Morning open (if online) | Optional online morning card selection |
| `/coaching/quiz-answer` | POST | After quiz answer (if online) | Record answer, get gap update |
| `/coaching/it-help` | POST | Future/deferred | Digital help queries |

### Sync protocol summary

**Outbound (device → backend):**
```json
POST /telemetry/events
{
  "coaching_events": [...],
  "llm_traces": [...],
  "digital_events": [...],
  "gap_profile_snapshot": {...},
  "device_info": {...}
}
→ Response: { "synced_ids": {...}, "gap_profile_corrections": [...], "config_updates": {...} }
```

**Inbound (backend → device):**
```json
GET /scenarios/sync?since_version=41
→ Response: {
  "scenarios": [...],
  "quiz_questions": [...],
  "deleted_scenario_ids": [],
  "config_thresholds": {...},
  "current_version": 42
}
```

---

## 7. Recommended Build Order (What To Do Next)

### Phase A: Schema Realignment (Sprint 1 — immediate)

**Goal:** Make the Room DB match Data Design v1.1 so everything built on top is correct.

Tasks:
1. Update `CoachingEventEntity` to match Section 4.1 exactly
2. Add `LlmTrace` entity (Section 4.2)
3. Add `DigitalProficiencyEvent` entity (Section 4.3)
4. Update `ChwGapProfileLocal` entity to match Section 4.4
5. Update `ScenarioCache` entity to match Section 4.5
6. Update `QuizQuestionCache` entity to match Section 4.6
7. Update `LearningPathLocal` entity to match Section 4.7
8. Update DAOs for new/changed entities
9. Increment DB version with migration

### Phase B: Sync Engine (Sprint 1-2)

**Goal:** The device can exchange data with the Knowledge Layer backend.

Tasks:
1. Create Retrofit API interface for all backend endpoints
2. Implement outbound sync worker (WorkManager):
   - Batch pending events from Room
   - POST /telemetry/events
   - Mark synced events
   - Apply gap_profile_corrections from response
3. Implement inbound sync worker:
   - GET /scenarios/sync?since_version=X
   - Upsert scenarios, quiz questions to cache
   - Update config thresholds
4. Implement connectivity listener → trigger sync on restore
5. Implement periodic sync (every 15 min)
6. Implement retry with exponential backoff (30s, 5m, 30m, max 3 attempts)

### Phase C: Coaching Card UI (Sprint 2)

**Goal:** Replace chat as the primary surface with bounded coaching cards.

Tasks:
1. Create `CoachingCardView` composable:
   - Title (Bangla)
   - Body text (Bangla, 2-3 points)
   - Warning signs list
   - Next step indicator (refer / follow-up / no action)
   - "Done" checkboxes per point
   - Dismiss action
2. Create card states: Loading, CardReady, OfflineFallback, NoGuidanceAvailable, Error
3. Create `CoachingCardFragment` for SPICE embedding
4. Keep existing Learn UI (it's good!) — it becomes the UC-1 surface
5. **Keep chat UI code but don't use it as primary** — park for future IT-help

### Phase D: Coaching Decision Engine (Sprint 2-3)

**Goal:** SDK can decide what to show and when, across all three modes.

Tasks:
1. Implement `PatientSnapshotBuilder` — parse SPICE assessment data
2. Implement `ContextPackBuilder` — assemble scenario + snapshot + CHW context
3. Implement `ModeSelector` — **online / edge / cached** decision based on connectivity + device capability
4. Implement `CoachingDecisionEngine`:
   - For morning open: select cards from local gaps + learning path
   - For assessment: build context pack → call backend or use cache
   - For risk flag: trigger coaching card based on risk level
5. Implement on-device `OutputValidator`:
   - Block diagnostic language
   - Block drug names
   - Block dosage instructions
   - Validate JSON structure
   - Fallback to cached Bangla card on failure

### Phase E: Hook Implementation (Sprint 3)

**Goal:** Connect SPICE workflow events to SDK coaching logic.

Tasks:
1. Flesh out `onMorningOpen(chwId, pendingPatients?)`:
   - Run morning card selection (max 3, all skippable)
   - Log session_start + card_shown events
2. Flesh out `onAssessmentSummaryReady(assessmentData)`:
   - Build PatientSnapshot
   - Build ContextPack
   - Select mode → call backend or use cache
   - Validate output → show card or fallback
3. Flesh out `onRiskFlagObserved(riskLevel, redRiskPatient)`:
   - Layer A: any non-low risk → show coaching card
   - Scenario selection by clinical domain
4. Flesh out `onVisitCompleted(encounterId)`:
   - Map encounterId → patient_visit_id
   - Close session, log session_end
   - Trigger sync
5. Implement `onConnectivityChanged(isOnline)`:
   - Trigger sync on restore

### Phase F: Digital Proficiency Capture (Sprint 3)

**Goal:** Capture low-friction digital signals.

Tasks:
1. Observe sync attempt success/failure
2. Observe form submission success/failure (from assessment create response)
3. Log digital_help_used when CHW uses digital coaching card
4. (Deferred) Login failure observation — needs SPICE confirmation

### Phase G: Edge Mode Realignment (Sprint 4)

**Goal:** Repurpose existing on-device AI from chat to bounded coaching generation.

Tasks:
1. Create `EdgeCoachingPrompts` — bounded prompt templates for edge mode that mirror the backend's template families (T-HTN-COUNSEL, T-DIAB-COUNSEL, etc.) but optimized for Gemma 1B's capabilities
2. Wire `GemmaService` into the coaching decision engine as the edge path (currently wired to chat)
3. Ensure edge mode output goes through the same `OutputValidator` as backend responses
4. Add `LlmTrace` logging for edge inference (model_id, latency, tokens, validator outcome)
5. Verify edge mode works end-to-end: cached scenario + context pack → Gemma generation → validation → coaching card display (or fallback to cached Bangla card)

### Phase H: Voice Interface Foundation (Sprint 5+, staged)

**Goal:** Establish voice-capable contracts for future Bangla voice flows.

Tasks:
1. Define `BanglaSttEngine` interface in SDK (from PoC's proven contract)
2. Define `BanglaTtsHelper` wrapping Android OS TTS with `bn-BD` locale
3. Add voice capability flags to `MicroCoachingConfig` (`enableVoice`, `enableStt`, `enableTts`)
4. (Later) Integrate SherpaOnnx AAR for offline Bengali STT
5. (Later) Wire STT → coaching query pipeline for IT-help flow
6. (Later) Wire TTS → audio playback for coaching card content

---

## 8. What to Keep vs. Remove vs. Defer

### KEEP (these are good)

| Component | Why |
|-----------|-----|
| `MicroCoachingSDK` singleton + builder | Clean host integration pattern |
| `MicroCoachingConfig` | Centralized config, matches architecture rules |
| Room Database approach | Offline-first, typed entities — exactly what architecture wants |
| Learn module UI (screens) | ModuleReady → Lesson → Quiz → Result flow is correct for UC-1 |
| Event logging pattern | Append-only events with sync status — matches architecture |
| FAB component | Useful UI entry point for SPICE integration |
| Sample app | Good integration reference |
| Privacy-safe patient hashing | Matches no-PII-in-analytics rule |

### REALIGN (valuable, but need to serve the new architecture)

| Component | Action | Why |
|-----------|--------|-----|
| On-device Gemma inference (`GemmaService`, `LiteRtLmService`) | **Realign** — keep engines, change from chat prompts to bounded coaching generation with output validation | Edge mode is a designed part of the tri-mode architecture (Section 11.2). Gemma provides AI-quality offline coaching. Repurpose from chat to constrained scenario-driven generation |
| Model download pipeline (`ModelManager`, `ModelDownloadWorker`) | **Realign** — keep infrastructure, lower build priority than sync engine | Still needed to deliver Gemma models for edge mode and SherpaOnnx models for STT. WorkManager download + SHA-256 verification is solid |
| `InferenceRouter` | **Realign** — expand to route between online (backend), edge (Gemma), and cached modes | Currently routes between Gemma engines; should become the tri-mode selector |
| STT/TTS (from PoC) | **Integrate later** — voice-capable interfaces now, full implementation staged | Architecture Section 16 mandates Bangla voice (`REQUIRED`), staged delivery (`REQ-DEFERRED-MVP`). Keep interface contracts, integrate SherpaOnnx STT and Android TTS when voice flows are prioritized |

### DEFER (not wrong, just not the current focus)

| Component | Action | Why |
|-----------|--------|-----|
| Chat UI (`ChatScreen`, `ChatViewModel`, `CoachingChatFragment`) | **Defer** — park for future IT-help use case | Primary surface is coaching cards, not free-form chat. Chat code stays in repo for when IT-help voice/text flow is activated |
| OTel telemetry (`TelemetryManager`) | **Replace** | Architecture uses ClickHouse events via Room + sync, not OTel spans directly. Event logging pattern replaces this |
| Onboarding carousel | **Defer** — nice-to-have, not core | Focus on coaching card surface first |

### ADD (missing, needed)

| Component | Priority | Notes |
|-----------|----------|-------|
| Sync engine (outbound events + inbound scenarios) | **P0 — build first** | Critical path for offline-first |
| Backend API client (Retrofit) | **P0** | |
| Coaching card UI (composable) | **P0** | Primary UI surface |
| PatientSnapshot builder | **P0** | |
| ContextPack builder | **P0** | |
| Mode selector (online / edge / cached) | **P0** | Tri-mode, not just online/cached |
| Output validator (on-device) | **P0** | Validates both backend AND edge AI responses |
| Coaching decision engine | **P0** | |
| `LlmTrace` entity | **P0** | Logs both online and edge AI calls |
| `DigitalProficiencyEvent` entity | **P0** | |
| Digital signal capture | **P1** | |
| Edge mode coaching prompts (bounded, not chat) | **P1** | Repurpose Gemma from chat to constrained scenario generation |
| Voice interface contracts (STT/TTS) | **P2** | Architecture Section 16 requires voice-capable interfaces even if full voice is staged |

---

## 9. Package Structure (Recommended)

Align with the Mobile Development Start Plan's recommendation:

```
sdk-android/src/main/java/com/medtroniclabs/microcoaching/
├── sdk/                          # SDK entry point + SPICE integration
│   ├── MicroCoachingSDK.kt       # (existing — expand hooks)
│   ├── MicroCoachingConfig.kt    # (existing)
│   └── hooks/
│       ├── SpiceHookAdapter.kt   # Interface for SPICE to call
│       └── HookEvents.kt         # Internal event models
├── data/
│   ├── db/
│   │   ├── MicroCoachingDatabase.kt  # (existing — update schema)
│   │   ├── entity/               # (existing — realign to data design v1.1)
│   │   └── dao/                  # (existing — update)
│   ├── cache/
│   │   └── ScenarioLookup.kt    # Query cached scenarios by domain/action
│   └── repository/               # (existing — expand)
├── sync/
│   ├── SyncCoordinator.kt       # Orchestrates inbound + outbound sync
│   ├── OutboundSyncWorker.kt    # WorkManager: events → backend
│   ├── InboundSyncWorker.kt     # WorkManager: scenarios ← backend
│   └── SyncApi.kt               # Retrofit interface for sync endpoints
├── domain/
│   ├── context/
│   │   ├── ContextPackBuilder.kt
│   │   └── PatientSnapshotBuilder.kt
│   ├── decision/
│   │   ├── CoachingDecisionEngine.kt
│   │   ├── MorningCardSelector.kt
│   │   └── ModeSelector.kt
│   ├── validation/
│   │   ├── OutputValidator.kt
│   │   └── FallbackSelector.kt
│   └── telemetry/
│       ├── EventRecorder.kt
│       └── DigitalSignalRecorder.kt
├── ai/                               # On-device AI (edge mode + voice)
│   ├── inference/
│   │   ├── LLMService.kt            # (existing — common interface)
│   │   ├── GemmaService.kt          # (existing — MediaPipe engine)
│   │   ├── LiteRtLmService.kt       # (existing — LiteRT engine)
│   │   └── EdgeCoachingPrompts.kt    # NEW — bounded prompts for coaching (not chat)
│   ├── model/
│   │   ├── ModelManager.kt          # (existing — download + verify)
│   │   ├── ModelDownloadWorker.kt   # (existing — WorkManager background download)
│   │   └── ModelState.kt            # (existing)
│   └── voice/                        # Staged — interfaces now, full impl later
│       ├── BanglaSttEngine.kt        # Interface contract (from PoC)
│       └── BanglaTtsHelper.kt        # Android OS TTS wrapper
├── ui/
│   ├── coaching/                 # NEW — coaching card surface
│   │   ├── CoachingCardView.kt
│   │   ├── CoachingCardFragment.kt
│   │   └── CoachingCardState.kt
│   ├── learn/                    # (existing — keep)
│   ├── quiz/                     # (existing — keep)
│   ├── fab/                      # (existing — keep)
│   ├── onboarding/               # (existing — defer)
│   ├── chat/                     # (existing — defer, keep code for IT-help later)
│   └── common/                   # (existing components)
└── network/
    ├── CoachingApi.kt            # POST /coaching/counselling
    ├── TelemetryApi.kt           # POST /telemetry/events
    └── ScenarioSyncApi.kt        # GET /scenarios/sync
```

---

## 10. Confidence-Gated Work

From the architecture docs, not everything is confirmed for implementation yet:

### Build now (confirmed)
- Room DB entities matching data design v1.1
- Sync engine (endpoints are defined)
- Coaching card UI (approved by architecture)
- Morning card selection (deterministic, our design)
- Event logging and sync
- Scenario cache population via sync
- Quiz flow using cached questions
- Edge mode architecture (tri-mode is `VALID INTERPRETATION` per Section 11.2)
- On-device Gemma inference realignment (PoC-validated, engines already working)
- Output validator (applies to both backend and edge AI responses)

### Build with fallback (workflow-inferred)
- `onAssessmentSummaryReady` — assessment response format is documented but exact callback point in SPICE is unconfirmed
- PatientSnapshot fields — most are DOC-CONFIRMED, some are TEAM-CONFIRM
- Risk flag trigger (Layer A with published fields only)

### Stub only (blocked on SPICE confirmation)
- Exact SPICE hook callback points
- Direct SPICE Room DB access
- Exact risk enum values (Layer B)
- Equipment anomaly event source
- Login failure observability
- `patient_track_id` availability per endpoint

---

## 11. Key Mindset Shifts

| Old Thinking | New Thinking |
|-------------|-------------|
| "Build a chat SDK" | "Build a coaching card platform inside SPICE" |
| "On-device AI is the whole solution" | "Tri-mode: backend AI (primary) → on-device Gemma (offline fallback) → cached Bangla cards (safe fallback)" |
| "Model download is critical path" | "Sync engine is critical path; model download supports edge mode (important but secondary)" |
| "Chat UI is the primary surface" | "Coaching cards are the primary surface" |
| "SDK works standalone" | "SDK only makes sense embedded in SPICE" |
| "Free-form AI responses" | "Bounded, validated, scenario-driven responses — applies to BOTH backend and on-device generation" |
| "OTel for telemetry" | "Room events → sync to backend → ClickHouse" |
| "Local-only scenarios from JSON seeds" | "Scenarios from backend via sync, cached locally" |
| "STT/TTS is a nice-to-have" | "Bangla voice is REQUIRED by the product brief — staged delivery, but architecture must support it" |

---

## 12. Connection to Other Systems

### SPICE Android App (`spice-android/`)
- Our SDK is embedded as an `.aar` in SPICE
- SPICE calls our hooks (onMorningOpen, onAssessmentSummaryReady, etc.)
- SPICE provides patient context, auth tokens, and UI hosting
- SPICE uses Hilt; our SDK does NOT use Hilt (non-negotiable)
- See `spice-android/docs/extension-points.md` for integration surface

### Knowledge Layer Backend (`knowledge-layer/`)
- FastAPI service that:
  - `POST /coaching/counselling` — scenario retrieval + AI generation + validation
  - `GET /scenarios/sync` — differential scenario bundles for devices
  - `POST /telemetry/events` — ingest device events (target, not yet built)
  - `POST /coaching/quiz-answer` — quiz grading (target, not yet built)
  - `POST /coaching/it-help` — IT help queries (exists, deferred for mobile)
  - `GET /scenarios/morning/{chw_id}` — morning cards (target, not yet built)
- Currently has: PDF ingestion, scenario extraction, coaching counselling endpoint, IT help, scenario sync, quiz answer
- Needs: telemetry ingestion, gap profile management, dashboard APIs, learning path management

### What's the order of work across systems?

1. **SDK: Schema realignment + sync engine** (can start immediately)
2. **Backend: Telemetry ingestion endpoint** (`POST /telemetry/events`) — needed for sync
3. **SDK: Coaching card UI + decision engine** (parallel with backend work)
4. **SDK + SPICE: Hook integration** (needs SPICE sandbox access)
5. **Backend: Dashboard APIs** (after telemetry flows)

---

## 13. Questions to Resolve

These are open items from the docs:

1. **SPICE hook points** — Which exact Activities/Fragments in SPICE will call our SDK hooks? Needs sandbox access.
2. **Assessment response fields** — Does `bpLog.avgSystolic` from assessment create reflect the just-entered BP or a historical average?
3. **patient_track_id** — Is it available in all SPICE contexts we need?
4. **Auth model** — How does the SDK get a SPICE bearer token to call the Knowledge Layer backend?
5. **Risk enum values** — What exact strings does SPICE use for risk levels beyond "Low risk" / "High risk"?
6. **Equipment anomaly** — How would the SDK detect a glucometer "HI" reading?
7. **Login failure** — Can SPICE expose login failure events to the SDK?

---

*This document should be updated as SPICE integration questions are answered and as the Knowledge Layer backend endpoints mature.*
