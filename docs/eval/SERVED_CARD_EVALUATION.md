# Served-card evaluation: how the SDK team measures retrieval

**Scope:** the offline chat paths, `ON_DEVICE_DIRECT` (BM25 only) and `ON_DEVICE_ASSISTED`
(BM25 + dense, model rewrite)
**Unit scored:** the card the serve gate chose, read from the `SERVE-DECISION` trace line
**Evaluator:** deterministic, no text similarity, no LLM judge
**Companion:** [UC2_CORRECTNESS_EVALUATION.md](./UC2_CORRECTNESS_EVALUATION.md) describes
the QA team's harness, which scores the displayed answer text. Section 5 compares the two.

---

## 1. What is measured, and why the card

The offline pipeline is retrieval → gate → optional rewrite. The gate is the only thing that
decides *what* the CHW is told; the model can only reword the card the gate chose, and the
groundedness gate replaces a bad rewrite with the card verbatim. So the question "did the
chat pick the right content" is answered by one field: which card the gate served, or that
it refused.

Every run, whether a JVM replay or a device run, ends in the same six verdicts per question
(`EvalReport.kt`):

| Verdict | Condition |
|---|---|
| `STRICT` | served card is in the question's acceptable set |
| `FAMILY` | served card is a different card of the same module family |
| `WRONG_FAMILY` | served card is in another module |
| `FALSE_REFUSE` | refused, but the question has an acceptable card |
| `GOOD_REFUSE` | refused, and the question is off-topic |
| `SERVED_IRRELEVANT` | served anything to an off-topic question |

Reported as one table shape everywhere:

```
exact card                          78/95
right module (exact + neighbour)    83/95
wrong family served                 3
false refusals                      9
off-topic refused                   5/5
off-topic answered                  0
```

The denominator for the first two lines is the clinical questions only (100 minus the 5
off-topic). No partial credit, no weighting: a neighbour card is reported as a neighbour, not
as half right.

The **acceptance bar** the gate change was measured against: wrong family ≤ 4, false
refusals ≤ 3, exact card ≥ 75/95, off-topic refused 5/5.

---

## 2. The question bank

One file, `sdk-android/src/test/resources/retrieval/qa_questions_bn.json`, one row per
question, 200 rows today under two `source` values (the QA team's UC2 bank of 17 September
and UC3 bank of 21 September). New QA runs append rows under a new source; nothing is
overwritten.

| Field | Meaning |
|---|---|
| `id` | `uc2_q004`, stable across runs |
| `source` | which QA pack the row came from |
| `question`, `native_query`, `english_query` | the text typed; the replay uses the native text and no translation |
| `acceptable` | list of `"<family8>:<cardIndex>"` keys (0-based index), or the string `"refuse"` |
| `question_type`, `domain` | copied from the QA pack |
| `qa_status`, `qa_retrieved_key` | what the QA device did on that run, for agreement checks |

`acceptable` starts as the QA pack's `expected_card_id` converted to the SDK key. It is then
widened with **twins**: a card whose title is identical, character for character, in another
module family. The published set contains duplicated modules (two ANC examination modules,
two PNC care modules), so either copy answers the question and is scored `STRICT`. The
generator refuses to write the bank if any mapped card's title disagrees with the corpus, so
a 1-based / 0-based mistake cannot land silently.

The generator lives outside the repo (`make_qa_bank.py` in the eval workspace) because it
reads the QA packs; the bank itself is committed and self-sufficient.

---

## 3. Level 1: JVM replay (minutes, every commit)

`QaQuestionBankEvalTest` runs the whole bank through the production classes: the same
`ModuleKnowledgeIndex`, `ScopeClassifier`, `GroundingSelector` and `ServeDecision` the app
ships, over the committed corpus snapshot (`audit_corpus_2026-08.json`, 26 modules, 199
cards).

```bash
./gradlew :sdk-android:testDebugUnitTest --tests '*QaQuestionBankEvalTest*'
```

Per source it prints the table above in two columns, **BM25-only** and **BM25+dense**, then
every false refusal with the gate's own evidence line and every wrong-family serve with the
question.

**Dense in the JVM.** The encoder is an on-device LiteRT model, so the replay cannot embed
queries. Instead `qa_dense_cosines_device.json` holds, per question, the cosine against every
card, dumped once on a device by the instrumented test `DeviceDenseVectorDumpTest`. The
replay feeds those cosines into the same fusion code the app runs. When a bank has no cosine
file it runs BM25-only.

