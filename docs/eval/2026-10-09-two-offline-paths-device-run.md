# Both offline paths on the device

**Date:** 9 October 2026
**Plan:** Task 8 of the two-offline-paths plan; spec `docs/superpowers/specs/2026-10-08-two-offline-paths-design.md`
**Memory and the RAM floor:** `docs/eval/2026-10-09-smarter-search-2gb-emulator.md`

## Summary

- **Both paths match the replay on the device.**
  - Smarter search served the right card for **77 of 95** questions; the replay predicts 78.
  - Standard search served it for **69 of 95**; the replay predicts 71.
  - Every other figure is within 2 of the replay.
- **The upgrade from "simple words" works.** On the first start, the 497 MB language model was
  deleted, the encoder was kept, nothing downloaded, and the mode bar named smarter search.
- **A fresh opt-in downloads the encoder alone,** labelled as smarter search everywhere. Dense
  retrieval starts on the next question with no restart.
- **Opting out works,** with or without removing the download. So does cancelling, and a
  cancelled download stays cancelled.
- **Two things need attention before release:**
  - **Smarter-search runs on this emulator hit an "isn't responding" (ANR) stall about every 35
    questions; the standard run hit none.** See below.
  - **After a cancel, the answering sheet offers no direct way to restart the download.**

## Setup

| | |
|---|---|
| Device | `Android_13` AVD on `emulator-5554`: arm64-v8a, API 33, 1440×3120, booted with `-memory 4096` (`MemTotal` 4,003,092 kB) |
| Build | host `Spice-SL` dev debug, against SDK branch `feat/test-harness-and-model-rewrite` at `4ee2833` plus the uncommitted two-offline-paths work (rewrite off, `smarterSearchMinRamMb` 3072) |
| Bank | `qa-uc2-2026-09-17`: 100 Bangla questions, 95 answerable, 5 off-topic; app language Bangla |
| Module export | sha256 `724b0127dda7c5b99c59ccf49ca0e12ca597a78aa10570abe174cd40e1fc981c`, unchanged through both runs |
| Runs | `chat-eval/runs/two-paths-enhanced`, `chat-eval/runs/two-paths-standard`, `chat-eval/runs/two-paths-optout`, `chat-eval/runs/two-paths-firstturn` |

## Results

| Path | Right card of 95 | Right module | Wrong module | False refusals | Off-topic refused | Median turn (best case) |
|---|---|---|---|---|---|---|
| Smarter search, device | **77** | 79 | 3 | 13 | 5 / 5 | 25.3 s |
| Smarter search, replay | 78 | 80 | 3 | 12 | 5 / 5 | |
| Standard search, device | **69** | 71 | 1 | 23 | 5 / 5 | 13.7 s |
| Standard search, replay | 71 | 73 | 1 | 21 | 5 / 5 | |

The replay figures are from `docs/references/serve-gate.md` §9. No figure differs from the replay
by more than 3, so no gap needs an explanation.

**How the two paths compare:**
- Smarter search served the right card where standard search did not on 10 questions.
- It lost 2 (`uc2_q004`, `uc2_q026`).
- The smarter-search run on the 2 GB emulator served the same card for every question as this
  run.

**Warnings:**
- Smarter search: none from the run. Dense retrieval ran on all 97 questions that reached
  retrieval; the other 3 were refused by the deny-list before it runs.
  - `check` flagged `uc2_q048` as `foreign_text`, a screen capture taken during a stall.
- Standard search: none. There was no `dense_on`, though the encoder stayed on disk the whole
  time.

**Timings** are emulator best cases. The smarter-search turns are slower mainly because of the
stalls below.
- Smarter search: `dense_ms` median 792 ms, p90 8.2 s.

## Checks by hand

