# Smarter search on a 2 GB phone (emulator)

**Date:** 9 October 2026
**Decides:** the default for `MicroCoachingConfig.smarterSearchMinRamMb`
**Plan:** Task 7 of the two-offline-paths plan; spec `docs/superpowers/specs/2026-10-08-two-offline-paths-design.md`

## Summary

- **On a 2 GB phone, the encoder adds about 190 MB to SPICE's memory.** The budget fixed before
  the run was 150 MB, so criterion 1 fails.
- **Nothing else failed.**
  - The 100-question run had no crashes, and SPICE kept the same process throughout.
  - SPICE survived every app-switch round, with smarter search on and with it off.
- **Retrieval quality on the device matches the replay.** Smarter search served the right card
  for **77 of 95** questions; the replay predicts 78.
- **Decision:** the default floor goes from 1536 MiB to **3072 MiB**, the language model's tier.
  Smarter search therefore reaches 3 GB phones and up by default. A host that accepts the cost can
  still set `.smarterSearchMinRamMb(1536)` to admit 2 GB phones.
- **Speed can't be judged here.** The emulator runs on the development Mac's CPU, so every
  timing below is a best case. A real 2 GB phone should be checked when one is available.

## Setup

| | |
|---|---|
| Device | `Pixel_4a` AVD: arm64-v8a, API 30, `hw.ramSize=2048`, 4 cores, `vm.heapSize=256` |
| `MemTotal` | 2,030,708 kB (1,983 MiB), above the 1536 MiB rule the build was tested with |
| Build | host `Spice-SL` dev debug, against SDK branch `feat/test-harness-and-model-rewrite` at `4ee2833` plus the uncommitted two-offline-paths work |
| Bank | `qa-uc2-2026-09-17`: 100 Bangla questions, 95 answerable, 5 off-topic |
| Module export | sha256 `724b0127…981c` (27 modules, 199 card vectors) |
| Runs | `chat-eval/runs/smarter-search-2gb-bn` (scored run); `smarter-search-2gb` (first run, app in English, not scored); `smarter-search-2gb-bn-paired` and `smarter-search-2gb-bn-control` (paired memory) |

The first full run was made with the host app in English after a clean install. In English the
SDK translates the question from English to Bangla before searching. With a Bangla question that
mangles the text ("পারিবারিক খাবার" became "Paricaric খেলা"), so that run scored 67 of 95 and is not
used. Every earlier device run used Bangla, and the scored run here does too. `chat-eval setup`
does not set the app's language; that is a gap in the harness.

## Pass criteria (fixed before the run)

1. The encoder adds at most 150 MB to SPICE's TOTAL PSS.
2. The 100-question run has no `crash` or `dense_off` warnings, and SPICE's process id is the
   same at the end as at the start.
3. Across three rounds of the app-switch test, SPICE survives at least as often with smarter
   search as with standard search.

If all three pass, the default stays at 1536 MiB. If any fails, it becomes 3072 MiB.

## Memory

TOTAL PSS from `dumpsys meminfo`, in kB. Native Heap and Other mmap (the mapped encoder file)
are the two rows that move.

**Paired measurement (the figure the decision uses).** Each row is a fresh process with chat
open, then one question (`uc2_q001`), in Bangla. The two rows differ only in the search path.

| Path | Before | After one question | Added | Native Heap added | Other mmap added |
|---|---|---|---|---|---|
| Standard search (control) | 235,177 | 304,478 | 69,301 | 46,752 | 4,900 |
| Smarter search | 253,149 | 520,363 | 267,214 | 133,194 | 81,610 |
| **Encoder's share** | | | **≈ 197,900 (193 MiB)** | ≈ 86,400 | ≈ 76,700 |

