# Bangla-capable small models: candidates for the next Ollama test

**Date:** 2026-09-28
**Status:** the five candidates were tested on 28 September; results in
`docs/eval/2026-09-28-assistant-answer-ollama-test.md`
**Question from the team:** how would Gemma do, since it is multilingual? Are there models built
for Bangla? Size is relaxed for now, to roughly 1–2 GB, with a stretch above that.

## Summary

Five models are worth testing, plus one larger model to show what size alone would buy:

| # | Model | Kind | Ollama download | On-device file | Licence | Why test it |
|---|---|---|---|---|---|---|
| 1 | **Gemma 4 E2B-it** | multilingual (Google) | 4.3 GB¹ | **2.58 GB, official LiteRT-LM** | Apache 2.0 | The team's question, and the only candidate with an official bundle for the engine the SDK already uses |
| 2 | **Qwen3.5 2B** | multilingual (Alibaba) | 1.9 GB¹ | 1.9 GB, community LiteRT-LM bundle only | Apache 2.0 | Same family as today's model, a newer generation (201 languages) |
| 3 | **TigerLLM-1B-it** | Bangla-specific | ~0.8 GB | would need our own conversion | CC-BY-4.0, plus Gemma's terms | Bangla instruction-tuned; claims strong Bangla scores |
| 4 | **BanglaLlama-3.2-3B instruct** (and the 1B) | Bangla-specific | ~2 GB (3B) | would need our own conversion | **GPL-3.0**, plus the Llama 3.2 licence | Bangla instruction-tuned on 172k Bangla instructions |
| 5 | **speaklar Gemma 3 1B-it** | Bangla fine-tune of Gemma 3 1B | needs a GGUF conversion | would need our own conversion | **not stated** | Trained for exactly our task: a Bangla question plus context in, a Bangla answer grounded in that context out |
| ref | **Gemma 4 E4B-it** | multilingual (Google) | 6.1 GB¹ | 3.66 GB, official LiteRT-LM | Apache 2.0 | Above budget. If E4B can't answer faithfully, nothing in range will |

¹ Ollama's Gemma 4 and Qwen3.5 downloads include vision (and, for Gemma, audio) encoders.
The text model is smaller. The on-device column is the number that matters for the SDK.

Three models carry over from the last test (`docs/eval/No_translation_ollama_test.md`):

- **`qwen3:1.7b`.** Its `en-strict` prompt was the best at answering in its own words rather
  than copying the card. On 77 questions it got 37 CORRECT, and 6 answers added claims.
- **`qwen3:0.6b`,** today's device model, as a control.
- **`gemma3:1b`,** as a control.

The strongest candidate on paper is **Gemma 4 E2B**:
- it is Google's current on-device model;
- it has an official LiteRT-LM bundle, used by the same engine as today's Qwen file;
- it is Apache 2.0;
- it was pre-trained on 140+ languages.

Its cost is about five times today's download (2.58 GB against 497 MB), and 1.73 GB of CPU
memory on a flagship phone.

## Multilingual models

### Gemma 4 (Google, released 2 April 2026)

| | E2B-it | E4B-it |
|---|---|---|
| Parameters | 2.3B effective, 5.1B with embeddings | 4.5B effective, 8B with embeddings |
| LiteRT-LM file (official, `litert-community`) | 2,583 MB, mixed 2-, 4- and 8-bit | 3.66 GB |
| CPU memory, Galaxy S26 Ultra | 1,733 MB | 3,283 MB |
| Decode speed, S26 Ultra CPU | 46.9 tokens/s | 17.7 tokens/s |
| MMMLU (multilingual MMLU) | 67.4% | 76.6% |
| Ollama tag | `gemma4:e2b-it-qat` (4.3 GB) | `gemma4:e4b-it-qat` (6.1 GB) |

- **Languages:** "over 140 languages" in pre-training, and "out-of-the-box support for 35+
  languages". The model card does not publish the list of 35, so **Bengali is not confirmed**
  among them.
- **Earlier evidence from our own tests:** Gemma 3 1B wrote fluent Bangla, but added claims
  not on the card and sometimes slipped into Russian, Hindi or Chinese. Gemma 4 has to be
  tested for both problems again.
- **Memory:** for text-only use, the bundle's weights can take as little as 0.8 GB of memory,
  with the 1.12 GB of embeddings memory-mapped.
- **Thinking mode:** Gemma 4 has one. Turn it off in the test (`think: false`), as we did for
  Qwen3.
