# A small answer model trained on our modules: cost, feasibility and roadmap

**Date:** 8 October 2026
**For:** the team and the client, as the long-run follow-up to the two offline paths
**Builds on:** `docs/eval/2026-10-01-offline-chat-way-forward.md`

All costs and durations here are estimates. Nothing in this doc has been trained or measured yet.

## Summary

- **The idea:** train a small model for one job: given a health worker's question and the right
  card, write a short Bangla answer that says only what the card says.
- **The computing is cheap:** tens to low hundreds of dollars per training run.
- **The real cost is people:**
  - a Bangla-speaking clinical reviewer, for about 50–150 hours of checking training answers;
  - one ML engineer, for about 2–4 months.
- **A first test can run on our current development Mac** in 1–2 weeks. It tells us whether the
  idea works before we spend on the rest.
- **Recommended base model: Gemma 3 1B.** It is already fluent in Bangla, it has an official
  phone build, and its main fault in our tests (inventing advice) is what training targets.
- **The card stays the source of truth.** Retrieval, the answer check and the fallback to the
  card all stay as they are. The trained model would be an opt-in on top of the enhanced path.

## What the model learns

The model should learn **the task, not the modules.** It should not memorise card content.
It should learn to answer from whatever card it is given:

- keep every number, "not" and referral that the question needs;
- answer the way the question was asked: a reason for "why", a number for "how long";
- say "the card doesn't say" when that is true;
- add nothing that isn't on the card.

That targets the three failures every model showed in our tests:
1. leaving out what the health worker needs;
2. changing the meaning;
3. adding things that aren't on the card.

**This changes what "continuously learning" means.** A model that learned the task should
handle new cards without retraining. We would retrain only when the kind of content or questions
changes, and confirm with a test on modules the model never saw. Retraining for every content
release is not needed by default.

## Is our data enough?

As a starting point, yes. Most of the training data still has to be made.

| What we have | What it is good for |
|---|---|
| 199 published cards | The source material. About 15–30 questions per card gives roughly 3,000–6,000 training examples |
| The 100-question QA bank, the 120-question test set, and 1,332 judged model answers | A ready test set, with the card's 105 of 120 as the score to beat. The judged good and bad answers can also become training examples |
| The chat-eval harness and the answer-check simulation | Scoring each trained version the same way we scored the models we tested |

**What is missing:**
- **Questions phrased the way health workers ask them,** each with a correct short answer.
  A large cloud model would draft these from the cards.
- **A Bangla clinical reviewer** to check every drafted answer. This is the largest cost:
  3,000–5,000 examples at 1–2 minutes each is about 50–150 hours.
- **The client's approval** to send module content to a cloud model to draft the examples.

## Can it be done on our current development machine?

The machine is an Apple M4 Pro with 24 GB of memory.

| Step | On this machine? |
|---|---|
| First test: LoRA training of a 1B model on 1,000–2,000 examples | **Yes**, with MLX-LM. Roughly an hour per run |
| LoRA training of Gemma 4 E2B | Probably, but slow and close to the memory limit |
| Full training, or many repeated runs | **No.** A rented cloud GPU (A100 or H100, about $2–4 per hour) is better |
| Converting to a phone build (`.litertlm`) and checking quality after compression to 4-bit | Possible, but the tools are mainly tested on Linux. A cloud machine is safer |
| Disk space | Each model is 2–10 GB. Clearing the test models we no longer need comes first |

## Base model candidates

| Model | Phone size (4-bit) | Notes |
|---|---|---|
| **Gemma 3 1B** (recommended) | about 0.5–0.8 GB, official LiteRT build | Fluent Bangla. In our tests it invented advice, which training targets directly |
| Qwen3 0.6B | 497 MB, today's model | No size increase, but weak Bangla. A useful lower bound |
| Gemma 3 270M | about 0.3 GB | Built for task-specific training, but likely too weak in Bangla. Worth one cheap test |
| Gemma 4 E2B | 2.58 GB | The best quality in our tests, but too large for the product. Use it as the best-case comparison, not the shipped model |