**What the JVM cannot reproduce.** On a Bangla device turn the guard query is the typed text
plus its ML Kit translation to English, and the SDK language sets the score floor. The replay
has no ML Kit, so the gate sees the Bangla text only. This moves one or two questions per
hundred relative to a device, which is why device runs remain the reference for headline
numbers.

**What is pinned.** Only the safety axes: wrong-family serves may not grow above the count
measured when the source was added, and no off-topic question may be answered. Exact-card
and false-refusal counts are printed, not asserted, so a change is read rather than enforced
question by question. Two older trainer sets (`ServeDecisionEvalTest`,
`HybridServeDecisionEvalTest`) pin their wrong counts the same way.

---

## 4. Level 2: emulator run (an hour per mode, before a release)

The same 100 questions typed into the real host app on an emulator, one mode at a time.

**Setup**

- Android 13 AVD, 4 GB RAM, host `spice-2.0-android` devDebug built against the SDK under
  test, signed in.
- For assisted mode: the "simple words" opt-in accepted, model and encoder downloaded, app
  restarted once after the download so the encoder is picked up.
- The audit IME `dev.audit.adbime` installed. The Compose text field accepts clipboard
  paste but not synthetic `commitText`, and only the active IME may write the clipboard, so
  the driver selects that IME, broadcasts the question into the clipboard, and pastes.

**Driver** (`drive.py`, per mode folder)

```bash
EXPECT_MODE=ON_DEVICE_ASSISTED python3 drive.py     # or ON_DEVICE_DIRECT
python3 score.py
```

For each question it:

1. Returns to the chat screen from wherever the last turn ended, verified against a UI dump
   rather than assumed.
2. Pastes the question, confirms the first 18 characters appear in the field, taps send.
3. Polls `logcat -d -s ChatTrace:I` and **anchors on the turn line that quotes this
   question** (`──── turn ──── … q="…"`). It reads only from that anchor forward and waits
   for `OUTCOME=`. This is what prevents the previous turn's outcome being attributed to
   the current question.
4. Records mode, served card (from `SERVE-DECISION serve`), fusion choice, dense top hit,
   cosine, outcome kind, and the answer text preview. Flags a mode mismatch against
   `EXPECT_MODE`.
5. Saves the per-question trace to `logs/<id>.log` and appends to `results.json`.

**Scorer** (`score.py`) applies the six verdicts and prints pass/fail against the bar.

The displayed answer text is captured but **not scored**.

---

## 5. Two harnesses on one run

On 23 September both teams ran the same 100 UC2 questions on the same SDK build in assisted
mode. Joining the two result files by question:

| | QA harness | SDK harness |
|---|---|---|
| Served card, per question | identical on 100/100 | identical on 100/100 |
| Refusals | 14 | 14 |
| Headline | CORRECT 35, PARTIAL 46, INCORRECT 19, weighted 58% | exact 78/95, module 83/95, wrong 3, false refusals 9 |

Same pipeline, same decisions, different headlines. The difference is entirely in what is
scored.

### 5.1 What each harness scores

| | QA harness | SDK harness |
|---|---|---|
| Unit | the answer text shown in the UI | the card the gate served |
| Ground truth | expected card body (CSV → module cache → `expected_answer`) | acceptable card keys, twins included |
| Method | token / key-point overlap with thresholds (`INTENT_CORRECT` 0.28, `INTENT_PARTIAL` 0.12), stub and length rules | set membership |
| Partial credit | PARTIAL = 0.5 | none; neighbour is its own line |
| Same-module neighbour | PARTIAL | `FAMILY`, not counted as exact |
| Refusal on answerable question | INCORRECT | `FALSE_REFUSE`, its own line |
| Sensitive to the model's wording | yes | no |
| Sensitive to the Bangla translation of the model's English output | yes | no |

### 5.2 Why the QA headline did not move

The QA harness rewards verbatim card text. In the 23 September run, its verdicts split by
answer path:

| Answer path | CORRECT | PARTIAL | INCORRECT |
|---|---|---|---|
| card served verbatim (rewrite failed groundedness) | 13 | 1 | 0 |
| model rewrite of the served card | 17 | 38 | 7 |
| refusal | 5 | 0 | 9 |

