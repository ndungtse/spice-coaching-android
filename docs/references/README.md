# SDK Reference — How It Works Under the Hood

**Version:** 0.3.8-SNAPSHOT · **Date:** 2026-06-03 · **Status:** Draft

This folder explains **how the SDK works internally** — the mechanisms behind the features. It complements the [integration guide](../documentation/README.md), which covers **how to use** the SDK from a host app.

> **Note:** this is reference material for understanding behaviour and debugging — not API contract. Public API signatures live in the [integration guide](../documentation/README.md); component-level internals live in [docs/SDK.md](../SDK.md).

---

## In this folder

| Doc | Topic |
|---|---|
| [chat.md](./chat.md) | How the on-device AI chat (IT-help) works — the input→retrieval→LLM→output pipeline, grounding, guardrails, translation, voice, and telemetry. |

---

## Other internals — where they're documented

The chat pipeline is the deepest under-the-hood subsystem and gets its own page above. The remaining internals are documented in already-tracked locations:

| Subsystem | What it does | Where |
|---|---|---|
| Components & module layout | Every SDK package/component, OTel pipeline, LLM service, networking, Room schema, data-access patterns | [docs/SDK.md](../SDK.md) |
| Inference mode selection | How the SDK chooses `ONLINE` / `EDGE` / `CACHED` (and the `forcedMode` override) | [documentation/02 — Initialization](../documentation/02-initialization.md#re-initializing-after-login-jwt) |
| On-device model lifecycle | Download strategies/providers, `ModelManager` + `ModelState`, low-end retrieval-only mode | [documentation/05 — Model & Voice](../documentation/05-model-and-voice.md) |
| Gap detection | Synced rule evaluation inside `onAssessmentSubmitted` | [docs/gaps/GAP_DETECTION_SDK.md](../gaps/GAP_DETECTION_SDK.md), [docs/gaps/GAPS_TEST.md](../gaps/GAPS_TEST.md) |
| Workflow hooks & data boundary | Lifecycle hooks, push/pull data interfaces, the SPICE↔SDK data boundary | [documentation/04 — Hooks & Data](../documentation/04-hooks-and-data.md) |

---

## SDK internals at a glance

```
                       MicroCoachingSDK (singleton)
   ┌──────────────────────────────────────────────────────────────┐
   │  InferenceRouter ── picks ONLINE / EDGE / CACHED               │
   │        │                                                       │
   │        ├─ EdgeInference ── Gemma 3 (on-device, .task/.litertlm)│
   │        └─ (online RAG route is dormant — edge-only today)      │
   │                                                                │
   │  ModuleKnowledgeIndex ── BM25 retrieval over synced module     │
   │                          cards + quizzes (chat grounding)      │
   │                                                                │
   │  ModelManager ── download / verify / state machine             │
   │  SyncCoordinator ── periodic + connectivity-triggered sync     │
   │  TelemetryManager ── OpenTelemetry spans (optional)            │
   │  OnDeviceTranslator ── ML Kit BN↔EN                            │
   │  microcoaching.db (Room) ── chat, events, modules, cache       │
   └──────────────────────────────────────────────────────────────┘
```

Two hard rules shape every internal: **no online LLM fallback** (inference is on-device Gemma only, with a retrieval-only degrade path on low-RAM devices), and the SDK's `microcoaching.db` is **fully separate** from the host's database.
