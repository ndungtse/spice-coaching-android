# Chat (IT-Help) — How It Works Under the Hood

**Version:** 0.3.8-SNAPSHOT · **Date:** 2026-06-03 · **Status:** Draft

How the SDK's on-device AI chat answers a CHW's question. This is the internal design behind the chat surfaces a host embeds (see [03 — UI Embedding](../documentation/03-ui-embedding.md) for the public API). It is **offline-first and on-device** — there is no online LLM fallback.

---

## The pipeline

A Bangla question travels through scope-gating, retrieval-grounding, the on-device LLM, output validation, and translation before it renders. Every guardrail that can run **without** invoking the LLM runs first — the model is the last line, not the first.

```
Bangla text  (or voice → STT)
      │
      ▼
  tokenize (BN)
      │
      ▼
  L1 scope classifier ──── out-of-scope ──► canned refusal  (no LLM call)
      │ in-scope
      ▼
  ML Kit  BN → EN
      │
      ▼
  BM25 retrieval over the synced module corpus (ModuleKnowledgeIndex)
      │
      ├──── L2: top score below threshold ──► canned "no grounding" refusal (no LLM)
      │ grounded
      ▼
  build grounded prompt  (English reference block + L3 hardened directives)
      │
      ▼
  Gemma 3  (on-device LLM, EN → EN)
      │
      ▼
  L4 OutputValidator (drug / dosage / length / sentinel) ── reject ──► quiz-explanation fallback
      │ pass
      ▼
  ML Kit  EN → BN  ── L5 fidelity check ── fail ──► EN body + "(অনুবাদ অনুপলব্ধ)" prefix
      │
      ▼
  render  +  L6 telemetry (it-help / chatbot coaching_event)
```

---

## Entry surfaces & session

The chat is reached through `CoachingChatFragment`, `CoachingChatBottomSheet`, or the `ChatFab` composable (see [03 — UI Embedding](../documentation/03-ui-embedding.md)). There is no `openChat()` API — hosts launch the surfaces directly.

Each open creates a fresh `ChatSession` (UUID-keyed). The session clamps context to keep the prompt small: ~3 prior exchanges (6 turns), a ~2000-character prompt cap, and per-message truncation (~600 chars). The Gemma chat template carries a Bangla or English system prompt depending on `MicroCoachingConfig.language`.

---

## On-device model & inference

