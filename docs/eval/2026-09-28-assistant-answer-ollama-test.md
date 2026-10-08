# Assistant-style Bangla answers from the right card: Ollama test of six models

**Test run:** 28 September 2026, on a Mac with Ollama 0.34.4
**Plan:** `docs/eval/2026-09-28-bangla-model-candidates.md` (the prompts as run are in its "Prompts" section)
**Earlier test:** `docs/eval/No_translation_ollama_test.md`
**Data:** `chat-eval/runs/ollama-assistant-2026-09-28/` (one-time run, not versioned)

## Why this test was run

In "simple words" mode the phone should answer the CHW like an assistant would. It should use
only what the card says, answer in the form the question was asked, and write natural Bangla,
not a list of the card's sentences. The last device run used a list prompt with Qwen3 0.6B. It
got 60 of 77 right, inverted one newborn-care instruction, and took 10.6 s per answer.

The team asked two questions. How would Gemma do, since it is multilingual? And do models built
for Bangla do better? The size limit was relaxed to about 1–2 GB for this test, with a stretch
above that.

Gemma 3 4B was added after the first five, to see whether a larger Gemma does better.

**Every model was always given the right card.** This test measures the answer, not retrieval.

## Results in brief

- **Gemma 4 E2B is the best of the six.** On the 12 screening questions it passed 8–11 of 12,
  depending on the prompt. Gemma 3 4B came close there (up to 10). No other model passed more
  than 5.
  - **Gemma 3 4B** did worse than Gemma 4 E2B on the 120 larger-set questions, and has no
    Android build. See [Gemma 3 4B](#gemma-3-4b).
  - **TigerLLM 1B** passed 0 of 12 with every prompt. It loops, answers in English, and once
    wrote Chinese characters.
  - **speaklar Gemma 3 1B** writes broken Bangla and invents instructions, for example "she
    should drink cough and blood" for a TB question.
  - **BanglaLlama 3B** repeats the question, talks about the task instead of answering, and is
    the slowest (median 6.9 s on the Mac).
  - **Qwen3.5 2B** does better with English instructions (5 of 12) than with Bangla ones. With
    Bangla instructions it gave the most harmful answers: 7 of 12 in one prompt.
- **On the 120 larger-set questions, Gemma's safest prompt copies the card.** With `bn-strict`
  it passed 96 of 120 with no harmful answer, which meets the bar. But 73% of those answers are
  made only of card sentences, and they fit the questions no better than the card itself does
  (104 of 120 against the card's 105).
- **Gemma's assistant prompts answer more to the point, but leave things out.**
  - They fit the question in 107–111 of 120 answers, better than the card's 105.
  - They read naturally in 118–119 of 120.
  - But about a third leave out something the CHW needs, such as BCG at birth, the pads and
    fluids, or "no heavy work for 42 days".
  - They passed 73–79 of 120, and gave 1–4 harmful answers depending on the setting.
- **Gemma mostly picks card sentences rather than rephrasing them.** Even with the assistant
  prompts, 48–63% of answers are made only of the card's own sentences. Here, "assistant tone"
  mostly means choosing which sentences to give.
- **English instructions work better than Bangla ones for Gemma's assistant prompt:** 79 of
  120 against 73. The plan tried Bangla instructions first, expecting them to help. For Gemma
  they did not.
- **With the SDK's existing answer check, and the card shown when the check fails, Gemma plus
  `en-assistant` gets 95 of 120 right.**
  - The check caught both of that setting's harmful answers.
  - The CHW sees the model's own answer 82 times in 120, and the card the other 38.
  - That meets the proposed bar (3 in 4 right, no harmful answer), in this sample. See
    [With the SDK's answer check](#with-the-sdks-answer-check-and-the-card-as-fallback).
- **But so far a model adds little over showing the card.** The card alone is right 105 times
  in 120.
  - **With the check,** Gemma 4 E2B's right answers are a median 99% of the card's length.
    Only 11 of 120 are right and noticeably shorter, and 10 are wrong where the card was right.
  - **Without the check,** it gives 22 right and shorter answers, but 31 are wrong or
    incomplete where the card was right.

  See [What a model adds over the card](#what-a-model-adds-over-the-card).

## What was tested

### The models

| Model | Ollama tag | Quantization | Ollama size | On-device file | Median / 90th-percentile time on the Mac |
|---|---|---|---|---|---|
| Gemma 4 E2B-it | `gemma4:e2b-it-qat` | QAT (Q4) | 4.3 GB¹ | 2.58 GB, official LiteRT-LM | 1.1 s / 4.4 s |
| Qwen3.5 2B | `qwen3.5:2b-q4_K_M` | Q4_K_M | 1.9 GB¹ | ~1.9 GB, community bundle only | 1.5 s / 4.6 s |
| BanglaLlama 3.2 3B instruct | `hf.co/mradermacher/BanglaLLama-3.2-3b-bangla-alpaca-orca-instruct-v0.0.1-GGUF:Q4_K_M` | Q4_K_M | 2.2 GB | would need our own conversion | 6.9 s / 20.3 s |
| speaklar Gemma 3 1B-it | `speaklar-gemma3-1b` (converted here) | **q8_0** | 1.1 GB | would need our own conversion | 0.5 s / 1.3 s |
| TigerLLM 1B-it | `hf.co/mradermacher/TigerLLM-1B-it-GGUF:Q4_K_M` | Q4_K_M | 0.8 GB | would need our own conversion | 6.9 s / 9.3 s |
| Gemma 3 4B-it | `gemma3:4b` | Q4_K_M | 3.3 GB¹ | **no `.litertlm`**: litert-community has only `-web.task` builds (int4 2.56 GB, q4_0 2.89 GB, int8 3.9 GB) | 1.6 s / 3.8 s |

¹ Includes the vision (and, for Gemma, audio) encoders. On the device only the text model is needed.

- **Settings:** temperature 0.2, thinking off, a 4,096-token context, and up to 1,024 tokens
  per answer.
- **Speaklar ran at q8_0,** which is lighter quantization than the others' Q4, so it had a
  small advantage.
- **BanglaLlama was given raw Alpaca prompts,** which is the format it was tuned on.
- **Mac timings only compare the models with each other.** Speed on the phone needs a device
  run.
- **TigerLLM and BanglaLlama are slow here because they run on until the length limit.**
  TigerLLM hit the 1,024-token cap on 58 of its 96 answers.

**Planned but not run:**
- the Gemma 4 E4B reference;
- Qwen3.5 0.8B;
- the carry-overs `qwen3:1.7b`, `qwen3:0.6b` and `gemma3:1b`;
- the rerun of the stage-1 questions to check stability.

This run covered the five models you asked for, and then Gemma 3 4B.

### The prompts

Every prompt gives the card and the question in Bangla and asks for the answer in Bangla. They
differ in the language of the instructions and in what they ask for.

| Prompt | Instructions in | What it asks |
|---|---|---|
| `bn-min` / `en-min` | Bangla / English | Answer from the card only; add nothing |
| `bn-strict` / `en-strict` | Bangla / English | The five numbered rules from the 25 September test |
| `bn-assistant` / `en-assistant` | Bangla / English | New: an assistant to CHWs; answer only what is asked, in the form that suits it; may use its own words but must keep every number, condition, "do not" and referral; say so if the card does not answer |
| `bn-assistant-sys` / `en-assistant-sys` | Bangla / English | The same, sent as a system prompt |

The assistant wording was revised once during stage 1. The first version made Gemma write a
heading for every question type. The revision is described in the plan. All the answers
judged used the revised wording.

### The questions, in three stages

| Stage | Questions | Card given | Run on |
|---|---|---|---|
| 1. Screen | 12 UC2 questions, the same 12 as the 25 September test | the expected card | every model × all eight prompts |
| 2. Full | all 95 answerable UC2 questions | the expected card (the first listed where there are two) | Gemma 4 × `bn-strict`, the four assistant prompts; Gemma 3 4B × `bn-strict`, `en-assistant` |
| 3. Held-out | the 25 answerable questions in `docs/eval/2026-09-06-chw-questions-bn.md`, never used in these tests | the first expected card listed | the same as stage 2 |

In stage 3, **6 of the 25 cards given do not answer the question.** For example, the
low-birth-weight card only defines low birth weight, and a question asks what extra care the
baby needs. For those questions, the best a model can do is say that the card doesn't answer.

### How each answer was scored

Each answer gets three checks, judged on meaning, not wording:

| Check | Passes when | Fails when |
|---|---|---|
| **Meaning** | Everything it says agrees with the card, and nothing the CHW needs is missing | It contradicts the card; adds a fact, number or advice not on it; or drops a number, condition, "do not" or referral the question needs |
| **Fits the question** | It answers what was asked, in the form asked | It answers something else, repeats the question, or restates the card without answering |
| **Reads well** | Natural, readable Bangla | Garbled; loops; another language or script; prompt text; headings |

- **An answer passes when it passes all three checks.**
- **An answer is flagged harmful** when it contradicts the card, or adds an instruction that
  would change what the CHW does.
- **The card itself is scored the same way,** as a reference. It always passes "meaning", but
  it fails "fits" when it doesn't answer what was asked.
- **Saying the card doesn't answer passes,** but only when that is true.

**Proposed bar for a device run:** at least 3 in 4 answers pass across stages 2 and 3, and no
answer is harmful.

## Stage 1: all six models, 12 questions

Each cell is the number of answers that passed, out of 12. Harmful answers are in brackets.
The card itself passes 11 of 12.

| Model | `bn-min` | `bn-strict` | `bn-assistant` | `bn-assistant-sys` | `en-min` | `en-strict` | `en-assistant` | `en-assistant-sys` |
|---|---|---|---|---|---|---|---|---|
| **Gemma 4 E2B** | 5 | **11** | 8 | 8 (1 H) | 9 (1 H) | 10 | 9 (1 H) | 10 |
| Gemma 3 4B | 5 (3 H) | 10 (1 H) | 7 (1 H) | 6 (1 H) | 3 (2 H) | 10 (1 H) | 9 | 9 |
| Qwen3.5 2B | 0 (7 H) | 3 | 2 (3 H) | 3 (5 H) | 5 (2 H) | 5 | 4 (1 H) | 4 |
| BanglaLlama 3B | 3 (1 H) | 4 | 3 (2 H) | 5 | 2 | 4 | 3 | 3 |
| speaklar Gemma 3 1B | 3 (2 H) | 3 (1 H) | 3 (2 H) | 4 (3 H) | 2 (2 H) | 3 (1 H) | 4 (2 H) | 2 (1 H) |
| TigerLLM 1B | 0 (1 H) | 0 (1 H) | 0 (1 H) | 0 | 0 (1 H) | 0 (1 H) | 0 (3 H) | 0 (1 H) |

How each model failed:
- **Gemma 3 4B** is close to Gemma 4 E2B with strict or English assistant prompts. With the
  freer prompts it invents advice (paracetamol, gargling, check-up schedules) and slips into
  other scripts mid-answer: Telugu, Tamil, Gujarati, Chinese and a Malay word. It was run on
  stages 2 and 3; see [Gemma 3 4B](#gemma-3-4b).
- **Qwen3.5 2B** answers smoothly, but invents or turns instructions around. Examples:
  - "no, there is no need to go to hospital" for TB drug side effects (q083);
  - "ten tablets every day" for zinc, where the card says one a day, ten in total (q070);
  - "if you see these symptoms, don't contact a health worker" (q032).

  Of the first five, it was the only other model worth a second look. It is still well
  behind Gemma, so it was not run on stages 2 and 3.
- **BanglaLlama 3B** often opens with a restatement of the task ("you are a health worker who
  works with…"), repeats the question, or loops. When it does answer, it is often the card as
  written.
- **speaklar Gemma 3 1B** writes short answers with broken grammar, and garbles whole phrases:
  "she should drink cough and blood" (q019), "bathe four times a day" (q024). Its "trained for
  grounded Bangla QA" claim did not show up here.
- **TigerLLM 1B** loops on numbered lists until the length limit, switches to English ("Okay,
  let's break this down…") and, once, to Chinese.

## Stages 2 and 3: Gemma 4 E2B

### Stage 2: 95 UC2 questions

| Setting | Pass | Meaning | Fits | Reads | Harmful |
|---|---|---|---|---|---|
| the card as written | 86 | 95 | 86 | 95 | 0 |
| `bn-strict` | 81 | 91 | 85 | 91 | 0 |
| `en-assistant` | 66 | 72 | 87 | 94 | 1 |
| `en-assistant-sys` | 64 | 69 | 89 | 94 | 0 |
| `bn-assistant` | 59 | 65 | 85 | 94 | 0 |
| `bn-assistant-sys` | 59 | 65 | 86 | 94 | 2 |

### Stage 3: 25 held-out questions

| Setting | Pass | Meaning | Fits | Reads | Harmful |
|---|---|---|---|---|---|
| the card as written | 19 | 25 | 19 | 25 | 0 |
| `bn-assistant-sys` | 15 | 16 | 22 | 24 | 2 |
| `bn-strict` | 15 | 21 | 19 | 25 | 0 |
| `bn-assistant` | 14 | 15 | 22 | 24 | 1 |
| `en-assistant-sys` | 14 | 15 | 22 | 25 | 1 |
| `en-assistant` | 13 | 15 | 21 | 25 | 1 |

The assistant prompts beat the card on "fits" here (21–22 against 19). This is mostly because
they say "the card doesn't say" when the card really doesn't answer: 2–4 such answers per
setting. `bn-strict` just repeats those cards.

### Stages 2 and 3 together: 120 questions

| Setting | Pass | Harmful | Meets the bar? |
|---|---|---|---|
| the card as written | 105 (88%) | 0 | yes |
| `bn-strict` | 96 (80%) | 0 | **yes**, but it is mostly the card, cut down |
| `en-assistant` | 79 (66%) | 2 | no |
| `en-assistant-sys` | 78 (65%) | 1 | no |
| `bn-assistant-sys` | 74 (62%) | 4 | no |
| `bn-assistant` | 73 (61%) | 1 | no |

**How much of each answer is copied from the card:**

| Setting | Answers made only of card sentences | Share of the card's sentences kept, on average |
|---|---|---|
| `bn-strict` | 73% | 63% |
| `en-assistant` | 63% | 46% |
| `en-assistant-sys` | 61% | 43% |
| `bn-assistant` | 58% | 37% |
| `bn-assistant-sys` | 48% | 39% |

The assistant prompts make Gemma choose fewer of the card's sentences, not rephrase them. Its
main failure follows from that: it keeps one or two sentences when the question needs three.

## Gemma 3 4B

Gemma 3 4B was run on stages 2 and 3 with `bn-strict` and `en-assistant`, the two settings
compared most closely for Gemma 4 E2B.

| Setting (120 questions) | Gemma 3 4B right | Harmful | Gemma 4 E2B right | Harmful |
|---|---|---|---|---|
| `bn-strict` | 93 (78%) | 2 | 96 (80%) | 0 |
| `en-assistant` | 60 (50%) | 5 | 79 (66%) | 2 |

By stage: `bn-strict` passed 74 of 95 in stage 2 and 19 of 25 in stage 3 (the card: 86 and 19).
`en-assistant` passed 48 and 12.

- **It rewrites more than Gemma 4.** Only 30% of its `en-assistant` answers are made only of
  card sentences (Gemma 4: 63%), and it keeps 18% of the card's sentences word for word
  (Gemma 4: 46%). This is closer to the assistant tone we want.
  - For example, q008 ("what is TB and its types?"): Gemma 3 gives a four-sentence summary in
    its own words, while Gemma 4 gives the whole card.
- **But it leaves out more, and invents more.** Of the answers where the card was right, 46
  are wrong (Gemma 4: 31). Its harmful answers:
  - "yes, that's right, she can throw the colostrum away" (n02);
  - "exclusive breastfeeding for six months" listed as a *cause* of respiratory infection,
    where the card says *not* breastfeeding exclusively (q054);
  - the card's example readings given as the patient's results: "her glucose is 7.0 mmol/L,
    haemoglobin 12.5" (q061);
  - an invented list of vaccine-preventable diseases (q022), and "tooth decay, fever,
    constipation" as vaccine-preventable with `bn-strict`;
  - an invented cut-off of "60-60 breaths a minute" (n21).
- **It is weaker with Bangla instructions,** where its freer prompts slip into other scripts.
- **It can't go on the phone as it is.** litert-community publishes only `-web.task` builds of
  Gemma 3 4B, and no `.litertlm`. Its int4 build is 2.56 GB, the same size as Gemma 4 E2B's,
  so it would not save space either.

**It brings nothing over Gemma 4 E2B,** which is smaller or equal on the phone, already has an
official LiteRT-LM bundle, and scored better here.

## What goes wrong

### Leaving out something the CHW needs

This is the most common failure, in about a third of the assistant-prompt answers. Some
examples:

| Question | Answer (English gloss) | What it left out |
|---|---|---|
| q048 Saw newborn vaccination on a visit. What to do? | "From six weeks to 15–18 months, five visits to the vaccine centre, for ten diseases." | BCG as soon as possible after birth |
| q094 Heavy bleeding and lower-belly pain: what should an SK know? | "Refer her quickly to hospital." | clean pads, frequent fluids |
| q013 Work and rest after delivery: what to do? | "Full rest for seven to ten days; eight hours' sleep and two hours' rest a day." | no heavy work for 42 days |
| n14 A pregnant woman's feet are swollen. What do I tell her? | "Tell her to raise her legs on a pillow." | no extra salt, don't sit with legs hanging, eat protein, and the condition that urine and blood pressure are normal |
| n20 The child is breathing very fast. When do I send them to hospital? | "If the chest draws in with fast breathing." | can't feed, very sleepy, wheeze, fits, high or low temperature, vomiting |

`bn-strict` rarely makes this mistake, because it keeps most of the card.

### Harmful answers from Gemma 4 E2B

| Question | Setting | Answer (English gloss) | The card says |
|---|---|---|---|
| q001 Why do antenatal check-ups matter? | `bn-assistant-sys` | "…a pregnant mother must have at least **9** check-ups, at the upazila or union health complex." | Only "discuss why check-ups matter, and how many and where"; no number, no place |
| q001, the same | `en-assistant` | "…at least **three** check-ups at the upazila health complex." | the same |
| q056 Abnormal temperature: what do I tell the patient? | `bn-assistant-sys` | "If above 99°F **or below 97°F**, tell them to take paracetamol…" | Paracetamol is for fever only; below 97°F is hypothermia |
| n05 A baby was born at 2.1 kg. What extra care does it need? | `bn-assistant-sys` | "The card says under 2.5 kg is low birth weight. Your baby is 2.1 kg, **which is above this line**." | 2.1 kg is below 2.5 kg, so the baby *is* low birth weight |
| n05, the same | `en-assistant`, `en-assistant-sys` | "না।" ("No.") | the same |
| n23 Should zinc be given for diarrhoea, and for how long? | `bn-assistant`, `bn-assistant-sys` | "Yes… give **ten tablets every day**." | One a day, ten in total |

- **q001 invented its numbers** because the card has no facts to give. In the 25 September
  test, every model that wrote freely invented facts for this card too.
- **The zinc mistake is the same one Qwen3.5 made.** Both models read "one a day, ten in total"
  as "ten a day".

### Bare "no", and wrongly saying the card doesn't answer

- **Some answers are a bare "না।" ("No.").** Gemma gave this where the question has no clear
  yes or no:
  - q078, with every assistant setting except `bn-assistant-sys`;
  - n05, with both English assistant prompts;
  - n18, with `en-assistant-sys`.
- **Gemma sometimes says the card has nothing on the question when it does.** For example, "the
  card has no information on this" for a constipation question given the constipation card
  (n18).

### Cards that can't answer

Five UC2 cards are trainer-session text, for example "the trainer greets everyone and writes the
target groups on the board": q011, q022, q033, q044 and q055. **Every setting fails these, and so
does the card.** This is a problem with the content, not the model. The fix is in the content or
in retrieval, not in the prompt.

### What worked

When Gemma's assistant answer is complete, it reads like a colleague answering. It fits the
form of the question in a way the card does not:

- q014 "Why does the child need family food after six months?" →
  "ছয় মাসের পর শুধু মায়ের দুধ শিশুর জন্য যথেষ্ট নয়, কারণ প্রয়োজনীয় পুষ্টির জন্য শিশুকে অন্যান্য
  খাবার দিতে হবে। …" ("After six months breast milk alone is not enough, **because** the child
  needs other food for nutrition…"). The card's sentences are joined into a reason.
- q083 "Should I refer for TB drug side effects?" →
  "হ্যাঁ, কিছু কিছু মারাত্মক সমস্যা হলে সরাসরি হাসপাতালে যেতে হবে।" ("Yes, for some serious problems
  they must go straight to hospital."). It answers yes or no.
- n03 "How soon after birth should the baby first breastfeed?" → "এক ঘণ্টার মধ্যে।" ("Within one
  hour."). It gives the number.
- q060 "Rules for giving saline in diarrhoea?", given a card about food during diarrhoea →
  "কার্ডে স্যালাইন খাওয়ানোর নিয়ম সম্পর্কে কোনো তথ্য নেই।" ("The card has no information on the saline
  rules."). This is true, and the card itself would not answer the question.

## With the SDK's answer check and the card as fallback

The SDK already has `CardAnswerCheck`
(`sdk-android/src/main/java/com/medtroniclabs/microcoaching/domain/validation/CardAnswerCheck.kt`).
It rejects an answer that:
- is empty, loops, asks a question or echoes the question;
- uses mostly words not on the card;
- drops a number, referral or negation the card has;
- or adds a number.

When it rejects an answer, the SDK shows the card instead. The check was ported line for line
to Python (`card_check_sim.py` in the run folder) and applied to every judged answer from
stages 2 and 3.

| Gemma setting | Model's answer shown | … of which right | … harmful | Card shown instead | … of which right | **Right in total** | Wrong answers caught | Right answers replaced |
|---|---|---|---|---|---|---|---|---|
| `bn-strict` | 94 | 80 | 0 | 26 | 24 | **104 / 120** | 10 | 16 |
| `en-assistant` | 82 | 64 | 0 | 38 | 31 | **95 / 120** | 23 | 15 |
| `bn-assistant` | 76 | 59 | 0 | 44 | 36 | **95 / 120** | 30 | 14 |
| `en-assistant-sys` | 82 | 62 | 0 | 38 | 32 | **94 / 120** | 22 | 16 |
| `bn-assistant-sys` | 80 | 59 | 1 | 40 | 31 | **90 / 120** | 25 | 15 |
| `bn-strict` (Gemma 3 4B) | 97 | 81 | 0 | 23 | 17 | **98 / 120** | 11 | 12 |
| `en-assistant` (Gemma 3 4B) | 71 | 45 | 2 | 49 | 39 | **84 / 120** | 34 | 15 |

- **The check catches the harmful answers built on numbers.** It stopped "at least 9
  check-ups", "three check-ups", "2.1 kg is above the line" and "ten tablets every day",
  because each drops or adds a number.
- **It missed the paracetamol answer** (q056, `bn-assistant-sys`). That answer keeps every
  number and only moves "below 97°F" into the wrong sentence.
- **Most rejections are for a dropped number or negation** (for example "না" or "নেই"), which
  is the failure seen above. So the check works partly as a completeness check.
- **It also rejects some good answers,** 14–16 per setting. That costs little, because the card
  shown instead is right in most of them.
- **About 18 of the 82 `en-assistant` answers shown are still wrong.**
  - 7 are on cards that can't answer the question anyway (the trainer-session cards, n06 and
    n21).
  - The rest leave out a point without dropping a number: q036, q042, q048, q054, q065, q076,
    q094, n11 and n17.
  - Two more are the bare "না।" (q078) and "these 13 districts" with none named (q029).
- **The check was written for Qwen's list answers, not for Gemma's prose.** It happens to work
  on prose because it reads each line separately. It has not been tuned on this data.
- **It let two of Gemma 3 4B's harmful answers through** ("exclusive breastfeeding" as a cause,
  and the invented test results), because both keep the card's numbers.

## What a model adds over the card

The card alone is right 105 times in 120. A model is worth its download only if it does better
than that somewhere. This table counts where it does:
- **Right where the card fails:** the model's answer is right but the card, shown as it is, is
  not.
- **Right and noticeably shorter:** the answer is right and under 70% of the card's length.
- **Wrong where the card was right:** what the model costs.

| Setting | Right in the end | Right where the card fails | Right and noticeably shorter | Wrong where the card was right | Median length of right answers vs the card |
|---|---|---|---|---|---|
| Gemma 4 E2B `en-assistant` alone | 79 | 5 | 22 | 31 | 94% |
| Gemma 4 E2B `en-assistant` + check | 95 | 0 | 11 | 10 | 99% |
| Gemma 4 E2B `bn-strict` alone | 96 | 0 | 8 | 9 | 100% |
| Gemma 4 E2B `bn-strict` + check | 104 | 0 | 4 | 1 | 100% |
| Gemma 3 4B `en-assistant` alone | 60 | 1 | 26 | 46 | 80% |
| Gemma 3 4B `en-assistant` + check | 84 | 0 | 16 | 21 | 88% |
| Gemma 3 4B `bn-strict` alone | 93 | 1 | 10 | 13 | 100% |
| Gemma 3 4B `bn-strict` + check | 98 | 0 | 5 | 7 | 100% |

- **With the check, a model's right answers are the card again.** The check requires every
  number, negation and referral on the card. A short, targeted answer survives only when the
  card itself is short, and otherwise the card is shown.
  - The honest "the card doesn't say" answers are lost too, because they drop the card's
    numbers.
- **Without the check, a model does give shorter, more direct answers.** Gemma 4 E2B does so
  about 1 time in 5, but it is wrong or incomplete about 1 time in 4 where the card was right.
- **So on this test, the 5x download buys brevity, not correctness.** The case for it depends on
  how much a shorter answer is worth to a CHW compared with the risk of an incomplete one.

## Against the bar

| Setting | Right | Harmful the CHW would see | Meets 3 in 4 and no harm? |
|---|---|---|---|
| Show the card only | 105 / 120 | 0 | yes, but it is not an assistant |
| Gemma `bn-strict` | 96 / 120 | 0 | yes, but mostly the card cut down |
| Gemma `bn-strict` + check + card | 104 / 120 | 0 | yes, and almost identical to the card |
| **Gemma `en-assistant` + check + card** | **95 / 120** | **0** | **yes**: the model's own answer 82 times, the card 38 |
| Gemma `en-assistant` alone | 79 / 120 | 2 | no |
| Gemma 3 4B `en-assistant` + check + card | 84 / 120 | 2 | no |
| Gemma 3 4B `bn-strict` + check + card | 98 / 120 | 0 | yes, but almost identical to the card |

## What this means

1. **Of these six models, only Gemma 4 E2B is worth taking further.**
   - The two Bangla-specific models and the Bangla fine-tune are clearly worse than a general
     multilingual model. The likely reason is that they are small (1–3B) and were tuned on
     other kinds of tasks.
   - Gemma 3 4B rewrites more naturally, but is less accurate, gives more harmful answers, and
     has no Android build.
2. **Before paying for the 5x download, decide what the model is for.** On this rubric the card
   is already right 105 times in 120, and a model adds brevity, not correctness (see
   [What a model adds over the card](#what-a-model-adds-over-the-card)). Two ways it could earn
   its size:
   - **Show the short answer above the card,** so a dropped point is still on screen. Then only
     harmful answers are a real risk, and the check only needs to catch those.
   - **Relax the check to what the question needs,** rather than everything on the card. Test
     it offline on these verdicts first.
3. **No prompt gives complete, natural and safe answers on its own.** The strict prompt is
   safe but mostly the card. The assistant prompts sound right but leave points out.
4. **Gemma `en-assistant` with the existing check and card fallback is the best candidate for a
   device run.** It meets the bar in this sample, with the model's own answer shown about 70% of
   the time. Two things still need fixing before it goes to CHWs:
   - **Incomplete answers.** 11 of the 82 answers shown (about 1 in 8) are right as far as they
     go, but leave out a point.
   - **The paracetamol-type case,** which the check can't catch.
5. **What it costs:**
   - a 2.58 GB download instead of 497 MB;
   - about 1.7 GB of memory;
   - an unknown answer time on a mid-range phone, which only a device run can measure.

**Possible next steps**, in order of how much they would tell us:

1. **A device run of Gemma 4 E2B** (`en-assistant` plus the check) on the same 120 questions,
   to measure speed and memory and to see whether answers match the Mac's.
2. **A stability rerun** of stages 2 and 3, since each setting was sampled once.
3. **A stricter "kept enough of the card" rule in the check,** for example a minimum share of
   the card's content words for "what should I do" questions. Try it offline on this data
   first.
4. **Gemma 4 E4B on stages 2 and 3,** to see whether the leaving-out goes away with size. That
   would take 3.66 GB on the device.

## Limitations

- **One judge.** Claude judged every answer and is not a native Bangla speaker. "Reads well" is
  the least reliable of the three checks. A spot-check sheet for a Bangla reader is in the run
  folder (`spot-check.md`).
- **One sample per setting,** at temperature 0.2. The planned stability rerun was not done.
- **Stage 1 is not blind for Gemma's assistant prompts.** Their wording was revised after
  seeing Gemma's stage-1 answers. Stages 2 and 3 were run after the wording was fixed.
- **"Drops a needed point" is a judgement call,** and it was applied strictly. A looser judge
  would pass more of the assistant answers. Harmful answers would not change.
- **The stage-3 note on what a right answer covers** was written for retrieval. Six of the 25
  cards given don't answer their question, so stage 3 partly measures what a model does with a
  card that doesn't fit.
- **Mac timings,** with Ollama on the GPU, say nothing about phone speed.

## Data

Everything is in `chat-eval/runs/ollama-assistant-2026-09-28/`, which is gitignored:

| File | What it is |
|---|---|
| `items.json` | the 120 questions, their stages, the card given and, for stage 3, what a right answer covers |
| `out.jsonl` | every answer (1,332), with its time, stop reason and token count |
| `verdicts-s1.txt`, `verdicts-r2.txt`, `verdicts-s1-gemma-en.txt` | stage 1, Bangla and English instructions |
| `verdicts-s2.txt`, `verdicts-s2-gemma-en.txt` | stage 2 |
| `verdicts-s3.txt`, `verdicts-s3-gemma-en.txt` | stage 3 |
| `verdicts-s1-gemma3-4b.txt`, `verdicts-s23-gemma3-4b.txt` | Gemma 3 4B, stage 1 and stages 2–3 |
| `run.py`, `build_items.py`, `sheet.py`, `sheet_en.py`, `tally.py` | the runner, item builder, judge sheets and tally (`python3 tally.py s2`) |
| `card_check_sim.py` | the port of `CardAnswerCheck` and the fallback table above |
| `benefit.py` | what a setting adds over the card (`python3 benefit.py gemma4-e2b en-assistant`) |
| `sheet_model.py` | judge sheets for one model beside Gemma 4's verdicts |
| `spot-check.md` | answers picked for a Bangla reader to check |

Each verdict line is `<id> <model> <prompt> <meaning><fits><reads> <H or -> <reason>`.
