# Offline chat: what we tested, and a proposed way forward

**Date:** 1 October 2026
**For:** the team discussion on the offline AI Coach
**Details:** `docs/eval/2026-09-29-offline-answer-model-summary.md` and the documents it links to

## Summary

- **The offline chat works in two steps.** First it finds the right card in the published
  modules. Then, in "simple words" mode, a small on-device language model rewrites that card
  as a short answer and human-like response.
- **Finding the card works well, and embeddings make it better.** With embeddings, the right
  card is served for 78 of 95 questions, against 71 of 95 with keyword search alone.
- **The rewrite is the problem.** Every model we tried either changed the meaning of the card
  sometimes, left out things the health worker needs, or added things the card does not say.
  Showing the card as written was more accurate than any model's rewrite.
- **We propose two offline paths, both showing the card as written:**
  - **Standard:** keyword search, with no extra download.
  - **Enhanced:** keyword search plus embeddings, for more accurate retrieval. It needs a
    one-time download of about 176 MB.

  The on-device rewrite model would be removed from the default experience.
- **In the long run,** a small model trained on our own published modules, and retrained as
  they change, is the most promising way to bring rewritten answers back safely.

## How the offline chat answers today

1. **Find the card.** The question is matched against every card in the synced modules.
   - **Keyword search (BM25)** matches the words of the question.
   - **Embeddings** match the meaning, so a question phrased differently from the card can
     still find it.

   A gate then decides whether the best card is good enough to serve, or whether to say
   "I don't know".
2. **Rewrite the card ("simple words" mode, phones with 3 GB of RAM or more).**
   - Qwen3 0.6B (a 497 MB download) answers the question using the card.
   - Because it works best in English, the phone translates the question and card into English
     with Google ML Kit, and translates the answer back into Bangla.

## What we tested

All the answer tests below gave the model the **right card**, so they measure only the
rewrite, not the search.

| When | What | Result |
|---|---|---|
| 24 Sep | Today's rewrite path on the Android emulator (ML Kit translation + Qwen3 0.6B), 100 questions | When the right card was found, only **26 of 77** answers were correct |
| 25 Sep | Rewrite without translation: Bangla card and question given straight to the model. Qwen3 0.6B and 1.7B, Gemma 3 1B, seven prompt styles each | Best: Qwen3 0.6B listing the card's sentences, **47 of 77**. The other models invented advice or switched language |
| 27 Sep | That best setting on the emulator, with a safety check that shows the card when an answer looks wrong | **60 of 77**, but one answer reversed an instruction ("the baby's hair may be cut in the first month"). Most answers fell back to the card anyway, and each took 10.6 s |
| 28–29 Sep | Larger and Bangla-specialised models: Gemma 4 E2B, Gemma 3 4B, Qwen3.5 2B, TigerLLM, BanglaLlama, speaklar. Eight prompt styles, then 120 questions, 25 of them new | Only **Gemma 4 E2B** was usable: 79 of 120 alone, 95 of 120 with the safety check. The card shown as written: **105 of 120** |

Scores from different rows aren't directly comparable, because the scoring was made stricter
along the way. The pattern in every row is the same.

## Quick findings

- **No rewrite model beat showing the card.**
  - The best, Gemma 4 E2B, was right 95 times in 120, with the safety check catching its
    worst answers. Showing the card was right 105 times.
  - Gemma 4 E2B is also a 2.58 GB download, about five times today's model.
- **The models fail in three ways:**
  - **Leaving out what the health worker needs.** For example, the vaccination schedule
    without "BCG as soon as possible after birth".
  - **Changing the meaning.** "Ten zinc tablets every day" where the card says one a day, ten
    in total. "A 2.1 kg baby is above the 2.5 kg line".
  - **Adding things that aren't on the card.** Invented check-up counts, or paracetamol for a
    low temperature.
- **Bangla-specialised models did worse, not better.**
  - TigerLLM, BanglaLlama and speaklar know Bangla words, but they are small and were not
    trained to answer from a given text.
  - They looped, repeated the question, answered in English, or produced broken Bangla.
- **The safety check helps, but in practice it brings back the card.**
  - It blocks an answer that drops one of the card's numbers, negations or referrals.
  - So the answers it lets through are nearly the whole card, and the rewrite then adds little.

## Caveats if we keep a language model for answers

