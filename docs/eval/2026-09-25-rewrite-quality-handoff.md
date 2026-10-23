# Offline chat: where we are, and making the rewrite good

**Date:** 2026-09-25
**Branch:** `feat/hybrid-dense-retrieval`
**Purpose:** context for the next working session. It covers what changed in the SDK, the
evaluation harness, what the first judged run showed, and the next steps proposed so far.

---

## 1. What changed in the SDK

**The serve decision is now three steps, and only the last one can refuse.**
- `CardEvidence` reads the question and records what each candidate shares with it.
- `CardRanker` picks one card from the fused candidates, using the old comparator unchanged.
- `ServeGate` serves or refuses that one card. It never substitutes another.

A pick that fails the gate refuses the turn; the old gate fell through to a lower candidate.
See `docs/references/serve-gate.md`.

**Nothing after the gate can refuse the card.** When the groundedness check or the output
validator rejects the model's answer, the card the gate passed is shown verbatim. The old
fallback re-ran the gate with a minimum score of 20; that re-check and
`ChatTuning.minFallbackServeScore` are removed.

**Every stage writes one trace line** under the `ChatTrace` tag:

```
FUSED n=3 order=[952fbce0:7 bm25=1/177.4 dense=2/0.57, e660517c:2 bm25=2/173.6 dense=1/0.57, 952fbce0:0 bm25=3/111.6 dense=-]
RANK pick=952fbce0:7 fused=1 band=[952fbce0:7,e660517c:2,952fbce0:0] | <evidence of each candidate>
GATE serve 952fbce0:7
POST groundedness=0.70 floor=0.25 validator=ok translation=ok shown=rewrite
```

`SERVE-DECISION` is gone.

**Measured on the question banks** (JVM replay):
- Wrong-family serves did not grow in any set.
- Off-topic refusals on the CHW set rose from 8/10 to 10/10.
- As accepted in advance, false refusals rose: QA UC2 from 8 to 12, QA UC3 from 15 to 20.

---

## 2. The evaluation harness, `chat-eval/`

A Python project beside `sdk-android/`. It runs the question bank on a device through Appium
and reads each turn's stage lines from the app log. It decides a verdict only where card
identity settles it:
- an off-topic question refused
- a refusal of an answerable question
- the expected card shown verbatim

Every other row goes on a judging sheet for a capable model or a person to judge, against the
rules in `chat-eval/JUDGING.md`. Nothing scores text by word overlap: that method cannot judge a
paraphrase, and on Bangla it strips vowel signs.

**What is in a run folder.** Each run exports the modules that were on the device into
`runs/<run>/modules.json`, at the start and again at the end. `check` and `report` score against
that export. The report shows every question with each stage, the text the CHW saw, and the
verdict with its reason. Run output is gitignored.

**Commands:**

```bash
chat-eval doctor
chat-eval setup --mode assisted          # or direct
chat-eval run --source qa-uc2-2026-09-17 --mode assisted --out runs/<name>
chat-eval check runs/<name>
# judge: read runs/<name>/judge_sheet.md, write a verdict file
chat-eval verdicts runs/<name> <verdict-file>
chat-eval report runs/<name>
```

**Setup needed:**
- Appium 3 with the UiAutomator2 driver
- a debug build of the host, so the module export can read the database
- a signed-in app. The session had expired on 2026-09-25, and `local.toml` holds no credentials.

---

## 3. What the first judged run showed

100 UC2 questions in assisted mode (BM25 + dense + Qwen3 0.6B rewrite) on the emulator,
2026-09-24:

| What happened | Rows | CORRECT |
|---|---|---|
| Off-topic, refused | 5 | 5 |
| Right card, shown verbatim (rewrite failed groundedness) | 18 | 18 |
| Right card, reworded by the model | 59 | 8 (28 PARTIAL, 23 INCORRECT) |
| Answerable but refused | 13 | 0 |
| Wrong or neighbouring card | 5 | 0 |
| **Total** | 100 | **31** |

Retrieval and the gate put the right card in front of the CHW 77 times. **The rewrite loses it.**
Earlier "71 to 78%" figures were card scores; they never judged the answer text.

**What went wrong in the 51 failed rewrites:**

| Failure | Rows |
|---|---|
| Garbled wording or dropped key points | 28 |
| Claims not on the card | 11 |
| "The information doesn't mention this", when the card does | 7 |
| A fragment such as "respiratory system" | 5 |

**Groundedness let every one of them through.** The lowest scored 0.26 against a 0.25 floor;
the median was 0.52. It checks that the answer's words are in the card, not that the answer
covers the card.

**BM25-only devices (under 3 GB)** show the card verbatim. The JVM replay gives 71/95 exact cards
for BM25 only, so they should score about 76 CORRECT out of 100. That is more than twice the
assisted path. It is not yet measured on a device; see step 1 below.

---

## 4. Why the rewrite fails: the translation, then the model

The model is prompted in English. So each assisted turn runs:

1. ML Kit translates the Bangla question to English.
2. ML Kit translates the Bangla card to English.
3. Qwen3 0.6B answers in English.
4. ML Kit translates the answer back to Bangla.

