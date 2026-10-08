# Answering from the Bangla card without translation: Ollama test of models and prompts

**Test run:** 25 September 2026, on a Mac with Ollama
**Write-up:** 28 September 2026
**Follow-up on the device:** `docs/eval/2026-09-27-card-sentence-device-run.md`
**Next test:** `docs/eval/2026-09-28-bangla-model-candidates.md`

## Why this test was run

In "simple words" mode today, the phone translates the CHW's Bangla question and the card
into English with ML Kit. Qwen3 0.6B answers in English, and ML Kit translates the answer
back to Bangla. In the judged device run of 24 September, the right card reached the model
77 times, but only 26 of those 77 answers were correct. Translation damaged the question,
the card and the answer.

This test asked whether a small model can answer well if it gets the Bangla card and the
Bangla question directly, with no translation anywhere. It also asked which model, and
whether the prompt's instructions should be written in Bangla or in English.

## Results in brief

- **Removing translation helps a lot.** The best setting answered 47 of the 77 right-card
  questions correctly, against 26 for the device today. It added nothing that isn't on the
  card.
- **The best setting was the model already on the phone, `qwen3:0.6b`,** with the
  instructions in English and a prompt asking it to *list the card's sentences that answer
  the question*.
- **Instructions written in Bangla did not work for Qwen.** Qwen models looped or repeated
  the question back. The worst answer ran to 41,000 characters.
- **Gemma 3 1B was tried with Bangla instructions,** in three prompts. It writes the most
  natural Bangla, but in most prompts it adds advice, reasons and numbers that are not on
  the card. It sometimes switches into Russian, Tamil, Arabic or Korean. Its list prompt
  with Bangla instructions was its best, and scored as well as Qwen's best, but it still
  added claims.
- **The bigger `qwen3:1.7b` was not better on all 77 rows.** It paraphrases more, and so
  drops more points. It is also about three times the size and three times slower.
- **Picking sentences without a model was worse.** It never invents anything, but it often
  leaves out the key action, such as "refer to the health centre".