If a rewrite model stays in the product, even as an option:

- **It is a liability on medical content.** In every test, some answers changed an instruction
  or a number. One wrong answer from a trusted assistant can do more harm than a long card.
- **The check can't catch everything.** It caught answers with wrong or missing numbers, but
  not an answer that keeps the right numbers and attaches them to the wrong advice (the
  paracetamol case).
- **It is large and slow on a phone:**
  - 497 MB today, 2.58 GB for the only model that came close;
  - about 1.7 GB of memory;
  - several seconds per answer.
- **It would need to be re-tested whenever the modules change,** because its errors depend on
  the card it is given.

## Why better translation doesn't fix it

- **Translation damages the answer at every step.** Today the question and the card are
  translated into English, and the answer is translated back. On the emulator, translation
  garbled the question, dropped or changed details on the card, and mistranslated the answer.
  This is the main reason only 26 of 77 answers were correct.
- **Medical text is unforgiving.** A single lost "not", or a changed number, reverses the
  advice. Even a very good translator makes those errors sometimes, and we would be
  translating three times per answer.
- **More accurate translators are much larger.** ML Kit's language packs are small. Stronger
  translation models are hundreds of megabytes to several gigabytes. That would come on top of
  the rewrite model, for a gain we can't count on.
- **Skipping translation removes this problem, but the rewrite problem above remains.**

## Proposed way forward

### Two offline paths, both showing the card as written

| | Standard (keyword search) | Enhanced (keyword search + embeddings) |
|---|---|---|
| How the card is found | Matches the question's words (BM25) | Matches both words and meaning, so differently phrased questions still find the card |
| Right card served, 95 questions | 71 | **78** |
| Says "I don't know" when it shouldn't | 21 | **12** |
| Wrong module served | 1 | 3 |
| Extra download | none | about **176 MB** once (the query encoder) |
| Extra storage per card | none | about 3 KB per card (0.6 MB for today's 199 cards) |
| Phones | every phone | phones with 3 GB of RAM or more, as today |
| What the health worker sees | the card, as written | the card, as written |

These are replay results on the 100-question QA bank. The enhanced path should still be
confirmed on a device.

**What this changes:**
- **The rewrite model is no longer part of the default offline experience.** Health workers
  get the approved card text, which was the most accurate answer in every test.
- **The enhanced path's download drops** from about 673 MB (the model plus the encoder) to
  about 176 MB.
- **Answers are immediate,** with no generation wait.

**What it costs:**
- **No "simple words" rewrite offline.** Answers are as long as the card.
  - This can be softened without a model, for example by showing the card's most relevant
    sentence first.
- **Search quality depends on the content.** Cards that hold trainer instructions, or that
  leave out what health workers ask about, can't be fixed by better search, and need content
  updates.
- **The enhanced path serves the wrong module slightly more often** (3 times against 1 in 95).
  The gate still needs to be watched as content grows.

### In the long run: a small model trained on our own modules

The general-purpose models we tested were never trained on our cards or on how health workers
ask questions. A model trained for exactly this task could do better at a phone-friendly size.
It would work like this:

1. **Build training examples** from the published modules: questions health workers ask, the
   card, and a correct short answer in Bangla. Generate them on a server and have them
   reviewed.
2. **Train a small model** (around 1B or smaller) on those examples, on a server. It learns the
   task of answering from a given card, not the cards themselves, so new cards don't need a
   retrain. "Continuously learning" here means **retraining when the kind of content changes**,
   checked on modules the model never saw, not learning on the phone.
3. **Test each new version before release** on a fixed question set, scored against showing
   the card. Ship it only when it beats the card, with no harmful answers.
4. **Keep the card underneath, or as the fallback,** so a missed point is never hidden from
   the health worker.

This needs reviewed training data in Bangla, training infrastructure, and a release process
for model updates. It is a project in its own right, not a quick change. Costs, tools and a
phased roadmap are in `docs/eval/2026-10-08-trained-answer-model-roadmap.md`.

## Decisions to consider

1. **Agree the two offline paths:** standard (keyword search) and enhanced (keyword search
   + embeddings), both showing the card as written.
2. **Remove or switch off the on-device rewrite model** in the default offline experience.
3. **Decide whether to explore the long-term trained model,** and who would review the Bangla
   training examples.