- **Gemma 3n is superseded.** Gemma 3n was the 2025 on-device family, and Gemma 4 E2B and E4B
  replace it.

### Qwen3.5 small models (Alibaba, released 2 March 2026)

| | 0.8B | 2B | 4B |
|---|---|---|---|
| Ollama download | 1.0 GB | 1.9 GB (`q4_K_M`) | 3.4 GB |
| LiteRT-LM | none found | community bundle, ~1.9 GB, int8 | none found |

- **Languages:** 201 languages and dialects. Qwen3's list of 119 names Bengali explicitly.
- **Licence:** Apache 2.0.
- **Architecture risk:** Qwen3.5 uses a new hybrid architecture ("Gated DeltaNet" linear
  attention in about 75% of its layers). The only LiteRT-LM bundle is a community conversion
  that calls itself the first working one, with no device measurements. Shipping it would
  mean trusting or redoing that conversion.
- **The 0.8B** is worth including as a cheap row: it is the same size class as today's model,
  a generation newer.
- **Qwen3 4B** is the older-generation alternative with an official LiteRT-LM bundle:
  2,536 MiB `mixed_int4`, 1.6–4.9 GB peak memory on the phones Google measured, and
  `qwen3:4b` at 2.5 GB in Ollama. Test it only if Qwen3.5 does well but its bundle proves
  unusable.

## Bengali-specific models

Most published Bangla LLMs are **base models**: trained to continue text, not to follow
instructions. They cannot be prompted to "answer from this card". Only the instruction-tuned
ones below are usable without our own fine-tuning.

### Usable (instruction-tuned)

**TigerLLM-1B-it** (`md-nishat-008/TigerLLM-1B-it`, ACL 2025)
- Its model card says Llama 3.2 1B, but the published weights are **Gemma 3 1B**: the GGUF's
  architecture is `gemma3` and it uses Gemma's chat template. It was further trained on a
  10M-token Bangla textbook corpus (grades 6–12) and
  100k native Bangla instruction pairs.
- GGUF: `mradermacher/TigerLLM-1B-it-GGUF`. Q3_K_M is 722 MB and Q5_K_M 851 MB, so Q4_K_M is
  about 0.8 GB.
- Licence: CC-BY-4.0, with Gemma's terms inherited from the base model.
- The authors report Bangla MMLU 0.61 and BanglaQuAD 0.68, above GPT-3.5 (0.55 and 0.50).
  **Treat these as unverified.** A 1B model beating GPT-3.5 on Bangla knowledge is an
  unusual claim, and our test is the check.

**BanglaLlama-3.2 instruct, 1B and 3B** (`BanglaLLM/BanglaLLama-3.2-{1b,3b}-bangla-alpaca-orca-instruct-v0.0.1`)
- Llama 3.2, pre-trained further on Bangla CulturaX, then instruction-tuned on 172k Bangla
  instructions. The collection was updated in January 2026, and a 2026 paper describes it.
- The card publishes no benchmark numbers. It warns that the models "have not undergone
  detoxification".
- **Licence: GPL-3.0**, on top of the Llama 3.2 Community License. A copyleft licence on
  weights shipped inside the SDK needs legal review before it could ship. It is fine to test.
- A quantized version exists in the 1B model's tree. I haven't confirmed the exact GGUF repo.

**speaklar Gemma 3 1B-it** (`munzurul/speaklar_gemma-3-1b-it`)
- Gemma 3 1B, fine-tuned with QLoRA on about 15,000 Bengali voice-bot conversations.
  - The inputs are Bengali, English and Banglish questions, each with knowledge-base context.
  - The outputs are Bengali only.
  - It is trained to "answer from supplied evidence" and to say when the information is not
    there.
  - The prompt uses `প্রশ্ন:` (question) and `প্রসঙ্গ:` (context) fields.
- This is the closest match to our use of any model found.
- It is Safetensors only, so it needs a GGUF conversion before Ollama can run it (a llama.cpp
  script does this).
- **No licence is stated.** Gemma's terms apply as a derivative. It can be tested, but not
  shipped until the author states a licence.
- There are no evaluation numbers, and it is a single-author model.

### Not usable without fine-tuning (base models)