The trace shows damage at every translation step:
- **The question.** "করণীয় কী?" ("what should be done?") becomes "What is the key?"; the model then
  answered "The key is health." "শ্বাসতন্ত্র কী?" becomes "What is the respiratory? What is the
  health worker?".
- **The card.** The iron-tablet card loses "for three months" and "at noon", so the model never
  sees them.
- **The answer.** A correct English answer comes back as "the health worker should breastfeed the
  newborn"; "calcium tablets" comes back as "calcium roses".

Some failures are the model's own. On q019 the English card was readable and still contained
the symptoms, but the model said it did not.

**Spike on 8 failed rows, run locally with Ollama.** This is throwaway and directional: a
different 4-bit build than the device's, 8 rows, judged in-session. The scripts are in the
session scratchpad only.

| Input to the model | Qwen3 0.6B | Gemma 3 1B |
|---|---|---|
| ML Kit English, as today | 1 CORRECT, 4 PARTIAL, 3 INCORRECT | not run |
| Clean English (hand translation) | 3 CORRECT, 5 PARTIAL | better, but chatty |
| All Bangla, no translation | 1 CORRECT, 3 PARTIAL, 4 INCORRECT | fluent and structured, adds claims ("TB is also known as malaria") |

What this suggests:
- **Clean input is the biggest single lever.** The same Qwen model given clean English improved
  clearly.
- **Qwen3 0.6B cannot work in Bangla directly.** It repeats the question back, loops, or
  reverses meaning.
- **Gemma 3 1B reads Bangla well but invents things.** It needs a stricter prompt and a
  measurement before it can be trusted.

---

## 5. Next steps

In rough order. Each is measured by the same harness on the same 100 questions.

1. **Measure BM25-only on a device.** Run the full bank in direct mode with the encoder files
   moved aside, so the emulator behaves like an under-3 GB device. This confirms the ~76
   estimate, and gives the bar the assisted path has to beat.
2. **Better translation.** The input side matters most. Options:
   - **English card text from the backend.** The card schema already has `body.en`; it is empty
     in today's corpus. A good offline translation, reviewed once, would remove step 2 of the
     chain entirely.
   - **The question.** Only step 1 remains on the device. A small map of the question-form
     phrases CHWs use ("করণীয় কী", "কী পরামর্শ দিব", "SK হিসেবে …") to fixed English could run
     before ML Kit. Or the prompt could ask the model to explain the card simply, without
     translating the question at all.
   - **A better on-device translator,** if ML Kit stays in the path. It needs a size and quality
     comparison.
3. **Gemma 3 1B with a stricter prompt.** It is already in `ModelCatalog`. The prompt should
   forbid anything not on the card and ask for the card's points in order, to curb the added
   claims seen in the spike. Try it in Bangla and in clean English.
4. **Look for better Bengali models or translators.** Compare quality against size, RAM tier
   and licence, using the same 8-row Ollama spike first and then the harness.
5. **A coverage check beside groundedness.** A rewrite that drops most of the card's content
   falls back to the card. It catches fragments and dropped points; it does not catch added
   claims or mistranslation.
6. **Last resort: drop the model rewrite** and show the card verbatim in assisted mode too.
   Verbatim scored 18/18 on this run. We would rather not end up here.

---

## 6. Open items

- **The human spot-check.** 20 rows are in `chat-eval/runs/full-assisted/human_spotcheck.md`.
  Judge them, then import with `chat-eval verdicts runs/full-assisted <file> --as human`. The
  report will then show agreement with the model judge.
- **13 answerable questions are refused.** Most are the vocabulary gap described in
  `docs/references/gate-vocabulary.md`. Some are cards whose bodies are trainer-session text:
  the immunisation module, including `8f41be1a:3`, which the facilitator filter does not catch.
- **The QA team's harness.** Share `chat-eval/` and agree the judging rules with the QA
  developer.
- **Deferred minors from the code review**, all in `chat-eval` unless noted:
  - an `--only` rerun of a newly added bank id is left out of checks
  - `bank_sha256` ignores `--bank`
  - the airplane-mode command needs Android API 30 or newer
  - stderr is not forced to UTF-8
  - a serve followed by a refusal outcome is labelled as a rewrite
  - the report header prints nested fields as raw dicts
  - the foreign-text check is sensitive to line breaks
  - `CardRanker` (SDK) has an unreachable fallback
  - the `RANK` line (SDK) mixes key formats
- **App behaviour, not changed:** switching to exact words pre-ticks "also remove the download".

## Where things are

| What | Path |
|---|---|
| Serve gate, trace lines, measurements | `docs/references/serve-gate.md` |
| Vocabulary sets behind the gate | `docs/references/gate-vocabulary.md` |
| Harness usage, formats, judging rules | `chat-eval/README.md`, `chat-eval/FORMATS.md`, `chat-eval/JUDGING.md` |
| The judged run | `chat-eval/runs/full-assisted/` (local only) |
| Specs and plans | `docs/superpowers/specs/2026-09-24-*`, `docs/superpowers/plans/2026-09-24-*` (local only) |
| Implementation ledgers, device logs, eval diff | `ignored/2026-09-25-ranker-gate-and-harness/` (local only) |