- **All of this scoring rewarded copying the card.** Answers were judged only on whether
  they kept the card's facts. So the winning prompt was the one that copies the card most
  closely. None of the scores measure whether an answer is more helpful than showing the
  card itself. See [The flaw in how this was scored](#the-flaw-in-how-this-was-scored).
- **On the device, the winning setting did worse than in this test.** It got 60 of 77 right,
  and one answer shown to a CHW inverted a newborn-care instruction.

## What was tested

### The questions

The test used **77 questions**: every question in the 24 September device run where the
right card was served. For each one, the model got that card's Bangla text and the question
as the CHW typed it.

| The device's result on these questions | Count |
|---|---|
| The device's rewrite was PARTIAL or INCORRECT | 51 |
| The device rejected its own rewrite and showed the card | 18 |
| The device's rewrite was CORRECT | 8 |

Showing the card itself is correct on all 77 by definition, because it is the right card.
That is the reference every setting below is compared with.

### The models

| Model | Ollama download | Notes |
|---|---|---|
| `qwen3:0.6b` | 523 MB | The model the phone runs today (a different 4-bit build) |
| `gemma3:1b` | 815 MB | Google's small multilingual model |
| `qwen3:1.7b` | 1.4 GB | A larger version of today's model |
| Extractive, no model writing | embeddinggemma | The 1 or 2 card sentences closest to the question, shown as written |

Settings: temperature 0.2, which is the phone's setting; a context of 4,096 tokens; and
Qwen's "thinking" turned off.

### The prompts

The card and the question were always in Bangla, and the answer was always asked for in
Bangla. The prefix says what language the **instructions** were written in: `en-` for
English, `bn-` for Bangla.

| Prompt | What it asks for | Instructions in |
|---|---|---|
| `en-min` / `bn-min` | The card, the question, then "answer the health worker directly in Bangla, using only the information above; do not add anything it does not say". `en-min` is today's device prompt with "in Bangla" added. | English / Bangla |
| `en-strict` / `bn-strict` | Five numbered rules: use only facts on the card; include every point that answers the question and don't drop numbers, durations or conditions; don't invent quotes or talk about yourself; for "what should be done" give the card's instructions; answer in simple Bangla | English / Bangla |
| `en-strict-sys` | The same five rules, sent as a system prompt | English |
| `en-list` / `bn-list` | "List, in Bangla, the sentences of the card that answer the question, keeping their meaning exactly. You may shorten a sentence, but you must not change or add any fact." | English / Bangla |

The exact wording of every prompt is in the [appendix](#appendix-the-exact-prompts).

### How it was run

1. **Stage 1:** every model with every prompt on 12 of the 77 questions, chosen before the
   test. 10 were questions the device got wrong, 1 it got right, and 1 where it showed the
   card.
2. **Stage 2:** the best settings from stage 1 on all 77 questions.

## How the answers were scored

Claude (`claude-opus-5-5`) judged every answer against the card. No person has checked
these verdicts yet.

| Verdict | Meaning |
|---|---|
| CORRECT | Gives every point on the card that answers the question, and nothing wrong |
| PARTIAL | On the right track, but drops a point, number or condition, or is garbled in places |
| INCORRECT | No answer (repeats the question, loops, or empty), a wrong answer, or one that contradicts the card |

Two things were counted separately:

- **Added claims:** answers that state something not on the card, such as advice, a reason
  or a number. These are counted per answer, whatever the verdict.
- **Other script:** answers containing text in another language, such as Russian or Tamil.

**Score** = CORRECT + ½ × PARTIAL. That is out of 12 in stage 1 and out of 77 in stage 2.

## Stage 1: every model and prompt, 12 questions

| Model | Prompt | Instructions in | CORRECT | PARTIAL | INCORRECT | Score (of 12) | Answers with added claims | Answers with other-script text |
|---|---|---|---|---|---|---|---|---|
| qwen3:0.6b | en-list | English | 6 | 4 | 2 | **8.0** | 0 | 0 |
| qwen3:0.6b | en-strict | English | 5 | 4 | 3 | 7.0 | 0 | 0 |
| qwen3:0.6b | en-strict-sys | English | 5 | 4 | 3 | 7.0 | 0 | 0 |
| qwen3:0.6b | bn-strict | Bangla | 4 | 4 | 4 | 6.0 | 0 | 0 |
| qwen3:0.6b | bn-list | Bangla | 4 | 4 | 4 | 6.0 | 0 | 0 |
| qwen3:0.6b | en-min | English | 3 | 5 | 4 | 5.5 | 0 | 0 |
| qwen3:0.6b | bn-min | Bangla | 1 | 3 | 8 | 2.5 | 3 | 0 |
| gemma3:1b | **bn-list** | **Bangla** | 6 | 6 | 0 | **9.0** | 2 | 0 |
| gemma3:1b | en-list | English | 7 | 3 | 2 | 8.5 | 3 | 5 |
| gemma3:1b | en-strict | English | 4 | 5 | 3 | 6.5 | 5 | 0 |
| gemma3:1b | **bn-strict** | **Bangla** | 4 | 3 | 5 | 5.5 | 7 | 1 |
| gemma3:1b | en-strict-sys | English | 3 | 4 | 5 | 5.0 | 6 | 0 |
| gemma3:1b | en-min | English | 2 | 4 | 6 | 4.0 | 5 | 1 |
| gemma3:1b | **bn-min** | **Bangla** | 0 | 5 | 7 | 2.5 | **12** | 2 |
| qwen3:1.7b | en-strict | English | 9 | 2 | 1 | **10.0** | 1 | 0 |
| qwen3:1.7b | en-list | English | 7 | 4 | 1 | 9.0 | 1 | 0 |
| qwen3:1.7b | bn-list | Bangla | 7 | 4 | 1 | 9.0 | 1 | 0 |
| qwen3:1.7b | en-strict-sys | English | 6 | 5 | 1 | 8.5 | 1 | 0 |
| qwen3:1.7b | bn-strict | Bangla | 3 | 7 | 2 | 6.5 | 2 | 0 |
| qwen3:1.7b | en-min | English | 4 | 5 | 3 | 6.5 | 4 | 0 |
| qwen3:1.7b | bn-min | Bangla | 0 | 3 | 9 | 1.5 | 5 | 0 |
| extractive | top 1 sentence | – | 4 | 6 | 2 | 7.0 | 0 | 0 |
| extractive | top 2 sentences | – | 5 | 5 | 2 | 7.5 | 0 | 0 |

The **bold** Gemma rows are its three prompts with Bangla instructions.

### What the stage 1 answers looked like

- **Gemma with Bangla instructions invents.**
  - Under `bn-min`, all 12 answers added something not on the card. For the question on
    antenatal check-ups (q001) it made up a schedule: "once a week for the first 12 weeks".
    Under `bn-strict` it said "at least 20–25 times".
  - Under `bn-strict` (q002), it copied the card on iron and calcium tablets correctly, then
    ended "করণীয় কী: আয়রন ও ক্যালসিয়াম বড়ি একসাথে খান" ("take iron and calcium
    together"). That is the opposite of the card.
  - On q077 (fits after delivery) it added "এখানে কোনো প্রকার চিকিৎসার প্রয়োজন নেই" ("no
    treatment is needed here").
- **Gemma with English instructions writes in other languages.** Under `en-list`, several
  answers start with a Russian preamble ("Вот предложения из карты…"). One (q014) turns "six
  months" into Arabic "سنة واحدة" ("one year").
- **Gemma's list prompt in Bangla (`bn-list`) was its cleanest.** It had no INCORRECT answers
  and two added claims.
- **Qwen with Bangla instructions repeats and loops.** Under `bn-min` it gave 8 INCORRECT of
  12. Two answers ran to about 40,000 characters, with a phrase repeated until the stream
  stopped.
- **Qwen with English instructions stays on the card**, but sometimes hands the question
  back as the answer (q019, q031).
- **Extractive answering drops the action.** On q077 both settings omitted "refer to the
  health centre quickly", the card's only instruction.

## Stage 2: the best settings on all 77 questions

| Setting | CORRECT | PARTIAL | INCORRECT | Score (of 77) | Answers with added claims |
|---|---|---|---|---|---|
| Showing the card itself | 77 | 0 | 0 | 77 | 0 |
| **qwen3:0.6b, en-list** | **47** | 18 | 12 | 56 | **0** |
| gemma3:1b, bn-list | 45 | 24 | 8 | 57 | 6 |
| qwen3:1.7b, en-strict | 37 | 31 | 9 | 52.5 | 6 |
| qwen3:1.7b, en-list | 36 | 31 | 10 | 51.5 | 1 |
| qwen3:0.6b, en-strict | 32 | 27 | 18 | 45.5 | 5 |
| Device today (ML Kit both ways) | 26 | 28 | 23 | 40 | not counted |

- **Why `qwen3:0.6b en-list` was chosen:** Gemma `bn-list` has a slightly higher score (57
  against 56), but it added claims in 6 answers against 0. In a health setting an invented
  instruction is worse than a missing one.
- **Why `qwen3:1.7b en-strict` fell back:** it won stage 1 (10 of 12), but on 77 questions it
  was well behind the 0.6B. Twelve questions were too few to rank settings reliably.
- **Ollama gives different answers each run.** Stage 2 re-ran the 12 stage-1 questions too,
  and only 17 of 36 answers came out word for word the same. So every stage-2 number comes
  from the stage-2 answers.

## Adding a check that falls back to the card

Most of `qwen3:0.6b en-list`'s INCORRECT answers were easy to recognise by rule: the question
handed back, one line repeated, or lines of words that aren't on the card. A check can
catch these and show the card instead. When it does, the CHW sees the card, which counts as
correct.

| Check | Answers it replaced with the card | CORRECT shown (of 77) | PARTIAL shown | INCORRECT shown |
|---|---|---|---|---|
| None | – | 47 | 18 | 12 |
| Question handed back, repeated lines, lines off the card | 11 (10 INCORRECT, 1 PARTIAL) | 58 | 17 | 2 |
| + the card mentions referral (রেফার/হাসপাতাল) and the answer doesn't | 12 | 59 | 16 | 2 |
| + a number on the card is missing from the answer | 26 (11 INCORRECT, 8 PARTIAL, 7 CORRECT) | **66** | 10 | 1 |

The strongest check threw away 7 good answers, but nothing was lost: those questions showed
the card, which is correct. The check was built on these same 77 questions, so 66 is an
optimistic number.

## What happened on the device

The best setting, `qwen3:0.6b en-list` plus the strongest check, was built into the SDK. It
was run on the 100 questions on the emulator on 27 September. The full write-up is in
`docs/eval/2026-09-27-card-sentence-device-run.md`.

| | This test (Ollama) | Device |
|---|---|---|
| CORRECT among the 77 right-card questions | 66 | **60** |
| Answers replaced by the card | 26 | 43 |
| Answers shown that contradict the card | 0 | **1**: "এক মাস পর্যন্ত চুল কাটা যাবে" ("the hair may be cut in the first month"), where the card says it must not be |
| Model time per answer | – | 10.6 s, against 3.3 s for the device today |

- **CORRECT over all 100 questions:** 65, up from 31 for the device today.
- **The run failed its own bar,** which was set before it. The bar required no shown answer
  that changes what the CHW would do, and the inverted haircut instruction broke it.

## The flaw in how this was scored

Every verdict above asks one thing: did the answer keep the card's facts without adding
any? Nothing asked whether the answer was **more helpful than showing the card**: shorter,
focused on the question, or easier to act on.

Under that scoring, the closer an answer stays to the card, the better it does. So the
"list the card's sentences" prompt was bound to win, and it did. The device run shows what
that meant. Of the 34 rewrites the device showed on right-card questions:

| Rewrite length compared with the card | CORRECT | PARTIAL | INCORRECT |
|---|---|---|---|
| 90% or more (nearly the whole card) | 10 | 1 | 0 |
| 60–90% | 7 | 9 | 2 |
| under 60% (actually shortened) | 0 | 4 | 1 |

A median 96% of each rewrite's words came straight from the card. No rewrite that really
shortened the card was correct.

**So the best setting mostly gave the CHW the card back.** It came with occasional
misspellings, one inverted instruction, and a longer wait. The prompts that let the model
answer in its own words (`en-min`, `bn-min`) did worst with every model, and invented the
most.

The plan for the next test (`docs/eval/2026-09-28-bangla-model-candidates.md`) proposes a
second verdict for every answer: whether it is better, the same, or worse than showing the
card.

## Limitations

- **One judge.** Claude judged every answer, and no person has checked them.
- **The two judging passes differ.** The stage-1 verdicts for `qwen3:0.6b` and `gemma3:1b`
  were judged a second time on 28 September, from the saved answers, because the first
  pass's per-answer verdicts had not been saved.
  - The second pass was stricter about added claims, which lowered most Gemma scores by 0.5
    to 2 points. For example, Gemma `en-strict-sys` went from 7 to 5.
  - The ranking of prompts did not change.
  - Stage 1 for `qwen3:1.7b`, the extractive rows, and all of stage 2 are the original
    verdicts from 25 September.
- **Twelve questions were too few** to rank settings (see `qwen3:1.7b en-strict`).
- **Ollama isn't the phone.** It runs a different 4-bit build of the models, and its output
  changes between runs. The device's result (60) was lower than this test's (66).
- **The fallback check was tuned on these questions.**
- **Some cards hold no facts.** For example, q001 only says "discuss the importance of
  check-ups and tell her how many visits". Every model that wrote freely invented the
  missing facts.

## Where the data is

`chat-eval/runs/ollama-spike-2026-09-25/`, which is gitignored and not committed:

| File | Contents |
|---|---|
| `rows.json` | The 77 questions, each with its card and the device's verdict |
| `dev_ids.json` | The 12 stage-1 questions |
| `out-dev.jsonl`, `out-full.jsonl` | Every model answer from stage 1 and stage 2 |
| `verdicts-stage1.txt` | Stage 1 verdicts for qwen3:0.6b and gemma3:1b (the second pass) |
| `verdicts-dev17b.txt` | Stage 1 verdicts for qwen3:1.7b |
| `verdicts-extractive.txt` | Stage 1 verdicts for extractive answering |
| `verdicts.txt` | Stage 2 verdicts: qwen3:0.6b en-list, qwen3:0.6b en-strict, gemma3:1b bn-list |
| `verdicts-17b.txt` | Stage 2 verdicts: qwen3:1.7b en-strict, en-list |
| `run.py` | The test script, with the prompts |
| `echo_check.py`, `card_check_ref.py` | The fallback checks |

An earlier and smaller test on 8 questions compared ML Kit English, hand-translated
English and all-Bangla input. It is described in
`docs/eval/2026-09-25-rewrite-quality-handoff.md`.

## Appendix: the exact prompts

`{card}` is the card's Bangla text and `{q}` is the question as the CHW typed it. Only
`en-strict-sys` used a system prompt.

**English rules** (used by `en-strict` and `en-strict-sys`):
```
Rules:
1. Use only facts written in the card. Do not add advice, causes, numbers or examples that are not in the card.
2. Include every point from the card that answers the question. Do not drop numbers, durations or conditions.
3. Do not invent quotes, names or dialogue. Do not talk about yourself.
4. If the question asks what should be done or what advice to give, give the card's instructions.
5. Answer in simple Bangla, in short sentences or a short list.
```

**Bangla rules** (used by `bn-strict`):
```
নিয়ম:
১. শুধু কার্ডে লেখা তথ্য ব্যবহার করুন। কার্ডে নেই এমন পরামর্শ, কারণ, সংখ্যা বা উদাহরণ যোগ করবেন না।
২. প্রশ্নের উত্তর দেয় এমন কার্ডের প্রতিটি কথা রাখুন। সংখ্যা, সময়কাল বা শর্ত বাদ দেবেন না।
৩. কোনো উদ্ধৃতি, নাম বা কথোপকথন বানাবেন না। নিজের সম্পর্কে কিছু বলবেন না।
৪. প্রশ্নে "করণীয় কী" বা "কী পরামর্শ দিব" থাকলে কার্ডের নির্দেশনাগুলো দিন।
৫. সহজ বাংলায়, ছোট বাক্যে বা ছোট তালিকায় উত্তর দিন।
```

**`en-min`**
```
{card}

Question: {q}

Answer the health worker directly in Bangla, using only the information above.
Do not add anything it does not say.
```

**`bn-min`**
```
{card}

প্রশ্ন: {q}

উপরের তথ্য ব্যবহার করে স্বাস্থ্যকর্মীকে সরাসরি বাংলায় উত্তর দিন।
তথ্যে নেই এমন কিছু যোগ করবেন না।
```

**`en-strict`**
```
You answer a community health worker's question using ONLY the guidance card below.
{English rules}

Card:
<<<
{card}
>>>

Question: {q}

Answer (in Bangla):
```

**`bn-strict`**
```
আপনি নিচের নির্দেশিকা কার্ড ব্যবহার করে একজন কমিউনিটি স্বাস্থ্যকর্মীর প্রশ্নের উত্তর দেবেন।
{Bangla rules}

কার্ড:
<<<
{card}
>>>

প্রশ্ন: {q}

উত্তর:
```

**`en-strict-sys`**
```
System: You answer a community health worker's question using ONLY the guidance card the user gives you.
{English rules}

User: Card:
<<<
{card}
>>>

Question: {q}
```

**`en-list`**
```
Below is a guidance card and a health worker's question. List, in Bangla, the sentences of the card that answer the question, keeping their meaning exactly. You may shorten a sentence, but you must not change or add any fact. Output only the list.

Card:
<<<
{card}
>>>

Question: {q}
```

**`bn-list`**
```
নিচে একটি নির্দেশিকা কার্ড এবং একজন স্বাস্থ্যকর্মীর প্রশ্ন আছে। কার্ডের যে বাক্যগুলো প্রশ্নের উত্তর দেয়, সেগুলো বাংলায় তালিকা আকারে লিখুন। অর্থ হুবহু রাখুন। বাক্য ছোট করতে পারেন, কিন্তু কোনো তথ্য বদলাবেন না বা যোগ করবেন না। শুধু তালিকাটি লিখুন।

কার্ড:
<<<
{card}
>>>

প্রশ্ন: {q}
```