| Model | Size | Why not |
|---|---|---|
| TituLLM (Hishab), Llama 3.2 1B and 3B | 1B, 3B | Base only. The authors say they did not instruction-tune it. The 3B scores lower than plain Llama 3.2 3B on Bangla MMLU (0.25 against 0.34, 5-shot). |
| TituLM Gemma 2 2B (Hishab) | 2B | Continued pre-training on 4.4B Bangla tokens; no instruct version found |
| bangla-smollm-135m (2026) | 135M | Base only, and too small to follow instructions |
| Paramanu-Bangla | 108M | Base only |

### Excluded by licence or size

| Model | Why excluded |
|---|---|
| **Tiny Aya** (Cohere, February 2026, 3.35B, 70 languages including Bengali) | **CC-BY-NC**: non-commercial, the same rule that ruled out NLLB. Commercial use means a deal with Cohere. |
| **Sarvam-1** (2B, 10 Indic languages including Bengali) | Sarvam non-commercial licence, and a base model ("cannot be used directly as a chat or an instruction-following model") |
| TigerLLM-9B, Nascenia Gemma 3 9B Bengali | 9B: far above any phone budget |

## Test plan for next session

Updated on 28 September after your review.

### What a good answer is

The model acts as an **assistant to the health worker**:
- It uses **only what the card says**.
- It answers **in natural Bangla**.
- It answers **the way the question was asked**:
  - "why" gets the reason;
  - "what is" gets an explanation;
  - "what should I do" gets the card's instructions;
  - "is this right?" gets yes or no, and why;
  - "how long" or "how many" gets the number.
- Signs or steps can come as a list when that is the natural answer. No prompt asks for a
  list, and no list-only prompt (`en-list`, `bn-list`) is tested.

**The model always gets the right card.** This test is about the answer, not retrieval.

### How each answer is judged

Every answer gets three checks. They are judged on **meaning, not wording**: rephrasing is
fine, and copying the card earns nothing.

| Check | Passes when | Fails when |
|---|---|---|
| **Meaning** | Everything it says agrees with the card. A detail left out is fine if it doesn't change what the CHW would do. | It contradicts the card; adds a fact, advice or number that isn't on the card; or drops something the question needs, such as a number, a condition, a "do not" or a referral. |
| **Fits the question** | It answers what was asked, in the form that was asked. | It answers something else, repeats the question, or restates the card without answering. |
| **Reads like an assistant** | Natural, readable Bangla addressed to a health worker | Garbled; loops; slips into another language or script; leaks prompt text; or opens with "here is the list". |

**An answer passes when it passes all three.** That is the test of "the answer makes sense".

Any answer that contradicts the card, or adds an instruction that would change what the CHW
does, is also flagged **harmful**. Earlier examples are "take iron and calcium together" and
"the hair may be cut in the first month".

**The card itself is judged too, as a reference.** For every question, the card shown as
written gets the same three checks. It always passes "Meaning", but often fails "Fits the
question". For example, a ten-sentence card doesn't answer "how many days?". A model is only
worth having if its answers fit the question better than the card does, without failing on
meaning.

**Bar for a device test** (a proposal, to be fixed before the run):
- at least **3 in 4 answers pass** across stages 2 and 3; and
- **no harmful answer**. This is the same zero bar the last device run failed on.

### Prompts

All the prompts give the card and the question in Bangla and ask for the answer in Bangla.

| Round | Prompt | Instructions in | What it says |
|---|---|---|---|
| 1 | `bn-min` | Bangla | Answer from the card only (unchanged from the last test, for comparison) |
| 1 | `bn-strict` | Bangla | The five numbered rules (unchanged from the last test) |
| 1 | `bn-assistant` | Bangla | New. The assistant role, answer the way the question is asked, may rephrase but not change the meaning (text below) |
| 1 | `bn-assistant-sys` | Bangla | The same as `bn-assistant`, sent as a system prompt |
| 2 | `en-min`, `en-strict`, `en-assistant`, `en-assistant-sys` | English | The same four with English instructions, for models that loop, echo the question or change language in round 1. The Qwen models did all three last time. |

**`en-assistant`** (as run; see the note below):
```
You are an assistant who helps community health workers in Bangladesh. Answer the health worker's question using only the information in the card.
- Answer only what the question asks, in the form that suits it. For example: the reason for a "why" question, the card's instructions for a "what should I do" question, yes or no with the reason for a yes-or-no question, the number for a "how long" or "how many" question.
- Speak naturally in simple Bangla, as a helpful colleague would. You may use your own words, but do not change the meaning.
- Keep every number, condition, "do not" and referral that the answer needs.
- Do not add anything that is not in the card. Do not repeat these instructions or the question, and do not write headings: just give the answer.
- If the card does not answer the question, say so.

Card:
<<<
{card}
>>>

Question: {q}
```

