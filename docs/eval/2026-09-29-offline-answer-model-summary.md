# Offline "simple words" answers: what we tested and where it stands

**Date:** 29 September 2026
**Covers:** the tests of 24–28 September. Details are in the linked docs.

## The question

In "simple words" mode the phone turns the right card into a short, natural Bangla answer to
the health worker's question, using only what the card says. **Can a model small enough for
the phone do that, safely?**

## Where it stands

- **The Bangla-focused models did not do better.** TigerLLM, speaklar and BanglaLlama failed
  on the first 12 questions, against every prompt. They are small (1–3B) and were not trained
  to answer from a given text.
- **Of the models tried, only Gemma 4 E2B is worth taking further.**
  - Its answers read naturally and fit the question better than the card does.
  - About a third leave out something the health worker needs, and a few were harmful.
- **With the SDK's answer check (which shows the card when an answer fails it), Gemma 4 E2B
  gets 95 of 120 right, with no harmful answer shown.**
- **That is still a small regression against serving the card alone (105 of 120).** Most of
  Gemma's answers that pass the check are nearly the whole card. So today the model buys
  shorter answers some of the time, not more correct ones.
- **It costs:**
  - a 2.58 GB download, about 5 times today's 497 MB;
  - about 1.7 GB of memory;
  - an answer time on a mid-range phone that is not yet measured.
