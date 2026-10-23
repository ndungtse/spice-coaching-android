# SDK Reference — How It Works Under the Hood

**Version:** 0.6.0-SNAPSHOT · **Date:** 2026-08-31 · **Status:** Draft

This folder explains **how the SDK works internally** — the mechanisms behind the features. It complements the [integration guide](../documentation/README.md), which covers **how to use** the SDK from a host app.

> **Note:** this is reference material for understanding behaviour and debugging — not API contract. Public API signatures live in the [integration guide](../documentation/README.md); the full structural picture lives in [docs/ARCHITECTURE.md](../ARCHITECTURE.md).

---

## In this folder

| Doc | Topic |
|---|---|
| [chat.md](./chat.md) | How the on-device AI chat works — the input→retrieval→LLM→output pipeline, grounding, guardrails, translation, voice, and telemetry. |
| [retrieval.md](./retrieval.md) | BM25 and dense retrieval, how the two rankings are fused, with a worked example on real corpus questions. |
| [serve-gate.md](./serve-gate.md) | Fusion order, the ranker's pick, the gate's serve or refuse, every refusal reason, the post-model checks, and the trace lines. |
| [gate-vocabulary.md](./gate-vocabulary.md) | Every word list retrieval and the gate compare against: gazetteer, synonyms, template/demographic/population words, where each comes from, and how much of the corpus it covers. |

---

## Other internals — where they're documented

The chat pipeline is the deepest under-the-hood subsystem and gets its own page above. The remaining internals are documented in already-tracked locations:

| Subsystem | What it does | Where |
|---|---|---|
| Architecture, components & module layout | Every SDK package/layer, composition root, Room schema, sync, workflows, decisions | [docs/ARCHITECTURE.md](../ARCHITECTURE.md) |
| Answer-mode selection | How chat routes between `ONLINE` / `ON_DEVICE_ASSISTED` / `ON_DEVICE_DIRECT` | [ARCHITECTURE.md §5.3](../ARCHITECTURE.md#53--chat--ai-pipeline) |
| On-device model lifecycle | Download strategies/providers, `ModelManager` + `ModelState`, consent gating, low-end retrieval-only mode | [documentation/05 — Model & Voice](../documentation/05-model-and-voice.md) |
| Gap detection | Internal, always-on rule evaluation — referral-correctness runs inside `onReferralSubmitted` | [docs/gaps/GAP_DETECTION_SDK.md](../gaps/GAP_DETECTION_SDK.md), [documentation/04 — Hooks & Data](../documentation/04-hooks-and-data.md#gap-detection) |
| Workflow hooks & data boundary | Lifecycle hooks, push/pull data interfaces, the host↔SDK data boundary | [documentation/04 — Hooks & Data](../documentation/04-hooks-and-data.md) |

---

## SDK internals at a glance

```
                       MicroCoachingSDK (singleton)
   ┌──────────────────────────────────────────────────────────────┐
   │  AnswerModeResolver ── ONLINE / ON_DEVICE_ASSISTED / DIRECT   │
   │        │                                                      │
   │        ├─ ChatBackendAnswerer ── POST /coaching/rag-query     │
   │        └─ ChatLocalAnswerer ── BM25 + dense → fusion →         │
   │                                ranker → ServeGate →           │
   │                                LiteRT-LM (Qwen3, downloaded)  │
   │                                                               │
   │  ModuleKnowledgeIndex ── BM25 retrieval over synced module    │
   │                          cards (chat grounding, EN + BN)      │
   │                                                               │
   │  ModelManager ── consent-gated download / state machine       │
   │  SyncCoordinator ── 15-min periodic + triggered sync          │
   │  TelemetryManager ── OpenTelemetry spans (optional)           │
   │  OnDeviceTranslator ── ML Kit BN↔EN                           │
   │  microcoaching.db (Room, v36) ── modules, events, chat, state │
   └──────────────────────────────────────────────────────────────┘
```

Two hard rules shape every internal: **offline chat never serves ungrounded LLM output** (`ServeGate` serves or refuses the ranker's one card before any LLM call, degrading to clinician-authored card text on low-RAM or model-less devices), and the SDK's `microcoaching.db` is **fully separate** from the host's database.