**`bn-assistant`** (the same instructions in Bangla, as run):
```
আপনি একজন সহকারী, যিনি বাংলাদেশের কমিউনিটি স্বাস্থ্যকর্মীদের সাহায্য করেন। শুধু কার্ডের তথ্য ব্যবহার করে স্বাস্থ্যকর্মীর প্রশ্নের উত্তর দিন।
- প্রশ্নে যা জানতে চাওয়া হয়েছে শুধু তারই উত্তর দিন, প্রশ্নের ধরন অনুযায়ী। যেমন: "কেন" প্রশ্নে কারণ, "কী করব" প্রশ্নে কার্ডের নির্দেশনা, হ্যাঁ-না প্রশ্নে হ্যাঁ বা না এবং কারণ, "কতদিন" বা "কতবার" প্রশ্নে সংখ্যা।
- একজন সহকর্মীর মতো সহজ বাংলায় স্বাভাবিকভাবে কথা বলুন। নিজের ভাষায় বলতে পারেন, কিন্তু অর্থ বদলাবেন না।
- উত্তরের জন্য দরকারি প্রতিটি সংখ্যা, শর্ত, "করা যাবে না" এবং রেফার করার কথা রাখুন।
- কার্ডে নেই এমন কিছু যোগ করবেন না। এই নির্দেশনা বা প্রশ্ন আবার লিখবেন না, কোনো শিরোনামও দেবেন না: সরাসরি উত্তর দিন।
- কার্ডে প্রশ্নের উত্তর না থাকলে সেটি বলুন।

কার্ড:
<<<
{card}
>>>

প্রশ্ন: {q}
```

**The wording changed once, during the run.** The first version said "answer the way the
question is asked: if it asks why, give the reason; if it asks what something is, explain it…".
Gemma 4 read that as a form to fill, and wrote a heading for every question type. The second
rule was rewritten to "answer only what the question asks, in the form that suits it", and the
fifth gained "do not repeat these instructions or the question, and do not write headings".
Every answer judged used the revised wording. It was not tuned further, to avoid fitting the
prompt to the 12 stage-1 questions.

The last line of the instructions ("if the card does not answer the question, say so") is
there for cards that hold no facts, such as q001's "discuss the importance of check-ups".
Last time, every model that wrote freely invented facts for those.

### Questions, in three stages

| Stage | Questions | Card given | Settings run |
|---|---|---|---|
| 1. Screen | The same 12 UC2 questions as last time, for comparison | the expected card | every model × the round-1 prompts, plus round 2 where needed |
| 2. Full | All 95 answerable UC2 questions, including the 18 where retrieval missed on the device | the expected card from the bank (the first listed, for the 8 with two) | the best 3–4 settings from stage 1 |
| 3. Held-out | The 25 answerable questions in `docs/eval/2026-09-06-chw-questions-bn.md`, written the way a CHW asks and never used in these tests | the first expected card listed | the same settings as stage 2 |

- **Stage 3 has extra help for the judge.** Each question there comes with a note of what a
  right answer covers.
- **The UC3 bank is not a held-out set.** 71 of its 100 questions are identical to UC2's, and
  it uses the same 103 cards.

### Running and judging

- **Settings:** temperature 0.2, thinking off, a context of 4,096 tokens, and each model's
  own chat template. These match the last test.
- **One answer per setting and question.** Ollama's output varies between runs, so the stage
  1 questions are rerun once for the stage 2 settings, to see how stable they are.
- **Judging:** Claude judges every answer, and you spot-check 20.
  - Each verdict records the three checks, the harmful flag, and a one-line reason.
  - Every verdict is saved to a file as it is made (unlike last time), in
    `chat-eval/runs/ollama-assistant-<date>/`.
- **How much judging:** about 430 answers in stage 1 (9 models × 4 prompts × 12 questions,
  more with round 2), about 380 in stage 2, and about 100 in stage 3.
- **Timing:** timings on the Mac only compare models with each other. The device test gives
  the real latency.

**Downloads and set-up:**