The control row matters. A first question costs SPICE about 68 MiB with no encoder at all (the
ML Kit translator, the chat's own state). So "after minus before" on the smarter-search side
alone overstates the encoder.

**Other readings, which agree.**

| Reading | Value | What it shows |
|---|---|---|
| Release: smarter search on after 100 questions, then switched to standard search (English session) | PSS fell by 156,090 kB; Other mmap from 56,678 to 9,935 | Turning smarter search off frees the encoder, as designed (Step 4) |
| Growth over 100 questions (Bangla) | M1 516,217 → M2 449,624 | No growth per question |
| Growth over 100 questions (English) | M1 574,784 → M2 536,773 | Same |

**Why it is more than the published ~110 MB.**
- The native heap grows by about 86 MB when the interpreter loads. That is most likely LiteRT's
  CPU delegate (XNNPACK) repacking the weights into its own buffers.
- The mapped file adds about 77 MB of clean pages on top.
- In the long Bangla run, Other mmap fell from 82 MB to 27 MB. So under pressure, Android takes
  those clean pages back, at a cost in speed (below).

If 2 GB phones matter enough, the next things to try are LiteRT's XNNPACK weight cache or
running without XNNPACK. Either should shrink the native-heap half. Neither was tried here.

## The 100-question run (Bangla)

| | Device | Replay (`docs/references/serve-gate.md` §9) |
|---|---|---|
| Right card | **77 / 95** | 78 / 95 |
| Right module | 79 / 95 | 80 / 95 |
| Wrong module | 3 | 3 |
| False refusals | 13 | 12 |
| Off-topic refused | 5 / 5 | 5 / 5 |

- **Warnings:** none. Dense retrieval ran on all 97 questions that reached retrieval; the other
  3 were refused by the deny-list before retrieval runs.
  - The English run warned `dense_off` on those 3. That was a harness false positive, now fixed
    in `capture.build_turn`.
- **Process:** the same pid (12583) from the first question to the last. No crashes.
- **Encoding time (`dense_ms`, best case):**
  - Bangla run: median 1,498 ms, p90 1,695 ms, max 1,877 ms.
  - English run: median 235 ms, p90 1,282 ms.

  The Bangla run kept the ML Kit translator resident as well. With the encoder's pages being
  reclaimed between questions, each encoding paid to read some of them back.
- **Encoder load (`loadMs`, best case):**
  - 379 ms straight after the download, with the file still in the page cache.
  - 3,620–4,336 ms after an app restart.
  - 657 ms in the paired run.
- **Whole turn (send to answer, best case):** median 14.4 s, p90 14.9 s in Bangla.

## App switching

Each round: camera, then a browser page, then system settings, then back to SPICE. Three rounds
per path, with SPICE on the chat screen and, for smarter search, the encoder loaded by one
question first.

| Path | Survived | Kill events for SPICE | PSS after each round (kB) |
|---|---|---|---|
| Standard search | 3 / 3 | 0 | 267,018 / 258,405 / 261,741 |
| Smarter search | 3 / 3 | 0 | 384,074 / 379,736 / 381,984 |

Android trimmed SPICE while it was in the background in both paths. It did not kill it. The
camera and browser were already running from the first round in rounds 2 and 3, so those rounds
put less pressure on memory than round 1.

## Decision

| Criterion | Result |
|---|---|
| 1. Encoder adds at most 150 MB | **Fail**: about 193 MiB (paired); 156 MB freed on release |
| 2. No crash, no `dense_off`, same pid | Pass |
| 3. Survives app switching at least as often as standard search | Pass: 3 of 3 in both |

Criterion 1 fails, so `smarterSearchMinRamMb` now defaults to **3072** in `MicroCoachingConfig` and
in the Builder.

What this does and doesn't say:
- **It does not say a 2 GB phone can't run smarter search.** On the emulator it ran 100 questions
  without trouble, and Android did not kill it.
- **It says the encoder costs more than the budget the team set.** On a phone with less to spare
  than the emulator (a real 2 GB phone with other apps installed, or a 128 MB heap instead of
  256), that margin is not proven.

The floor can come back down if:
- the XNNPACK options above cut the cost below 150 MB; or
- a real 2 GB phone shows the cost is acceptable.

## Found along the way

- **Card vectors that land after the modules were not picked up until a restart.** The dense
  index was rebuilt only when the module list changed. On a fresh install the modules are
  written before the vectors, so smarter search stayed off until the app restarted.
  - Fixed: `ChatKnowledgeIndexBootstrap` now rebuilds on card-vector writes too, through the new
    `CardEmbeddingDao.observeCount()`.
  - This was already broken at HEAD.
- **Harness:**
  - `setup --mode enhanced` now waits for the mode bar to read "smarter search" before it turns
    airplane mode on. Otherwise it cut the download short.
  - It opens the answering sheet through the mode bar's label when the "Change" text is missing
    from the accessibility tree, which it was in Bangla.
  - It does not warn `dense_off` on deny-list refusals.

## Not measured

- **Speed on real hardware.** Every timing above is an emulator best case.
- **A 128 MB heap.** The AVD gives 256 MB. The card vectors are 0.6 MB today, so heap is not the
  constraint at this corpus size.
- **1 GB and 1.5 GB phones, and 32-bit installs.** These are covered by `OnDeviceSetupTest` only.
