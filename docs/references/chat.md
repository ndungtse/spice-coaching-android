# Chat — How It Works Under the Hood

**Status:** current for `feat/hybrid-dense-retrieval`

How the SDK's AI chat answers a CHW's question. This is the internal design behind the chat
surfaces a host embeds (see [03 — UI Embedding](../documentation/03-ui-embedding.md) for the
public API). Online, the backend answers. Offline, everything below runs on the phone.

---

## Which pipeline answers

`resolveAnswerMode` picks one of three modes for every message, from a stored preference,
connectivity, device memory, consent and model state:

| Mode | When | What answers |
|---|---|---|
| `ONLINE` | the user prefers online and a network is available | backend RAG (`POST /coaching/rag-query`) |
| `ON_DEVICE_ASSISTED` | device has ≥ 3 GB RAM, the user opted in, the model file is present and the engine is loaded | on-device retrieval, then the small model rewords the one card the gate chose |
| `ON_DEVICE_DIRECT` | anything else | on-device retrieval, then the card is served as written |

Direct is the floor every other mode falls back to. Retrieval picks the same card in both
on-device modes; the model rewords the result, it does not choose it.

The on-device model is the catalog default, Qwen3-0.6B mixed INT4 on LiteRT-LM
(`ModelCatalog.DEFAULT_ID`). Gemma 3 entries in the catalog are rollback targets. The
query encoder for dense retrieval is EmbeddingGemma-300m, downloaded with the model.

---

## The offline pipeline

```
Bangla text  (or voice → STT)
      │
      ▼
  L0 deny-list (ScopeClassifier.isOutOfScope) ── hit ──► canned refusal, no retrieval
      │
      ▼
  ML Kit  BN → EN   (the English copy joins the question for the gate)
      │
      ▼
  BM25 over every card  +  dense cosine over every card vector
      │
      ▼
  rank fusion  →  top 3 candidates                    ── see retrieval.md
      │
      ▼
  ranker picks one card, then the gate serves or refuses it  ── see serve-gate.md
      │                ── refuse ──► canned "no grounding" refusal
      │ one card
      ├── ON_DEVICE_DIRECT ─────────────────────────► card text served as written
      │
      ▼ ON_DEVICE_ASSISTED
  prompt = that one card's text + the question + two instructions
      │
      ▼
  Qwen3-0.6B  (EN → EN)
      │
      ▼
  groundedness check ── under floor ──► the card itself is served instead
      │
      ▼
  output validator ── rejected ──► the card itself is served instead
      │
      ▼
  EN → BN translation, then the Latin-share guard
      │
      ▼
  L4 OutputValidator (drug / dosage / length) ── reject ──► card served instead
      │
      ▼
  ML Kit  EN → BN  ── L5 fidelity check ── fail ──► EN body with a caveat
      │
      ▼
  render  +  telemetry
```

Every guardrail that can run without the model runs first. The model is the last step, not
the first.

---

## Retrieval and the gate

Retrieval runs two searches over the whole corpus, word-based BM25 and meaning-based dense
vectors, and merges their rankings by rank position. The serve gate then decides whether any
of the top three candidates demonstrably shares the question's subject, taking population
scope into account, and refuses otherwise.

- [retrieval.md](./retrieval.md): both searches, the fusion arithmetic with a worked example,
  and why the phone does not copy the backend's design.
- [serve-gate.md](./serve-gate.md): the five steps, the population veto, every refusal reason
  with its threshold, and a worked example.

---

## One card reaches the model

`ChatTuning.llmContextCards` is 1. The gate's chosen card is placed first, the other candidates
after it, all are translated to English, and the list is cut to one before the prompt is
built. The cut happens before the prompt so that everything downstream judges the answer
against exactly what the model saw: the groundedness check, the drug and dosage block-lists,
and the attribution chip.

The prompt is deliberately spare (`buildContextAnswerPrompt`): the card text, the question,
and two instructions to answer from the text and add nothing. No labels, no turn markers, no
refusal sentinel. The card text is the linked quiz explanation when the card has one,
otherwise the body clipped to its last complete sentence: the same text the direct mode would
show, so the model's only job is to reword it.

Conversation history is not replayed to the model. Each turn is independent; the history
stays visible in the UI only.

