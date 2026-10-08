# Bangla card-sentence answers: device run of the 100 UC2 questions

**Date:** 2026-09-27
**Branch:** `feat/test-harness-and-model-rewrite` (uncommitted SDK change)
**Spec:** `docs/superpowers/specs/2026-09-27-bangla-card-sentence-answer-design.md` (§5 is the pass bar)

## Result: fail

The run fails the bar that was set before it. One answer shown to the CHW inverts an
instruction on the card.

| Pre-registered bar (spec §5) | Needed | Got | |
|---|---|---|---|
| CORRECT among the rows where the exact card was served | ≥ 60 of 77 | **60 of 77** | met, with no margin |
| Shown rewrites with a claim not on the card that changes what the CHW would do | 0 | **1** (uc2_q037) | **not met** |

In uc2_q037, the card says a newborn's hair must not be cut for a month. The CHW was shown
"এক মাস পর্যন্ত চুল কাটা যাবে": the hair may be cut in the first month. The card check did not
catch it (see [Why the check missed uc2_q037](#why-the-check-missed-uc2_q037)).

The spec says what happens after a fail: compare against showing the card verbatim, and put the
choice to the product owner. That comparison and a recommendation are at the end.

## What was run

- **Change under test:** on a Bangla assisted ("simple words") turn, Qwen3 0.6B gets the Bangla
  card and the typed Bangla question. It is asked, in English, to list the card's sentences
  that answer the question. The answer is shown with no ML Kit translation. A card check runs
  on it, and any failure shows the card verbatim instead.
- **Card check:** as in spec §4.2–§4.5. That includes the amendments made after code review and
  before this run: NFC normalisation, numbers read from the list lines only, `added_number` and
  `missing_negation`.
- **Device:** Android_13 emulator (`sdk_gphone64_arm64`, arm64-v8a, 3,909 MB), airplane mode
  on, `qwen3_0_6b_mixed_int4.litertlm`, temperature 0.2. This is the same device as the
  baseline.
- **Questions:** the 100 UC2 questions (`qa-uc2-2026-09-17`).
- **Baseline:** `chat-eval/runs/full-assisted` (24 September), the current device path: ML Kit
  both ways and an English prompt.
- **Retrieval did not move.** All 100 questions got the same gate result and the same card as
  in the baseline.
  - The corpus file hash differs, because the baseline was checked against
    `audit_corpus_2026-08.json` and this run against the device's own export.
  - Both files hold the same 199 cards.
  - The 107 cards this bank touches have identical titles and bodies in both.
- **Judge:** Claude (`claude-opus-5-5`), following `chat-eval/JUDGING.md`. It judged the 39
  rows the harness could not decide. The harness decided the other 61: the expected card shown
  verbatim, or a refusal.

## Results against the baseline

### All 100 questions

| Verdict | Baseline | This run |
|---|---|---|
| CORRECT | 31 | **65** |
| PARTIAL | 28 | 15 |
| INCORRECT | 41 | 20 |

The non-exact rows did not change: 18 refusals (5 correct refusals of off-topic questions, 13
refusals of questions the corpus answers) and 5 other cards served. Every gain is on the 77 rows where the
exact card was served.

### The 77 rows where the exact card was served

| What the CHW saw | Baseline | This run |
|---|---|---|
| A rewrite | 59 (8 C, 28 P, 23 I) | **34** (17 C, 14 P, 3 I) |
| The card verbatim (fallback) | 18 (18 C) | **43** (43 C) |
| **CORRECT in total** | **26** | **60** |

- **Rewrite quality improved.** Half of the shown rewrites are now CORRECT (17 of 34), against
  8 of 59 before.
- **Fallbacks rose to 56%.** More than half of the exact-card answers were the verbatim card,
  so the CHW got no shortening on those.
- **Rewrites that were not CORRECT mostly omit a point.** 14 of the 17 are PARTIAL and 3 are
  INCORRECT.

### Why the card fell back (the 43 fallbacks on exact-card rows)

| Reason | Rows |
|---|---|
| `card_check:loop`: the model repeated a line | 13 |
| `card_check:question`: the model handed the question back | 13 |
| `card_check:missing_number`: a number on the card was dropped | 9 |
| `card_check:missing_negation`: a `না`/`নেই` on the card was dropped | 3 |
| `card_check:off_card`: a line mostly of words not on the card | 3 |
| `card_check:echo` | 1 |
| `card_check:empty` | 1 |
| Groundedness, or the L4 output validator | 0 |

The card check caused every fallback. Groundedness and the output validator never fired on a
Bangla answer that had passed it.

### Claims not on the card, in shown rewrites

| | Baseline | This run |
|---|---|---|
| Shown rewrites with a claim not on the card | 16 | **2** |
| Of those, changing what the CHW would do | not assessed | **1** (uc2_q037) |

- **uc2_q037:** "hair may be cut in the first month". This inverts the card.
- **uc2_q072:** the card names two malaria tests, the blood slide and the RDT. The answer names
  them as "RDT and rapid detection test", which is one test twice. The CHW loses the blood
  slide but is not told to do anything unsafe.

### Latency (median)

| | Baseline | This run |
|---|---|---|
| Model generation (`LLM raw latencyMs`) | 3.3 s | **10.6 s** |
| Send to answer on screen | 15.8 s | **21.7 s** |

The Bangla prompt and the Bangla answer both cost more tokens than the English ones, so every
assisted Bangla turn takes about 6 seconds longer. The ML Kit steps this change removes did not
make up the difference.

## Why the check missed uc2_q037

- **The card has two negations:**
  - `তিন দিন পর্যন্ত নবজাতককে গোসল করানো যাবে না`: no bath for three days;
  - `এক মাস পর্যন্ত চুল কাটা যাবে না`: no haircut for a month.
- **The answer kept the first and dropped the second.** The `missing_negation` rule only asks
  whether the answer has *any* standalone `না` or `নেই`, so the first one satisfied it.
- **A count-based rule would have caught it.** Suppose the rule were "the answer has fewer
  standalone `না`/`নেই`/`নয়` than the card".
  - Replayed on this run's 34 shown rewrites, it falls back on uc2_q037 and on nothing else.
  - It would cost no CORRECT rewrite, and would make the exact-card count 61.
- **This is found after the fact and does not change the result.** The run is scored as it
  ran.

## Other defects seen in shown answers

- **A recurring misspelling.** `মধ্যে` is written `মধ্নে` (or `মধ্নার`) in 4 shown rewrites
  (uc2_q005, q054, q074, q085). No card contains that spelling, so it comes from the model on
  the device.
- **A broken character.** One answer (uc2_q039) contains U+FFFD (`সংখ্�য়ে`), which suggests
  a multi-byte character was split when the engine's output was decoded.
- **Repetition inside one line.** uc2_q039 ends with `120 over 80 mmHg.` twice, and uc2_q095
  repeats a word. The loop rule only compares whole lines.
- **Empty fragments.** uc2_q065 was shown `দ্রুত শ্বাস বলে। শ্বাস-প্রশ্বাসের হার বলে।`
  ("is called fast breathing. is called breathing rate."). No rule requires an answer to carry
  any content, and groundedness passes answers this short.
- **Dropped qualifiers.** Several PARTIAL answers keep the sentence but lose the word that
  carries the instruction:
  - uc2_q047: "mashed" at 6–8 months;
  - uc2_q074: "buffer solution";
  - uc2_q014: "after six months".

## Three shown rewrites that went well

The first is open; click the others to expand.

<details open><summary><b>uc2_q075</b> (CORRECT): one long sentence of referral signs, split into three lines, all signs kept</summary>

**Question:** Patient কে শিশুকে কখন হাসপাতালে রেফার করতে হবে? নিয়ে কী advice দিব?

**Card** `7a1f98e9:6` শিশুকে কখন হাসপাতালে রেফার করতে হবে?:

> শিশুকে হাসপাতালে রেফার করতে হবে যদি দ্রুত শ্বাসের সাথে বুকের পাঁজর ডেবে যায়, শিশুকে খাওয়ানো না যায়, অস্বাভাবিক ঘুম ঘুম ভাব থাকে, শ্বাস-প্রশ্বাসের সময় সাঁই সাঁই বা গড় গড় শব্দ হয়, খিঁচুনি হয়, অতিরিক্ত জ্বর বা স্বাভাবিকের চেয়ে তাপমাত্রা কমে যায়, অথবা অতিরিক্ত বমি হয়।

**Shown to the CHW:**

> 1. শিশুকে হাসপাতালে রেফার করতে হবে যদি দ্রুত শ্বাসের সাথে বুকের পাঁজর ডেবে যায়।
> 2. শিশুকে খাওয়ানো না যায়, অস্বাভাবিক ঘুম ঘুম ভাব থাকে।
> 3. শ্বাস-প্রশ্বাসের সময় সাঁই সাঁই বা গড় গড় শব্দ হয়, খিঁচুনি হয়, অতিরিক্ত জ্বর বা স্বাভাবিকের চেয়ে তাপমাত্রা কমে যায়, অথবা অতিরিক্ত বমি হয়।

</details>

<details><summary><b>uc2_q038</b> (CORRECT): a run-on list of six causes, one per line</summary>

**Question:** ভিজিটে ডায়রিয়া ছড়ানোর কারণ দেখেছি। করণীয় কী?

**Card** `e660517c:3` ডায়রিয়া ছড়ানোর কারণ:

> ডায়রিয়া ছড়ানোর প্রধান কারণগুলো হলো: রান্না ও খাওয়ার জন্য বিশুদ্ধ পানি ব্যবহার না করা, পঁচা বা বাসি খাবার খাওয়া, খাবার ঢেকে না রাখা, মলত্যাগের পরে ও খাবার খাওয়ার আগে সাবান ও নিরাপদ পানি দিয়ে হাত না ধোয়া, স্যানিটারি ল্যাট্রিন ব্যবহার না করা, এবং মাংস, দুধ ও ডিম ভালোভাবে সিদ্ধ করে না খাওয়া।

**Shown to the CHW:**

> 1. রান্না ও খাওয়ার জন্য বিশুদ্ধ পানি ব্যবহার না করা।
> 2. পঁচা বা বাসি খাবার খাওয়া।
> 3. খাবার ঢেকে না রাখা।
> 4. মলত্যাগের পরে ও খাবার খাওয়ার আগে সাবান ও নিরাপদ পানি দিয়ে হাত না ধোয়া।
> 5. স্যানিটারি ল্যাট্রিন ব্যবহার না করা।
> 6. এবং মাংস, দুধ ও ডিম ভালোভাবে সিদ্ধ করে না খাওয়া।

</details>

<details><summary><b>uc2_q057</b> (CORRECT): the redundant middle sentence shortened, the referral kept</summary>

**Question:** প্রসব পরবর্তী জটিলতা: জ্বর — নির্দেশনা কী? আমি কী পরামর্শ দিব?

**Card** `a6032a14:1` প্রসব পরবর্তী জটিলতা: জ্বর:

> প্রসবের পর জ্বর একটি বিপদজনক লক্ষণ। যদি প্রসবের পর মায়ের জ্বর আসে, তবে এটি একটি জটিলতা। এই ক্ষেত্রে দ্রুত স্বাস্থ্যকেন্দ্রে রেফার করতে হবে।

**Shown to the CHW:**

> প্রসবের পর জ্বর একটি বিপদজনক লক্ষণ। এটি একটি জটিলতা। এই ক্ষেত্রে দ্রুত স্বাস্থ্যকেন্দ্রে রেফার করতে হবে।

</details>

## Every shown rewrite that was not CORRECT

These are the 17 rewrites the card check let through that the judge did not mark CORRECT: 3
INCORRECT, then 14 PARTIAL. Click a row to see its question, card and answer.

<details><summary><b>uc2_q037</b> (INCORRECT): Drops the negation in the last sentence: the card says hair must not be cut for a month, the shown text says it may be cut ('এক মাস পর্যন্ত চুল কাটা যাবে'), which inverts the instruction.</summary>

**Question:** নবজাতকের পরিচ্ছন্নতা ও স্বাস্থ্যবিধি — নির্দেশনা কী? আমি কী পরামর্শ দিব?

**Card** `d12af13b:3` নবজাতকের পরিচ্ছন্নতা ও স্বাস্থ্যবিধি:

> নবজাতককে ধরার আগে ও পরে সাবান দিয়ে ভালো করে হাত ধুতে হবে। তিন দিন পর্যন্ত নবজাতককে গোসল করানো যাবে না এবং এক মাস পর্যন্ত চুল কাটা যাবে না।

**Shown to the CHW:**

> নবজাতকে ধরার আগে ও পরে সাবান দিয়ে হাত ধুতে হবে। তিন দিন পর্যন্ত নবজাতকে গোসল করানো যাবে না। এক মাস পর্যন্ত চুল কাটা যাবে।

**Claim not on the card:** `এক মাস পর্যন্ত চুল কাটা যাবে।`

</details>

<details><summary><b>uc2_q065</b> (INCORRECT): Two empty fragments ('দ্রুত শ্বাস বলে', 'শ্বাস-প্রশ্বাসের হার বলে'): no definition of fast breathing or of the rate, and none of the counting method.</summary>

**Question:** দ্রুত শ্বাস ও শ্বাস-প্রশ্বাসের হার গণনা নিয়ে প্রশ্ন এসেছে। দ্রুত ও শ্বাস কী বলব?

**Card** `7a1f98e9:5` দ্রুত শ্বাস ও শ্বাস-প্রশ্বাসের হার গণনা:

> শ্বাসের গতি স্বাভাবিকের চেয়ে বেড়ে গেলে তাকে দ্রুত শ্বাস বলে। একবার শ্বাস নেয়া ও ছাড়াকে শ্বাস-প্রশ্বাস বলা হয়, এভাবে এক মিনিটে যতবার শ্বাস নেয়া ও ছাড়া হয় সেই সংখ্যাকে শ্বাস-প্রশ্বাসের হার বলা হয়। শ্বাস গণনার সময় শিশুকে শান্ত অবস্থায় রাখতে হবে, ঘরে যথেষ্ট আলোর ব্যবস্থা থাকতে হবে। সেকেন্ডের কাঁটাযুক্ত ঘড়ি বা টাইমার শিশুর বুকের উপর রাখতে হবে। আগে ঘড়ি দেখতে হবে পরে শ্বাস গণনা করতে হবে। বুক বা পেটের যে কোন অংশের উঠানামা লক্ষ্য করতে হবে।

**Shown to the CHW:**

> 1. দ্রুত শ্বাস বলে।  
> 2. শ্বাস-প্রশ্বাসের হার বলে।

</details>

<details><summary><b>uc2_q072</b> (INCORRECT): Keeps that two blood tests are needed, but names them as 'RDT and rapid detection test', which is one test twice, dropping the blood-slide test and contradicting the card.</summary>

**Question:** SK হিসেবে ম্যালেরিয়া সনাক্তকরণ পরীক্ষা management কীভাবে করব?

**Card** `dcde24a9:6` ম্যালেরিয়া সনাক্তকরণ পরীক্ষা:

> ম্যালেরিয়া রোগ নিশ্চিত হওয়ার জন্য মানুষের শরীরের রক্ত পরীক্ষা করতে হয়। ম্যালেরিয়া জীবাণু নিশ্চিত হওয়ার জন্য 2 ধরণের রক্ত পরীক্ষা করতে হয়: রক্ত কাচ পরীক্ষা এবং দ্রুত সনাক্তকরণ পরীক্ষা বা আর.ডি.টি।

**Shown to the CHW:**

> 1. ম্যালেরিয়া জীবাণু নিশ্চিত হওয়ার জন্য 2 ধরণের রক্ত পরীক্ষা করতে হয়।  
> 2. আর.ডি.টি এবং দ্রুত সনাক্তকরণ পরীক্ষা করতে হয়।

**Claim not on the card:** `আর.ডি.টি এবং দ্রুত সনাক্তকরণ পরীক্ষা করতে হয়`

</details>

<details><summary><b>uc2_q005</b> (PARTIAL): Keeps most severity facts, but lines 1-3 are garbled ('সারা বিশেষ বাংলাদেশে', 'দুই থেকে তা থেকে পাঁচ', 'কিছুদিনের মধ্নার সৃষ্টি হয়' losing 'resolves within days') and it drops the 1.1 lakh deaths a year.</summary>

**Question:** ডায়রিয়া: ভয়াবহতা নিয়ে স্বাস্থ্যকর্মীর করণীয় কী?

**Card** `952fbce0:5` ডায়রিয়া: ভয়াবহতা:

> ডায়রিয়া এবং তা থেকে সৃষ্ট অপুষ্টি সারা বিশ্বে, বিশেষ করে বাংলাদেশে, শিশুর অসুস্থতা ও মৃত্যুর অন্যতম প্রধান কারণ। আমাদের ও আমাদের পার্শ্ববর্তী দেশসমূহে পাঁচ বছরের কম বয়সী শিশুরা বছরে অন্তত দুই থেকে পাঁচ বার ডায়রিয়ায় ভোগে এবং এই রোগে অন্তত বিশ থেকে ত্রিশ দিন অসুস্থ থাকে। বেশিরভাগ ডায়রিয়া কিছুদিনের মধ্যেই সেরে যায় এবং কোনো প্রকার ঔষধের দরকার হয় না। তবে, শুধু দশ শতাংশ ডায়রিয়ায় মারাত্মক পানিস্বল্পতার সৃষ্টি হয় এবং তা থেকে পাঁচ বছরের কম বয়সী বাচ্চা মারা যেতে পারে। বাংলাদেশে বছরে প্রায় এক লক্ষ দশ হাজার শিশু ডায়রিয়া থেকে মারা যায়।

**Shown to the CHW:**

> 1. ডায়রিয়া এবং তা থেকে সৃষ্ট অপুষ্টি সারা বিশেষ বাংলাদেশে শিশুর অসুস্থতা ও মৃত্যুর অন্যতম কারণ।  
> 2. আমাদের ও আমাদের পার্শ্ববর্তী দেশসমূহে পাঁচ বছরের কম বয়সী শিশুরা বছরে অন্তত দুই থেকে তা থেকে পাঁচ বার ডায়রিয়ায় ভোগে এবং এই রোগে অন্তত বিশ থেকে ত্রিশ দিন অসুস্থ থাকে।  
> 3. বেশিরভাগ ডায়রিয়া কিছুদিনের মধ্নার সৃষ্টি হয় এবং কোনো প্রকার ঔষধের দরকার হয় না।  
> 4. তবে, শুধু দশ শতাংশ ডায়রিয়ায় মারাত্মক পানিস্বল্পতার সৃষ্টি হয় এবং তা থেকে পাঁচ বছরের কম বয়সী বাচ্চা মারা যেতে পারে।

</details>

<details><summary><b>uc2_q014</b> (PARTIAL): All three points are there, but line 1 drops 'after six months' and 'only', so it states that mother's milk is not enough for the child without the qualifier.</summary>

**Question:** মাকে ৬ মাসের পর কেন পারিবারিক খাবার প্রয়োজন? সম্পর্কে কী কাউন্সেলিং দিব?

**Card** `0fa2eaf5:1` ৬ মাসের পর কেন পারিবারিক খাবার প্রয়োজন?:

> ছয় মাসের পর শুধু মায়ের দুধ শিশুর জন্য যথেষ্ট নয়। প্রয়োজনীয় পুষ্টির জন্য শিশুকে অন্যান্য খাবার দিতে হবে। ছয় মাস বয়স থেকে অন্যান্য খাবার না দিলে শিশুর স্বাভাবিক বৃদ্ধি ঘটবে না এবং শিশু অপুষ্টিতে ভুগবে।

**Shown to the CHW:**

> 1. মায়ের দুধ শিশুর জন্য যথেষ্ট নয়।  
> 2. প্রয়োজনীয় পুষ্টির জন্য শিশুকে অন্যান্য খাবার দিতে হবে।  
> 3. ছয় মাস বয়স থেকে অন্যান্য খাবার না দিলে শিশুর স্বাভাবিক বৃদ্ধি ঘটবে না এবং শিশু অপুষ্টিতে ভুগবে।

</details>

<details><summary><b>uc2_q024</b> (PARTIAL): Keeps pads not cloth (four a day), daily bathing, breast cleaning and nipple massage, but drops keeping perineal stitches clean and dry.</summary>

**Question:** মাকে প্রসব পরবর্তী মায়ের যত্ন: ব্যক্তিগত পরিচ্ছন্নতা সম্পর্কে কী কাউন্সেলিং দিব?

**Card** `f62c103f:2` প্রসব পরবর্তী মায়ের যত্ন: ব্যক্তিগত পরিচ্ছন্নতা:

> প্রসবের পর চার সপ্তাহ পর্যন্ত রক্ত মিশ্রিত স্রাব বের হয়, সেজন্য কাপড় ব্যবহার না করে ন্যাপকিন ব্যবহার করতে হবে (দিনে চারটি)। প্রতিদিন গোসল করতে হবে, স্তন পরিষ্কার করতে হবে এবং স্তনের বোটা ভিতরের দিকে দেওয়া থাকলে তেল দিয়ে মালিশ করতে হবে। পেরিনিয়ামে সেলাই থাকলে তা পরিষ্কার ও শুকনো রাখতে হবে।

**Shown to the CHW:**

> প্রসবের পর চার সপ্তাহ পর্যন্ত রক্ত মিশ্রিত স্রাব বের হয়, সেজন্য কাপড় ব্যবহার না করে ন্যাপকিন ব্যবহার করতে হবে (দিনে চারটি)। প্রতিদিন গোসল করতে হবে, স্তন পরিষ্কার করতে হবে এবং স্তনের বোটা ভিতরের দিকে দেওয়া থাকলে তেল দিয়ে মালিশ করতে হবে।

</details>

<details><summary><b>uc2_q025</b> (PARTIAL): Three of the four benefits as written; drops that family food means less trouble and less time.</summary>

**Question:** পারিবারিক খাবার খাওয়ানোর প্রয়োজনীয়তা ও উপকারিতা নিয়ে স্বাস্থ্যকর্মীর করণীয় কী?

**Card** `0fa2eaf5:2` পারিবারিক খাবার খাওয়ানোর প্রয়োজনীয়তা ও উপকারিতা:

> বাড়তি খাবার কেনার প্রয়োজন হয় না এবং খরচ কম হয়। শারীরিক বৃদ্ধি ও মানসিক বিকাশ ভালোভাবে হয়, ফলে শিশু সুস্থ ও হাসিখুশি থাকে। রোগ প্রতিরোধ ক্ষমতা বাড়ে ফলে রোগ-ব্যাধি কম হয়, শিশু অসুস্থ হলে তাড়াতাড়ি সুস্থ হয়ে উঠে। ঝামেলা কম ও সময় কম লাগে।

**Shown to the CHW:**

> বাড়তি খাবার কেনার প্রয়োজন হয় না এবং খরচ কম হয়। শারীরিক বৃদ্ধি ও মানসিক বিকাশ ভালোভাবে হয়, ফলে শিশু সুস্থ ও হাসিখুশি থাকে। রোগ প্রতিরোধ ক্ষমতা বাড়ে ফলে রোগ-ব্যাধি কম হয়, শিশু অসুস্থ হলে তাড়াতাড়ি সুস্থ হয়ে উঠে।

</details>

<details><summary><b>uc2_q039</b> (PARTIAL): Keeps visit date, month, weight and height, but the month sentence is garbled (with a broken character, 'সংখ্�য়ে'), and it omits temperature, the blood-pressure instruction and fundal height, ending with '120 over 80 mmHg.' twice.</summary>

**Question:** মাকে গর্ভবতী মহিলার ভিজিট ও শারীরিক পরিমাপের তথ্য সম্পর্কে কী কাউন্সেলিং দিব?

**Card** `f9cdf65f:3` গর্ভবতী মহিলার ভিজিট ও শারীরিক পরিমাপের তথ্য:

> স্বাস্থ্যকর্মী যে দিন গর্ভবতী মহিলাকে এএনসি সেবা দিবেন সেই ভিজিটের তারিখ লিখতে হবে। স্বাস্থ্যকর্মী গর্ভের যে মাসে গর্ভবতী মহিলাকে এএনসি সেবা দিবেন সংখ্যা অনুযায়ী সেই মাস লিখতে হবে। এএনসি চেকআপের সময় গর্ভবতী মহিলার ওজন (কেজিতে) সঠিকভাবে নির্ণয় করে সংখ্যায় লিখতে হবে। প্রথম এএনসি চেকআপে গর্ভবতী মহিলার উচ্চতা (সেন্টিমিটারে) সঠিকভাবে নির্ণয় করে সংখ্যায় লিখতে হবে। থার্মোমিটারে গর্ভবতী মহিলার শরীরের তাপমাত্রা (ফারেনহাইট ডিগ্রিতে) সঠিকভাবে মেপে সংখ্যায় লিখতে হবে। গর্ভবতী মহিলার রক্তচাপ সঠিকভাবে পরীক্ষা করে সংখ্যায় লিখতে হবে, যেমন 120 over 80 mmHg। ফিতা (মেজারিং টেপ) দিয়ে গর্ভবতী মায়ের জরায়ুর উচ্চতা (সেন্টিমিটারে) সঠিকভাবে মেপে সংখ্যায় লিখতে হবে।

**Shown to the CHW:**

> স্বাস্থ্যকর্মী যে দিন গর্ভবতী মহিলাকে এএনসি সেবা দিবেন সেই ভিজিটের তারিখ লিখতে হবে। স্বাস্থ্যকর্মী গর্ভের মাস গর্ভবতী মহিলাকে এএনসি সেবা দিবেন সংখ্�য়ে লিখতে হবে। এএনসি চেকআপের সময় গর্ভবতী মহিলার ওজন সঠিকভাবে নির্ণয় করে সংখ্যায় লিখতে হবে। প্রথম এএনসি চেকআপে গর্ভবতী মহিলার উচ্চতা (সেন্টিমিটারে) সঠিকভাবে নির্ণয় করে সংখ্যায় লিখতে হবে। 120 over 80 mmHg. 120 over 80 mmHg.

</details>

<details><summary><b>uc2_q041</b> (PARTIAL): The TB-care steps are all there, but the list of testing places is cut to upazila health centres and chest clinics, dropping community clinics, district and other hospitals and the rest.</summary>

**Question:** কোথায় কফ পরীক্ষা করা হয় এবং যক্ষ্মা ধরা পড়লে করণীয় কী? SK হিসেবে আমি রোগীকে কী বলব?

**Card** `8528ce04:3` কোথায় কফ পরীক্ষা করা হয় এবং যক্ষ্মা ধরা পড়লে করণীয়:

> কফ পরীক্ষা করা হয় সকল উপজেলা স্বাস্থ্যকেন্দ্র, সকল বক্ষব্যাধি ক্লিনিক ও হাসপাতাল, জেলা সদর হাসপাতাল, বিশেষায়িত হাসপাতাল, কমিউনিটি ক্লিনিক, নির্দিষ্ট এনজিও ক্লিনিক, সম্মিলিত সামরিক হাসপাতাল, সকল ইপিজেড, ফ্যাক্টরি, বিজিএমআই, বিকেএমআই এবং কারাগারসমূহে। যক্ষ্মা রোগ ধরা পড়লে দ্রুত চিকিৎসা শুরু করতে হবে। নিয়মিত এবং পূর্ণ মেয়াদে সেবিকার সামনে ওষুধ সেবন করতে হবে। চিকিৎসা চলাকালীন সময় রোগের অবস্থা বোঝার জন্য কফ পরীক্ষা করাতে হবে। যেখানে সেখানে কফ বা থুথু ফেলা যাবে না, রোগীর কফ, থুথু নির্দিষ্ট পাত্রে ফেলে পরে তা পুঁতে বা পুড়িয়ে ফেলতে হবে। হাঁচি, কাশির সময় রোগীকে মুখে রুমাল অথবা কাপড় ব্যবহার করতে হবে।

**Shown to the CHW:**

> 1. কফ পরীক্ষা করা হয় সকল উপজেলা স্বাস্থ্যকেন্দ্র, সকল বক্ষব্যাধি ক্লিনিক ও হাসপাতাল।  
> 2. যক্ষ্মা ধরা পড়লে দ্রুত চিকিৎসা শুরু করতে হবে।  
> 3. নিয়মিত এবং পূর্ণ মেয়াদে সেবিকার সামনে ওষুধ সেবন করতে হবে।  
> 4. চিকিৎসা চলাকালীন সময় রোগের অবস্থা বোঝার জন্য কফ পরীক্ষা করাতে হবে।  
> 5. সেখানে কফ বা থুথু ফেলা যাবে না, রোগীর কফ, থুথু নির্দিষ্ট পাত্রে ফেলে পরে তা পুঁতে বা পুড়িয়ে ফেলতে হবে।  
> 6. হাঁচি, কাশির সময় রোগীকে মুখে রুমাল অথবা কাপড় ব্যবহার করতে হবে।

</details>

<details><summary><b>uc2_q044</b> (PARTIAL): Keeps asking what problems children had and listening, but drops then discussing the common and dangerous side effects and their management, which is what the question asks.</summary>

**Question:** মাকে টিকার সাধারণ ও বিপদজনক পার্শ্বপ্রতিক্রিয়া এবং ব্যবস্থাপনা সম্পর্কে কী কাউন্সেলিং দিব?

**Card** `8f41be1a:3` টিকার সাধারণ ও বিপদজনক পার্শ্বপ্রতিক্রিয়া এবং ব্যবস্থাপনা:

> টিকা নেয়ার পর শিশুরা কি ধরণের সমস্যার সম্মুখীন হয় তা জানতে চাইবেন। তাদের বলা কথাগুলো মনযোগ সহকারে শুনবেন। পরে নিজে টিকার সাধারণ ও বিপদজনক সমস্যা এবং এর সমাধান নিয়ে আলোচনা করবেন। টিকার সাধারণ ও বিপদজনক পার্শ্বপ্রতিক্রিয়া এবং ব্যবস্থাপনা বলতে পারবেন।

**Shown to the CHW:**

> টিকা নেয়ার পর শিশুরা কি ধরণের সমস্যার সম্মুখীন হয় তা জানতে চাইবেন। তাদের বলা কথাগুলো মনযোগ সহকারে শুনবেন।

</details>

<details><summary><b>uc2_q047</b> (PARTIAL): Keeps small pieces at 9-11 months, but drops the consistency for the other two ages: 'mashed' at 6-8 months and 'like adults' at 12-24 months become just 'give food'.</summary>

**Question:** বয়স অনুযায়ী শিশুর খাবারের ঘনত্ব — নির্দেশনা কী? আমি কী পরামর্শ দিব?

**Card** `0fa2eaf5:4` বয়স অনুযায়ী শিশুর খাবারের ঘনত্ব:

> ছয় মাস থেকে আট মাস বয়সী শিশুদের চটকানো খাবার দিতে হবে। নয় মাস থেকে এগারো মাস বয়সী শিশুদের খাবার ছোট ছোট টুকরা করে দিতে হবে। বারো মাস থেকে চব্বিশ মাস বয়সী উপরের শিশুদের বড়দের মতো খাবার দিতে হবে।

**Shown to the CHW:**

> ছয় মাস থেকে আট মাস বয়সী শিশুদের খাবার দিতে হবে।  
> নয় মাস থেকে এগারো মাস বয়সী খাবার ছোট ছোট টুকরা করে দিতে হবে।  
> বারো মাস থেকে চব্বিশ মাস বয়সী শিশুদের খাবার দিতে হবে।

</details>

<details><summary><b>uc2_q054</b> (PARTIAL): All the indirect causes are there, but it drops that most pneumonia is bacterial and the direct/indirect framing, and misspells 'মধ্যে' as 'মধ্নে' twice.</summary>

**Question:** মাকে শ্বাসতন্ত্রের সংক্রমণের কারণ সম্পর্কে কী কাউন্সেলিং দিব?

**Card** `7a1f98e9:4` শ্বাসতন্ত্রের সংক্রমণের কারণ:

> শ্বাসতন্ত্রের সংক্রমণের প্রত্যক্ষ কারণ হলো ব্যাকটেরিয়া এবং ভাইরাস উভয়ই, যার মধ্যে অধিকাংশ ক্ষেত্রে ব্যাকটেরিয়ার কারণে নিউমোনিয়া হয়ে থাকে। পরোক্ষ কারণগুলোর মধ্যে রয়েছে শিশুর জন্মকালীন ওজন যদি দুই দশমিক পাঁচ কেজির কম হয়, শিশুকে যদি জন্মের পর পর গোসল দেয়া হয়, শিশু যদি পুষ্টিহীনতায় ভোগে, শিশুকে যদি জন্মের পর শাল দুধ খাওয়ানো না হয়, শিশুকে যদি ছয় মাস পর্যন্ত শুধুমাত্র বুকের দুধ খাওয়ানো না হয়, শিশুকে যদি রোগ প্রতিষেধক টীকা না দেয়া হয়, শিশুর যদি ভিটামিন এ এর অভাব থাকে, বাড়ির ভেতরের আবহাওয়া যদি বদ্ধ, ঠান্ডা, ভেজা বা স্যাঁতস্যাঁতে হয়, এবং যদি এক ঘরের মধ্যে অনেক লোক একসাথে বসবাস করে (ঘন বসতি)।

**Shown to the CHW:**

> শ্বাসতন্ত্রের সংক্রমণের কারণ হলো ব্যাকটেরিয়া এবং ভাইরাস উভয়ই, পরোক্ষ কারণগুলোর মধ্নে শিশুর জন্মকালীন ওজন যদি দুই দশমিক পাঁচ কেজির কম হয়, শিশুকে যদি জন্মের পর পর গোসল দেয়া হয়, শিশু যদি পুষ্টিহীনতায় ভোগে, শিশুকে যদি জন্মের পর শাল দুধ খাওয়ানো না হয়, শিশুকে যদি ছয় মাস পর্যন্ত শুধুমাত্র বুকের দুধ খাওয়ানো না হয়, শিশুকে যদি রোগ প্রতিষেধক টীকা না দেয়া হয়, শিশুর যদি ভিটামিন এ এর অভাব থাকে, বাড়ির ভেতরের আবহাওয়া যদি বদ্ধ, ঠান্ডা, ভেজা বা স্যাঁতস্যাঁতে হয়, এবং যদি এক ঘরের মধ্নে অনেক লোক একসাথে বসবাস করে (ঘন বসতি)।

</details>

<details><summary><b>uc2_q071</b> (PARTIAL): The ultrasound and TT points and the iron not-taken and irregular marks are all there, but it drops asking whether iron is taken as directed and ticking it.</summary>

**Question:** আল্ট্রাসোনোগ্রাম, টিটি টিকা ও আয়রন/ক্যালসিয়াম ট্যাবলেট — key counselling points কী?

**Card** `f9cdf65f:6` আল্ট্রাসোনোগ্রাম, টিটি টিকা ও আয়রন/ক্যালসিয়াম ট্যাবলেট:

> আল্ট্রাসোনোগ্রাম করা হয়ে থাকলে টিক (✓) চিহ্ন দিতে হবে। 20 সপ্তাহের মাঝে আল্ট্রাসোনোগ্রাম করার জন্য উৎসাহিত করা। আল্ট্রাসোনোগ্রাম করা না হয়ে থাকলে (X) চিহ্ন দিতে হবে। গর্ভবতী মহিলার সাথে কথা বলে অথবা টিটি টিকার কার্ড দেখে লিখতে হবে। টিকার কার্ড না থাকলে টিটি টিকার সম্পর্কিত ডোজ সংখ্যায় লিখে পাশে '+' দিতে হবে। পূর্বে টিটি টিকার ডোজ সম্পূর্ণ করে থাকলে কার্ডে সম্পূর্ণ কথাটি লিখতে হবে। এএনসি চেকআপের সময় গর্ভবতী মহিলাকে সেবা প্রদান করার সময় আয়রন বড়ি নিয়ম অনুযায়ী খাচ্ছে কিনা তা জিজ্ঞাসা করে এই ঘরে টিক (✓) চিহ্ন দিতে হবে। আয়রন বড়ি না খেলে (X) চিহ্ন দিতে হবে। আয়রন বড়ি অনিয়মিত খেলে ঘরে টিক (✓) চিহ্ন দিয়ে অনিয়মিত কথাটি লিখতে হবে। ক্যালসিয়াম ট্যাবলেটের ক্ষেত্রেও একই পদ্ধতি অনুসরণ করতে হবে।

**Shown to the CHW:**

> 1. আল্ট্রাসোনোগ্রাম করা হয়ে থাকলে টিক (✓) চিহ্ন দিতে হবে।  
> 2. 20 সপ্তাহের মাঝে আল্ট্রাসোনোগ্রাম করার জন্য উৎসাহিত করা।  
> 3. আল্ট্রাসোনোগ্রাম করা না হয়ে থাকলে (X) চিহ্ন দিতে হবে।  
> 4. গর্ভবতী মহিলার সাথে কথা বলে অথবা টিটি টিকার কার্ড দেখে লিখতে হবে।  
> 5. টিকার কার্ড না থাকলে টিটি টিকার সম্পর্কিত ডোজ সংখ্যায় লিখে পাশে '+' দিতে হবে।  
> 6. পূর্বে টিটি টিকার ডোজ সম্পূর্ণ করে থাকলে কার্ডে সম্পূর্ণ কথাটি লিখতে হবে।  
> 7. আয়রন বড়ি না খেলে (X) চিহ্ন দিতে হবে।  
> 8. আয়রন বড়ি অনিয়মিত খেলে ঘরে টিক (✓) চিহ্ন দিয়ে অনিয়মিত কথাটি লিখতে হবে।  
> 9. ক্যালসিয়াম ট্যাবলেটের ক্ষেত্রেও একই পদ্ধতি অনুসরণ করতে হবে।

</details>

<details><summary><b>uc2_q074</b> (PARTIAL): The steps and the 15-20 minute window are there, but step 3 drops 'buffer solution', so 'put two drops in the same well' no longer says what the drops are.</summary>

**Question:** ডেঙ্গু র‍্যাপিড ডায়াগনস্টিক টেস্ট (RDT) কিট ব্যবহারের পদ্ধতি danger signs ও করণীয় কী?

**Card** `e65337d3:1` ডেঙ্গু র‍্যাপিড ডায়াগনস্টিক টেস্ট (RDT) কিট ব্যবহারের পদ্ধতি:

> প্রথমে রোগীর আঙুল থেকে রক্ত সংগ্রহ করুন। সংগৃহীত রক্তের এক ফোঁটা কিটের গোলাকার গর্তে (স্যাম্পল ওয়েল) দিন। এরপর বাফার সলিউশনের দুই ফোঁটা একই গোলাকার গর্তে দিন। 15 থেকে 20 মিনিট অপেক্ষা করুন। এই সময়ের মধ্যে ফলাফল দেখা যাবে। 20 মিনিটের বেশি সময় ধরে ফলাফল দেখলে তা সঠিক নাও হতে পারে।

**Shown to the CHW:**

> 1. ডেঙ্গু রোগীর আঙুল থেকে রক্ত সংগ্রহ করুন।  
> 2. এক ফোঁটা কিটের গোলাকার গর্তে দিন।  
> 3. দুই ফোঁটা একই গোলাকার গর্তে দিন।  
> 4. 15 থেকে 20 মিনিট অপেক্ষা করুন।  
> 5. এই সময়ের মধ্নে ফলাফল দেখা যাবে।  
> 6. 20 মিনিটের বেশি সময় ধরে ফলাফল দেখলে তা সঠিক নাও হতে পারে।

</details>

<details><summary><b>uc2_q081</b> (PARTIAL): Keeps breathing rate, abnormal breathing referral (over 60 a minute), cord and eye marks, but drops birth date, birth weight and skin, and drops the doctor referral for an abnormal cord.</summary>

**Question:** নবজাতকের জন্ম ও শারীরিক অবস্থার তথ্য — key counselling points কী?

**Card** `f9cdf65f:7` নবজাতকের জন্ম ও শারীরিক অবস্থার তথ্য:

> নবজাতকের জন্ম তারিখ লিখতে হবে। চিকিৎসক/স্বাস্থ্যকর্মীর ব্যবস্থাপত্র দেখে অথবা প্রসূতি বা পরিবারের অন্য সদস্যদের সাথে আলাপ করে নবজাতকের জন্ম ওজন লিখতে হবে। নবজাতকের শ্বাসের হার ফাঁকা ঘরে লিখতে হবে। নবজাতকের শ্বাস অস্বাভাবিক থাকলে অর্থাৎ শ্বাসকষ্ট অথবা শ্বাসের অস্বাভাবিক শব্দ যেমন ঘড়ঘড় বা বাঁশির মত শাঁইশাঁই আওয়াজ থাকলে এবং শান্ত অবস্থায় শ্বাসের গতি মিনিটে 60 বারের বেশি থাকলে চিকিৎসকের কাছে রেফার করতে হবে। নবজাতকের নাভী শুকনো ও স্বাভাবিক থাকলে টিক (√) চিহ্ন দিতে হবে। নাভী অস্বাভাবিক থাকলে অস্বাভাবিক এর উপর (✔) চিহ্ন দিতে হবে এবং চিকিৎসকের কাছে রেফার করতে হবে। নবজাতকের চোখ স্বাভাবিক হলে টিক (√) চিহ্ন দিতে হবে। অস্বাভাবিক থাকলে চিকিৎসকের কাছে রেফার করতে হবে। নবজাতকের ত্বক স্বাভাবিক হলে স্বাভাবিক এর উপর টিক (√) চিহ্ন দিতে হবে। অস্বাভাবিক থাকলে চিকিৎসকের কাছে রেফার করতে হবে।

**Shown to the CHW:**

> 1. নবজাতকের জন্ম ও শারীরিক অবস্থার তথ্য লিখতে হবে।  
> 2. নবজাতকের শ্বাস হার ফাঁকা ঘরে লিখতে হবে।  
> 3. নবজাতকের শ্বাস অস্বাভাবিক থাকলে অর্থাৎ শ্বাসকষ্ট অথবা শ্বাসের অস্বাভাবিক শব্দ যেমন ঘড়ঘড় বা বাঁশির মত শাঁইশাঁই আওয়াজ থাকলে এবং শান্ত অবস্থায় শ্বাসের গতি মিনিটে 60 বারের বেশি থাকলে চিকিৎসকের কাছে রেফার করতে হবে।  
> 4. নবজাতকের নাভী শুকনো ও স্বাভাবিক থাকলে টিক (√) চিহ্ন দিতে হবে।  
> 5. নাভী অস্বাভাবিক থাকলে অস্বাভাবিক এর উপর (✔) চিহ্ন দিতে হবে।  
> 6. নবজাতকের চোখ স্বাভাবিক হলে টিক (√) চিহ্ন দিতে হবে।  
> 7. অস্বাভাবিক থাকলে চিকিৎসকের কাছে রেফার করতে হবে।

</details>

<details><summary><b>uc2_q091</b> (PARTIAL): Gives only the insecticide-treated net, dropping nightly use, nets when sleeping away from home, smoke or spray, covering the body, repellent and window netting.</summary>

**Question:** মশার কামড় থেকে আত্মরক্ষার উপায় — ki guidance dibo?

**Card** `dcde24a9:8` মশার কামড় থেকে আত্মরক্ষার উপায়:

> ম্যালেরিয়া প্রতিরোধের উল্লেখযোগ্য ব্যবস্থার মধ্যে মশার কামড় থেকে আত্মরক্ষা করা একটি গুরুত্বপূর্ণ উপায়। মশার কামড় থেকে আত্মরক্ষার উপায়গুলি হচ্ছে: কীটনাশকযুক্ত মশারি (ITN) প্রতিদিন সন্ধ্যা হওয়ার সঙ্গে সঙ্গে টানানো ও ব্যবহার করা। জুম চাষ করতে বা বনে বাঁশ বা কাঠ কাটতে বাড়ির বাইরে অবস্থান বা রাত্রিযাপন করতে হলে ঘুমানোর সময় কীটনাশকযুক্ত মশারি (ITN) ব্যবহার করা। মশা তাড়াবার ধোঁয়া, স্প্রে ইত্যাদি ব্যবহার করা। যতটুক সম্ভব শরীর ঢেকে রাখা (ফুল হাতা শার্ট পরিধান ইত্যাদি)। সম্ভব হলে শরীরে অনাবৃত অংশে মশা বিতারক ক্রীম বা লোশন এবং শোবার ঘরে দরজা জানালায় মশা প্রতিবন্ধক জাল (Mosquito Proof Net) ব্যবহার করা।

**Shown to the CHW:**

> মশার কামড় থেকে আত্মরক্ষার উপায় কীটনাশকযুক্ত মশারি ব্যবহার করা।

</details>

<details><summary><b>uc2_q094</b> (PARTIAL): Keeps the quick hospital referral, but drops clean sanitary pads and cuts the fluids to 'water and sharbat', losing saline.</summary>

**Question:** যোনীপথে বেশী রক্তক্ষরণ ও তলপেটে ব্যথা somporke ki jante hobe SK hishebe?

**Card** `cb824f82:1` যোনীপথে বেশী রক্তক্ষরণ ও তলপেটে ব্যথা:

> যদি গর্ভবতী মায়ের যোনীপথে বেশী রক্তক্ষরণ হয় এবং তলপেটে ব্যথা থাকে, তাহলে তাকে দ্রুত হাসপাতালে রেফার করুন। তাকে পরিষ্কার স্যানিটারী ন্যাপকিন পরে থাকতে বলুন এবং ঘন ঘন তরল খাবার যেমন স্যালাইন, পানি, শরবত, ইত্যাদি খেতে বলুন।

**Shown to the CHW:**

> যদি গর্ভবতী মায়ের যোনীপথে বেশী রক্তক্ষরণ হয় এবং তলপেটে ব্যথা থাকে, তাকে দ্রুত হাসপাতালে রেফার করুন। পানি এবং শরবত খেতে বলুন।

</details>

## Caveats

- **One judge.** Claude judged the 39 open rows. The exact-card count sits exactly on the bar,
  so one verdict would move it either way. The decisive failure, uc2_q037, is not a judgement
  call: the shown sentence says the opposite of the card.
- **The check was built and tuned on this bank.** The spike's 77 rows came from these
  questions, and the post-review amendments were tested against them. A held-out bank has not
  been run.
- **The harness now reads rewrites from the SDK's `SHOWN` line.** The app renders a list as one
  text view per item, so the screen capture held only the first item.
  - When the capture is part of the `SHOWN` text, the harness uses the `SHOWN` text. When they
    disagree, it keeps the screen text and flags the row `shown_mismatch`.
  - No row in this run was flagged.
- **Ollama and the device differ.** The spike reached 66 of 77 on Ollama, and the device
  reached 60. The device build falls back more often: 43 of 77 rows, against 26 in the
  spike.

## The choice this puts to the product owner

Spec §5 says a fail is followed by a comparison with showing the card verbatim.

| On the 77 exact-card rows | Verbatim only | This change as run |
|---|---|---|
| CORRECT | **77** (by definition: the right card, as written) | 60 |
| The CHW gets a shorter, focused answer | never | 34 rows (17 of them CORRECT) |
| Shown answers that contradict the card | 0 | 1 |
| Model generation per turn | none | 10.6 s median |

These are the options:

1. **Verbatim only on Bangla turns.** Every exact-card answer is right, with no generation
   risk. The CHW reads the whole card.
2. **Keep the rewrite, with a count-based negation rule, and pre-register a new run on a
   held-out bank.** The count rule would have caught the one failure here, at no measured cost.
   But 14 of the 34 shown rewrites still lose a point, and the model's `মধ্নে` misspelling and
   broken characters would still reach the CHW.

**Recommendation: option 1 now.**
- An answer that inverted a newborn-care instruction reached the CHW.
- Of the 34 rewrites shown, 14 lose a point, against zero for the verbatim card.
- Every assisted Bangla turn is about 6 seconds slower.

Option 2 is worth running as an experiment behind the same setting, with the count rule and a
fresh held-out bank, before any rewrite is shown to CHWs again.

## Where the data is

- Run folder (gitignored): `chat-eval/runs/card-sentence-assisted/`.
  - `report.md` and `diff.md` (compared with `full-assisted`);
  - `judge_sheet.md`;
  - `verdicts/primary.jsonl`;
  - `turns.jsonl`, with each turn's full trace.
- Smoke run: `chat-eval/runs/smoke-card-sentence/`.