- **Proposed next step:** try a few new kinds of prompt on Gemma 4 E2B, aimed at complete
  answers, before any device work. See [Next](#next).

## How we got here

1. **Today's device path loses most answers (24 September).** The phone translates the
   question and card to English with ML Kit, Qwen3 0.6B answers in English, and ML Kit
   translates back. When the right card was served, only **26 of 77** answers were correct.
   Translation damaged the question, the card and the answer.
2. **First Ollama test: no translation (25 September).**
   - We gave each model the Bangla card and question directly: `qwen3:0.6b`, `qwen3:1.7b` and
     `gemma3:1b`, with seven prompts each.
   - The winner was the model already on the phone, `qwen3:0.6b`, with English instructions
     and a prompt to *list the card's sentences that answer the question*. It got **47 of
     77** correct with nothing invented, and **58** when a simple check fell back to the card.
3. **The same setting on the Android emulator failed (27 September).**
   - It reached **60 of 77** correct, exactly the bar set beforehand.
   - But it failed the other half of the bar: one answer shown to a health worker inverted an
     instruction. The card says a newborn's hair must not be cut for a month; the answer said
     it may be.
   - **56% of answers fell back to the card,** so most health workers would have seen no
     shorter answer.
   - **The median answer time rose from 3.3 s to 10.6 s.**
   - **The Ollama scoring had been misleading.** It rewarded answers for keeping the card's
     facts, so the prompt that copied the card most closely won.
4. **We changed what "good" means.** A good answer:
   - agrees with the card;
   - answers the way the question was asked (a reason for "why", the number for "how long");
   - reads like a colleague talking, not like a list.

   The card itself is scored the same way, as the baseline to beat.
5. **Second Ollama test: bigger and Bangla-focused models (28 September).**
   - Six models, eight prompts, on the 12 screening questions:
     - Gemma 4 E2B and Gemma 3 4B;
     - Qwen3.5 2B;
     - three Bangla-focused models: TigerLLM 1B, speaklar Gemma 3 1B and BanglaLlama 3B.
   - The Gemma models then ran on 120 questions, 25 of them held out and never used before.
   - Every model was always given the right card.

## How each model failed

The scores in the two Ollama tests use different scoring and question sets, so compare
models within a row, not across rows.

| Model | Size (the phone file where one exists, otherwise the Ollama download) | With Bangla instructions | With English instructions | Verdict |
|---|---|---|---|---|
| **Qwen3 0.6B** (today) | 497 MB | Loops and repeats the question. Two answers ran to about 40,000 characters | Stays on the card. "List the sentences" was the best prompt (47 of 77), but on the emulator it inverted an instruction and took 10.6 s | Mostly copies the card; can't write a natural answer |
| Qwen3 1.7B | ~1.4 GB | Loops, as 0.6B does | Rephrases more, but drops more (37 of 77) | No better than 0.6B at 3× the size |
| Gemma 3 1B | ~0.8 GB | Fluent Bangla, but invents schedules and advice ("take iron and calcium together", the opposite of the card). Its list prompt was the cleanest (45 of 77) | Opens answers in Russian; once wrote "one year" in Arabic for "six months" | Invents too much |
| Qwen3.5 2B | ~1.9 GB, community build only | The most harmful answers: 7 of 12 in one prompt, for example "no need to go to hospital" for TB drug side effects | Its best: 5 of 12 | Not safe |
| TigerLLM 1B (Bangla) | own conversion needed | 0 of 12: loops on numbered lists | 0 of 12: answers in English, once in Chinese | Unusable |
| speaklar Gemma 3 1B (Bangla) | own conversion needed | Broken grammar ("she should drink cough and blood"); invents visit counts | Same | Unusable |
| BanglaLlama 3B (Bangla) | own conversion needed | Repeats the question, or talks about the task instead of answering; slowest | Same | Unusable |
| Gemma 3 4B | no Android build (`-web.task` only, 2.56 GB) | Strict prompt fine (10 of 12). Freer prompts invent advice and switch script mid-answer (Telugu, Tamil, Gujarati, Chinese) | Rephrases the most of any model, but on 120 questions only 60 right with 5 harmful ("she can throw the colostrum away") | Worse than Gemma 4 E2B |
| **Gemma 4 E2B** | 2.58 GB, official LiteRT-LM | Strict prompt: mostly the card, cut down (96 of 120, none harmful). Assistant prompt: natural but leaves points out (73 of 120) | Assistant prompt: 79 of 120, 2 harmful. **With the answer check: 95 of 120, none harmful shown** | The only candidate |

Showing the card as written gets **105 of 120** on the same scoring.

## Gemma 4 E2B in more detail

| What the health worker sees | Right, of 120 | Harmful shown | The model's own answer shown |
|---|---|---|---|
| The card as written | 105 | 0 | – |
| Gemma, assistant prompt, English instructions | 79 | 2 | 120 |
| **The same, plus the answer check (card shown when the check fails)** | **95** | **0** | 82 |
| Gemma, strict prompt, plus the check | 104 | 0 | 94, nearly all the card |

- **What works.** When the answer is complete, it reads like a colleague: "within one hour",
  "yes, for serious side effects go straight to hospital". It says "the card doesn't say"
  when that is true.
- **What fails.** It picks one or two of the card's sentences when the question needs three.
  For example, it gives the vaccination schedule but leaves out "BCG as soon as possible
  after birth". It also gave two harmful answers:
  - "2.1 kg is above the 2.5 kg line", about a baby who is low birth weight;
  - "ten zinc tablets every day", where the card says one a day, ten in total.
- **Why the check only helps safety.** It demands every number, negation and referral on the
  card. A short answer survives only when the card is short too, so what gets through is
  nearly the card. With the check, only 11 of 120 answers are both right and noticeably
  shorter than the card.

## Next

**Try a few new kinds of prompt on Gemma 4 E2B,** aimed at complete answers that still read
naturally. Each can be run and scored on the same 120 questions on a Mac, before any device
work:

1. **Pick, then write.** The model first lists the numbers of the card sentences that answer
   the question, then writes the answer from all of them.
2. **Worked examples.** Two or three Bangla examples in the prompt, each a question, a card and
   a complete answer in natural words.
3. **A prompt per question type.** The code detects the type ("why", "what should I do", "how
   many", yes or no) and sends a short prompt made for that type.
4. **A second pass.** The model compares its answer with the card and adds anything missing.

**Alongside that:**
- **A different screen:** show Gemma's short answer *above* the card. Then a point it leaves
  out is still on the screen, and the check only needs to stop harmful answers.
- **A looser check** that tests what the question needs, not everything on the card.
- **Qwen3 0.6B on the new scoring.** The model the phone runs today was never scored against
  the 120 questions, so there is no direct comparison yet.

If a prompt closes the gap to the card, the next step is a device run to measure speed and
memory.

## Caveats

- **One judge.** Claude judged every answer and is not a native Bangla reader. A Bangla speaker
  is spot-checking 20 answers.
- **One sample per setting,** at temperature 0.2.
- **Mac timings, with Ollama on the GPU, say nothing about phone speed.**

## Detailed docs

| Doc | What it covers |
|---|---|
| `docs/eval/No_translation_ollama_test.md` | First Ollama test: Qwen3 0.6B and 1.7B, Gemma 3 1B, seven prompts |
| `docs/eval/2026-09-27-card-sentence-device-run.md` | The Qwen3 0.6B list prompt on the Android emulator, and why it failed |
| `docs/eval/2026-09-28-bangla-model-candidates.md` | Why these models were picked, the scoring rules and the exact prompts |
| `docs/eval/2026-09-28-assistant-answer-ollama-test.md` | Second Ollama test: six models, 120 questions, the answer check and what a model adds over the card |