Of the 78 questions where the gate served the exact labelled card, the QA harness scored 28
CORRECT, 41 PARTIAL and 9 INCORRECT. A correct card reworded by the 0.6B model lands in
PARTIAL most of the time, because the rewrite is shorter and is then translated back to
Bangla by ML Kit before the comparison. So the gate change, which moved 9 refusals to exact
cards and 4 neighbour or wrong cards to exact cards, shows in the QA numbers as three
CORRECT and ten PARTIAL, while ordinary run-to-run variation in how many rewrites clear the
groundedness floor (21 verbatim serves on 17 September, 14 on 23 September) moved seven
questions the other way.

### 5.3 Capture differences that changed verdicts

- **Off-by-one on rows 86 to 95.** In the QA file the response text for q087, q093 and q095
  is, verbatim, the card body served to the *previous* question, and the logcat for rows 86
  to 95 stops before the `OUTCOME=` line. The evaluator then reported "wrong card, got
  a6032a14:4" for q087, which is q086's card, while the same row's log shows the right card
  served. The SDK driver avoids this by anchoring on the turn line that quotes the question.
- **`retrieved_card_id` is null on 74 rows.** The QA evaluator falls back through
  `groundedFrom`, `grounded[]` and BM25 top-1; when the log is truncated the fallback can
  pick a card the gate did not serve. The `SERVE-DECISION serve` line is always present on
  a served turn and names the decision directly.
- **Module cache in UC3.** The earlier UC3 pack captured 70 rows from a module cache and
  scored 13 exact-card serves as refusals; re-scoring from the logs gave 60/95 exact with 25
  false refusals.

---

## 6. Proposal for one shared harness

Both measurements are useful. Card correctness says whether retrieval and the gate work;
text correctness says whether the rewrite is faithful and readable. They should be two
columns of one report, computed from one capture, not two harnesses with two headlines.

1. **One capture format.** Per question: `id`, question, mode from the turn line, served
   card from `SERVE-DECISION serve` (null on refusal), refusal reason, outcome kind
   (`served_grounded` / `fallback_card_body` / `REFUSAL`), groundedness, displayed text,
   and the full trace from the anchored turn line to `OUTCOME=`. Anchor on the turn line;
   never on "the next OUTCOME".
2. **One question bank** with an `acceptable` set per question, twins included, kept in the
   SDK repo so the JVM replay and the device runs read the same labels. New packs append
   under a new `source`.
3. **Two scores, both reported.** Card score: the six verdicts, no weighting. Text score:
   the QA intent rubric, applied only to rows where a card was served, so a false refusal is
   not counted twice.
4. **One acceptance bar** written down and agreed, with card-level lines (exact, wrong
   family, false refusals, off-topic) and a text-level line (share of served rows CORRECT).
5. **Scripts in one place.** The SDK driver and scorer, the QA evaluator, and the bank
   generator under `scripts/eval/` in this repo, with a README that states the setup in
   section 4.

Open questions for the joint session: whether text scoring should compare against the
Bangla card or the English card the model actually saw; whether `PARTIAL` keeps a 0.5 weight
or becomes its own line; and how twins are labelled in the QA packs.

---

## 7. Where things are today

| Item | Location |
|---|---|
| Question bank and device cosines | `sdk-android/src/test/resources/retrieval/qa_questions_bn.json`, `qa_dense_cosines_device.json` |
| Corpus snapshot | `sdk-android/src/test/resources/retrieval/audit_corpus_2026-08.json` |
| JVM replay | `sdk-android/src/test/java/.../ai/retrieval/QaQuestionBankEvalTest.kt`, `EvalReport.kt` |
| Cosine dump (instrumented) | `sdk-android/src/androidTest/java/.../ai/embedding/DeviceDenseVectorDumpTest.kt` |
| Emulator driver and scorer | eval workspace, `_chat-audit/gate-v2-assisted/` and `gate-v2-direct/` (`drive.py`, `score.py`, `questions.json`, `results.json`, `logs/`) |
| Bank generator | eval workspace, `final_eval_audit/make_qa_bank.py` |
| Latest numbers | [serve-gate.md, "Measured on the question banks"](../references/serve-gate.md#measured-on-the-question-banks) |
| Per-question diagnosis of the open misses | [2026-09-23-gate-v2-refusals-and-wrong-cards.md](./2026-09-23-gate-v2-refusals-and-wrong-cards.md) |