- **Model:** Gemma 3 1B INT4, on-device, `.task` (MediaPipe) or `.litertlm` (LiteRT-LM). Inference knobs: `inferenceTemperature` (0.6 default), `maxInferenceTokens` (512 default).
- **Mode:** the `InferenceRouter` resolves `ONLINE` / `EDGE` / `CACHED`. In practice the chat runs **edge** (on-device) — the online RAG route exists but is dormant. `forcedMode` can pin a mode for dev/test.
- **Low-end devices** (< ~3 GB RAM): no model is loaded. The chat degrades to **retrieval-only** — it serves pre-authored, clinician-reviewed Bangla card/quiz text from the corpus with no LLM round-trip. See [05 — Model & Voice](../documentation/05-model-and-voice.md#low-end-devices).

Model download/verification/state is owned by `ModelManager` — see [05 — Model & Voice](../documentation/05-model-and-voice.md).

---

## Retrieval grounding (BM25)

The chat is **grounded** — it answers from the synced learning corpus, not free-form world knowledge.

- **Index:** `ModuleKnowledgeIndex` builds an inverted index from `ModuleDao` at SDK init and rebuilds after a successful module sync. It indexes both module **cards** (title + body + next-action, BN and EN) and **quizzes** (case + question + correct options + explanation).
- **Scorer:** Okapi **BM25** (`k1≈1.5`, `b≈0.75`) — pure-Kotlin, < 20 ms per query, no embedding model (deliberately: keeps the APK small and latency low at pilot corpus size).
- **Tokenization:** whitespace + Bangla/Latin punctuation, with Bangla **bigram** fallback for short queries (captures compounding without a stemmer).
- **Top-K = 2**, with quiz hits boosted over card hits (quizzes are reviewed Q&A pairs). A score-threshold gate (≈3.0 BM25) decides whether there is enough grounding to answer at all.
- The reference block injected into the prompt is **English-only** (the model is English-dominant); chunks without English text are translated once at index time and cached.

---

## Guardrails (defence in depth)

| Layer | When | Catches | Cost |
|---|---|---|---|
| **L1 — Scope classifier** | Pre-LLM, after tokenization | Off-topic questions (cooking, sports, personal advice) | < 5 ms |
| **L2 — Retrieval threshold** | Post-BM25, pre-LLM | In-domain questions the corpus doesn't cover | ~0 (part of retrieval) |
| **L3 — Hardened system prompt** | Inside the LLM | Drift, hallucinated drugs/dosages, "as an AI…" patter; emits a `REFUSE_NO_GROUND` sentinel when uncovered | 0 (prompt text) |
| **L4 — Output validator** | Post-LLM, pre-translation | Drug names / dosage numbers not in the source chunks, length blow-out, sentinel leaks | < 10 ms |
| **L5 — Translation fidelity** | Post EN→BN | Empty / Latin-only / mangled translation → falls back to EN with a caveat prefix | < 5 ms |
| **L6 — Telemetry** | Every outcome | Tuning data — records the outcome + retrieval score + chunk ids | cheap |

When L4 rejects an LLM answer but a quiz chunk was retrieved, the chat serves that quiz's **authored `explanation_bn` verbatim** — pre-authored, clinician-reviewed Bangla — rather than a blank refusal.

### Scope strictness

`MicroCoachingConfig.chatScopeStrictness` tunes how aggressively the chat refuses (effective default via the Builder is **`Strict`** — see [02 — config reference](../documentation/02-initialization.md#llm--inference--model-download)):

- **`Strict`** — an L1 keyword miss or L2 retrieval miss is a hard refusal, without ever calling the LLM. Cheapest; most aggressive at rejecting uncovered questions.
- **`ExtendedClinical`** — L1 is advisory; on a retrieval miss the LLM is called with an open-scope clinical prompt and either answers with a "consult your supervisor" caveat or emits the refusal sentinel. One LLM round-trip per message.

### Refusal taxonomy

Refusals are canned, clinician-authored Bangla strings (keyed `chat_refusal_*` in `strings.xml` / `values-bn`), never generated by the LLM:

| Outcome | Trigger |
|---|---|
| `refused_scope` | L1 — out of clinical/SPICE scope |
| `refused_no_ground` | L2 — in-scope but uncovered by the corpus |
| `refused_unsafe` | L4 — validator rejected the LLM output |
| `translation_degraded` | L5 — translation unusable; English served with a caveat |

---

## Translation pipeline

The chat preserves a full BN → EN → LLM → EN → BN round-trip via ML Kit `OnDeviceTranslator`:

- Bangla input is translated to English before retrieval/prompt-build; Gemma replies in English; the reply is translated back to Bangla before render.
- `MicroCoachingSDK.translationModelState` gates the UI: suggestion chips stay hidden until the BN↔EN pack is `Ready`; a `Downloading` pack blocks send with a "Bangla loading" state; a `Failed` pack falls back to **BN-only retrieval + BN prompt** (degraded but functional).

---

## Voice

- **TTS ("speak out loud"):** `CoachingTtsHelper` wraps Android `TextToSpeech` (locale `bn-BD` in chat). On a missing Bengali voice pack it fires the system installer intent and reports a `LanguageMissing` state. The lesson player uses the same helper.
- **STT (mic input):** Android's platform `SpeechRecognizer` handles English (on-device + cloud) and online Bengali. **Offline Bengali** is provided by the optional `:sdk-android-sherpa` engine, wired via `Builder.offlineSttEngineFactory(SherpaOnnxStt.factory)`. Hosts can also supply a custom `voiceInputController`. See [05 — Model & Voice](../documentation/05-model-and-voice.md#voice--stt).

---

## Telemetry

Every chat response generation emits one `coaching_event` row (`event_family = "it-help"`, `event_type = "chatbot"`), capturing `validator_status`, `fallback_used`, `inference_mode` (`edge`), `network_state`, and — on refusals — a `payload_json` with the refusal outcome, retrieval top-score, and grounded chunk ids. Rows persist via `CoachingEventDao` and ship on the next `OutboundSyncWorker` cycle. Chat **messages** themselves persist separately in `chat_messages`. Telemetry export is gated by `enableTelemetry` (off by default) — see [02 — Telemetry](../documentation/02-initialization.md#telemetry-opentelemetry-configuration).

---

## Related

- [03 — UI Embedding](../documentation/03-ui-embedding.md) — the public chat surfaces.
- [05 — Model & Voice](../documentation/05-model-and-voice.md) — model download lifecycle and STT.
- [docs/SDK.md](../SDK.md) — component-level reference (LLM service, networking, Room schema).