```bash
ollama pull gemma4:e2b-it-qat           # 4.3 GB
ollama pull gemma4:e4b-it-qat           # 6.1 GB (reference)
ollama pull qwen3.5:2b-q4_K_M           # 1.9 GB
ollama pull qwen3.5:0.8b                # 1.0 GB
ollama pull hf.co/mradermacher/TigerLLM-1B-it-GGUF:Q4_K_M
ollama pull hf.co/mradermacher/BanglaLLama-3.2-3b-bangla-alpaca-orca-instruct-v0.0.1-GGUF:Q4_K_M
# speaklar Gemma 3 1B-it: Ollama on the Mac cannot import Gemma 3 safetensors, so convert with
# llama.cpp convert_hf_to_gguf.py --outtype q8_0 (after removing the <image_soft_token> added
# token, which sits past the vocabulary), then `ollama create` with gemma3:1b's template
# already installed from the last test: qwen3:0.6b, qwen3:1.7b, gemma3:1b
```

As run, speaklar is at **q8_0** and every other model at Q4 (Q4_K_M, or QAT for Gemma 4), so
speaklar had the advantage of the lighter quantization. BanglaLlama gets a raw Alpaca prompt
(`### Instruction:` / `### Response:`), because that is the format it was tuned on.

That's about 15 GB of downloads. Turn thinking off for Gemma 4 and Qwen3.5. Use each model's
own chat template, which Ollama applies.

## Decisions for you

1. **Which candidates to test.** I recommend:
   - all five candidates, plus the E4B reference;
   - `qwen3:1.7b`, carried over;
   - the two controls.
2. **Whether GPL-3.0 (BanglaLlama) and an unstated licence (speaklar) are acceptable to test.**
   Neither is acceptable to ship without review.
3. **The bar for a device test:** 3 in 4 answers passing, and no harmful answer.
4. **Whether the `bn-assistant` wording reads naturally** to a Bangla speaker.

## Sources

- Gemma 4: [model card](https://ai.google.dev/gemma/docs/core/model_card_4),
  [google/gemma-4-E2B-it](https://huggingface.co/google/gemma-4-E2B-it),
  [LiteRT-LM E2B bundle](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm),
  [LiteRT-LM E4B bundle](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm),
  [Ollama tags](https://ollama.com/library/gemma4/tags),
  [Google blog](https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/)
- Qwen3.5 and Qwen3: [release overview](https://www.therundown.ai/tools/qwen3-5-small),
  [Ollama qwen3.5 tags](https://ollama.com/library/qwen3.5/tags),
  [community LiteRT-LM 2B bundle](https://huggingface.co/paulsp94/Qwen3.5-2B-LiteRT-LM),
  [Qwen3 blog (language list)](https://qwenlm.github.io/blog/qwen3/),
  [Ollama qwen3 tags](https://ollama.com/library/qwen3/tags),
  [LiteRT-LM Qwen3-4B](https://huggingface.co/litert-community/Qwen3-4B),
  [litert-community](https://huggingface.co/litert-community)
- TigerLLM: [TigerLLM-1B-it](https://huggingface.co/md-nishat-008/TigerLLM-1B-it),
  [paper](https://arxiv.org/abs/2503.10995),
  [GGUF](https://huggingface.co/mradermacher/TigerLLM-1B-it-GGUF/tree/main)
- BanglaLlama: [1B instruct](https://huggingface.co/BanglaLLM/BanglaLLama-3.2-1b-bangla-alpaca-orca-instruct-v0.0.1),
  [3B instruct](https://huggingface.co/BanglaLLM/BanglaLLama-3.2-3b-bangla-alpaca-orca-instruct-v0.0.1),
  [collection](https://huggingface.co/collections/BanglaLLM/banglallama),
  [2026 paper](https://aclanthology.org/2026.loreslm-1.7.pdf)
- speaklar: [munzurul/speaklar_gemma-3-1b-it](https://huggingface.co/munzurul/speaklar_gemma-3-1b-it)
- TituLLM: [paper](https://arxiv.org/html/2502.11187v1),
  [titulm-llama-3.2-3b-v2.0](https://huggingface.co/hishab/titulm-llama-3.2-3b-v2.0),
  [titulm-gemma-2-2b-v1.1](https://huggingface.co/hishab/titulm-gemma-2-2b-v1.1)
- bangla-smollm-135m: [paper](https://arxiv.org/html/2606.16383)
- Tiny Aya: [tiny-aya-global](https://huggingface.co/CohereLabs/tiny-aya-global),
  [Cohere blog](https://cohere.com/blog/cohere-labs-tiny-aya),
  [TechCrunch](https://techcrunch.com/2026/02/17/cohere-launches-a-family-of-open-multilingual-models/)
- Sarvam-1: [sarvamai/sarvam-1](https://huggingface.co/sarvamai/sarvam-1)