---

## Guardrails (defence in depth)

| Layer | When | Catches |
|---|---|---|
| **L0 — Deny-list** | before retrieval | Off-topic questions the model has answered wrongly before (weather, sports, recipes). Canned refusal, no retrieval. |
| **L1 — Scope classifier** | advisory under `ExtendedClinical`, a hard gate under `Strict` | Questions with no clinical or workflow vocabulary. |
| **L2 — Ranker and serve gate** (`CardRanker`, `ServeGate`) | after retrieval, before the model | The ranker picks one card; the gate refuses it when it shares no subject with the question or is scoped to a different population. Evidence-based, not a score threshold. A refused pick refuses the turn. |
| **L3 — Prompt** | inside the model | The instruction to use only the card text. |
| **Groundedness** | after the model | Content-word overlap with the card under 0.25 (strong retrieval) or 0.35. The card the gate passed is served verbatim instead; this check never refuses. |
| **L4 — Output validator** | after the model | Drug names or dosage numbers not in the card, over-length answers. The card is served instead. |
| **L5 — Translation fidelity** | after EN→BN | Empty, Latin-only or mangled translation. English served with a caveat. |
| **Telemetry** | every outcome | The outcome, retrieval score and chunk ids, for tuning. |

### Scope strictness

`MicroCoachingConfig.chatScopeStrictness`:

- **`Strict`**: an L1 miss or a gate refusal is a hard refusal, without calling the model.
- **`ExtendedClinical`**: L1 is advisory; the deny-list and the gate are what refuse.

### Refusal taxonomy

Refusals are canned, clinician-authored Bangla strings, never generated by the model:

| Outcome | Trigger |
|---|---|
| `refused_scope` | L0 deny-list, or L1 under `Strict` |
| `refused_no_ground` | the serve gate refused the ranker's pick |
| `refused_unsafe` | L4 rejected the answer and no card could be served instead |
| `translation_degraded` | L5: translation unusable, English served with a caveat |

---

## Entry surfaces & session

The chat is reached through `CoachingChatFragment`, `CoachingChatBottomSheet`, or the
`ChatFab` composable (see [03 — UI Embedding](../documentation/03-ui-embedding.md)). Each
open creates a fresh `ChatSession`. The answering-style sheet lets the user choose between
the verbatim card (direct) and the reworded answer (assisted); choosing direct unloads the
engine but can keep the model file.

---

## Translation pipeline

Bangla input is translated to English by ML Kit before retrieval, and the English copy joins
the typed Bangla in the gate's question so evidence can match either language. The model is
prompted in English and its reply is translated back to Bangla before render.
`MicroCoachingSDK.translationModelState` gates the UI: a `Downloading` pack blocks send; a
`Failed` pack falls back to Bangla-only retrieval with the card served in Bangla.

---

## Voice

- **TTS ("speak out loud"):** `CoachingTtsHelper` wraps Android `TextToSpeech` (locale
  `bn-BD` in chat). On a missing Bengali voice pack it fires the system installer intent and
  reports a `LanguageMissing` state.
- **STT (mic input):** Android's platform `SpeechRecognizer` handles English and online
  Bengali. Offline Bengali is the optional `:sdk-android-sherpa` engine, wired via
  `Builder.offlineSttEngineFactory(SherpaOnnxStt.factory)`. See
  [05 — Model & Voice](../documentation/05-model-and-voice.md#voice--stt).

---

## Telemetry

Every response emits one `coaching_event` row (`event_family = "it-help"`,
`event_type = "chatbot"`) with the validator status, whether a fallback was used, the answer
mode, the network state and, on refusals, the refusal outcome, retrieval top score and
grounded chunk ids. Rows persist via `CoachingEventDao` and ship on the next
`OutboundSyncWorker` cycle. Export is gated by `enableTelemetry` (off by default).

---

## Related

- [retrieval.md](./retrieval.md) and [serve-gate.md](./serve-gate.md), the two pages this one points at.
- [03 — UI Embedding](../documentation/03-ui-embedding.md), the public chat surfaces.
- [05 — Model & Voice](../documentation/05-model-and-voice.md), model download lifecycle and STT.
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md), component-level reference.