| Check | What was seen |
|---|---|
| **Upgrade from "simple words"** (the language model and the encoder on disk, consent `enabled`, installed over the 27 September build with `install -r`) | Logged `Removed qwen3_0_6b_mixed_int4.litertlm (497516544 bytes): the on-device rewrite is off on this build`, then `Reconcile (smarter search): verdict=READY` and `Smarter search ready: …/embed/embeddinggemma-300M_seq256_mixed-precision.tflite`. The `.litertlm` file was gone and `embed/` untouched. No download started. The bar read "এই ফোনে · উন্নত খোঁজা". |
| **Fresh install, offer** | The card showed the smarter-search copy: "আরও বেশিবার সঠিক নির্দেশনা খুঁজে পান … একবার ১৮৪ MB ডাউনলোড". 184 MB is Android's decimal reading of the same 176 MiB. |
| **Fresh install, download** | The worker logged `languageModel=false, encoder=true`. The bar read "উন্নত খোঁজা যোগ হচ্ছে · N%" and the notification "উন্নত খোঁজা ডাউনলোড হচ্ছে". No `.litertlm` appeared. |
| **A dropped connection** | The download dropped twice ("Software caused connection abort"). Each time the encoder-only work failed, as designed. Reopening chat requeued it, and it resumed from the partial file with an HTTP `Range` request ("Resuming download from byte 97211975", then 122382061, then 171967949). |
| **Download landing mid-session** | In one process (pid 15404, never restarted): the opt-in, the download, "Smarter search ready", then the first question logged `encoder loaded: loadMs=1159` and `DENSE hits=3`. |
| **Opt-out with removal** | Chose "সাধারণ খোঁজা" ("Standard search") with "ডাউনলোডটিও মুছে ফেলুন" ("Also remove the download") ticked. `files/embed/` was empty, the bar read "এই ফোনে", and the next question logged `DENSE off (smarter search not enabled)`. |
| **Opt-out keeping the download** | The whole standard-search run above: no turn used dense retrieval, though the encoder stayed on disk. |
| **Cancel mid-download** | The bar's cancel stopped the work and deleted the tokenizer, and the bar returned to "এই ফোনে · বদলান". The stored `download_user_cancelled` flag kept the download from restarting when the app was reopened. A **0-byte** `.tflite` was left behind: the worker created the file just after the cancel had cleaned up. The next download resumes onto it, so it costs nothing, but the cancel and the worker race. |
| **Low-end and 32-bit** | Not run. It needs a host build with `forceLowEndMode(true)`, and the host was left unchanged. `OnDeviceSetupTest` covers the rule: a 32-bit process, too little RAM, or no Hugging Face token means no offer and no search section, and chat answers with standard search. |

## Needs attention

**1. Stalls in smarter-search runs.** The smarter-search QA run raised an ANR three times on this
emulator, at turns 36, 71 and around 84. Each time chat-eval had to restart SPICE and resume. The
standard run did all 100 questions in one pass with none.
- **Where the main thread was stuck:**
  - waiting for the render thread to draw the chat (`syncAndDrawFrame`);
  - answering Appium's accessibility query.

  The render thread had used about 345 s of system CPU.
- **What it is not:** the encoder runs off the main thread with 2 threads. The 2 GB emulator
  (1080×2340, API 30) ran 100 smarter-search questions with no stall.
- **The likely cause:** encoding competes for the emulator's 4 CPUs with drawing a long Bangla
  chat on a 1440×3120 screen, while UiAutomator walks the whole view tree each turn. That is not
  proven.
- **Before release:** check on a real phone that a long smarter-search session stays responsive.
  If it doesn't, profile it.

An earlier ANR happened at app start, while sync, the index build, the Bangla voice pack and the
resumed encoder download were all running. The chat state did extra work on every download
progress tick: an `isLowEndDevice` binder call twice per tick. That call is now probed once.

**2. Restarting after a cancel.** After a cancel, consent stays "enabled" and the state is idle.
So the sheet shows smarter search already selected, with no download card to tap. The user has
to choose standard search and then smarter search again. The rewrite-on path behaves the same
way at HEAD. Possible fixes:
- show the download card for an idle, consented setup;
- have a cancel withdraw the consent.

## Bangla copy waiting for review

Each of these carries `<!-- TODO(bn-review) -->` in `res/values-bn/strings.xml`:

- `chat_mode_bar_on_device_search`, `notification_search_download_title`,
  `download_card_search_title`
- `answering_sheet_search_title`, `answering_sheet_search_standard_title`,
  `answering_sheet_search_standard_body`, `answering_sheet_search_smarter_title`,
  `answering_sheet_search_smarter_body`, `answering_sheet_search_smarter_body_sized`
- `answering_search_opt_out_title`, `answering_search_opt_out_body`,
  `answering_search_opt_out_confirm`
- `search_offer_title`, `search_offer_body`, `search_offer_body_sized`