## What it would cost

| Item | Estimate |
|---|---|
| Drafting the training examples with a cloud model | Tens of dollars |
| Cloud GPU for training, converting and testing | Tens to low hundreds of dollars per run |
| Bangla clinical review | About 50–150 hours for the first dataset, then a 20–50 answer spot-check per release |
| ML engineering | About 2–4 months of one engineer for the first shipped version |
| Android integration | Small: the SDK already runs LiteRT models, the answer check and the card fallback |
| Phone download for users who opt in | About 0.5–0.8 GB, close to today's 497 MB model |

## Skills needed

- **ML engineering:** building the dataset, training, and evaluation. The evaluation side
  already exists in the chat-eval harness.
- **On-device engineering:** 4-bit compression, LiteRT conversion, and measuring speed and
  memory on a mid-range phone. The Android team can cover this.
- **Bangla clinical review:** checking the training examples, and spot-checking each release.
- **Release process:** versioning the model, and shipping it only when it passes a fixed test.

## Tools and libraries

| Purpose | Tools |
|---|---|
| Training | Hugging Face `transformers`, `datasets`, TRL (`SFTTrainer`) and PEFT (LoRA). MLX-LM on the Mac. Unsloth on a cloud GPU (it needs CUDA) |
| Drafting data | A large cloud model, and a simple review sheet for the reviewer |
| Phone build | `ai-edge-torch` to LiteRT-LM (`.litertlm`), run by the SDK's existing LiteRT runtime |
| Quick checks | Export to GGUF, run in Ollama, and score with our harness |

## Roadmap

### Phase 1: first test (1–2 weeks, on the development Mac)

- Draft about 1,500 examples from **half** of the modules, and spot-review a sample.
- LoRA-train Gemma 3 1B on them.
- Score it on the 120 questions, including cards from the modules it never saw.
- **Go on only if** it comes close to the card's 105 of 120 with no harmful answers, and does as
  well on the unseen modules as on the seen ones.

### Phase 2: the real dataset (3–6 weeks, mostly reviewer time)

- Draft 3,000–6,000 examples across all modules, including questions the card can't answer,
  where the right reply is "the card doesn't say".
- Have every example reviewed, and keep a held-out set that is never used in training.

### Phase 3: train, compress and test on a phone (2–3 weeks, on a cloud GPU)

- Train on the reviewed dataset, then compress to 4-bit and convert to `.litertlm`.
- Score it again after compression, because compression can lose quality.
- Measure answer time and memory on a mid-range phone.

### Phase 4: integrate and release (2–4 weeks)

- Plug it into the enhanced path as an opt-in, behind the existing answer check, with the card
  as the fallback.
- Add a release gate: each new version must beat the card on the fixed test set with no harmful
  answers before it ships.
- Spot-check each release with the Bangla reviewer.

**Total:** about 2–4 months of one engineer, plus the reviewer's hours. Phase 1 is the decision
point: it is cheap, and if it fails we stop having lost about two weeks.

## Risks

- **It may still make mistakes on medical content.** Training reduces errors but does not remove
  them. The answer check and the card fallback stay in place for that reason.
- **The answer check can't catch everything.** It misses an answer that keeps the right numbers
  but attaches them to the wrong advice.
- **Quality can drop after compression to 4-bit.** Phase 3 measures this before anything ships.
- **The test set is small** (120 questions, judged by one reviewer). Phase 2 adds a larger
  held-out set and a native Bangla reviewer.
- **Content changes the model has not seen,** such as a new kind of module, need a retrain.
  The release gate catches this.

## Decisions to consider

1. **Approve Phase 1** as a two-week test, with a clear go or stop at the end.
2. **Name a Bangla clinical reviewer** for the spot-review in Phase 1 and the full review in
   Phase 2.
3. **Approve sending module content to a cloud model** to draft the training examples, or name an
   approved alternative.
