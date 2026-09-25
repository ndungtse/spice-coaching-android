# chat-eval

Runs chat questions on a device, records what every stage of the offline chat did, and lays
the result out for judgement. The harness decides a verdict only where identity settles it; a
capable model or a person judges everything the CHW saw that is not the expected card
verbatim. See `JUDGING.md` for the rules and `FORMATS.md` for every file.

```
bank + corpus ─► run ─► turns.jsonl ─► check ─► checks.jsonl + judge_sheet ─► judge ─► verdicts ─► report
```

Nothing under `runs/`, `corpora/` or `local.toml` is ever committed.

## Setup

**Python 3.11 or newer**, then from this folder:

```bash
python3 -m venv .venv
. .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -e '.[dev]'
pytest                         # pure-code tests; no device needed
```

**Android SDK.** Set `ANDROID_HOME` to the SDK folder, which holds `platform-tools/adb` and
`emulator/emulator`.

**Appium 3 with the UiAutomator2 driver** (needs Node):

```bash
npm install -g appium
appium driver install uiautomator2
appium --address 127.0.0.1 --port 4723     # leave running during setup and run
```

**Local settings.** Copy `local.example.toml` to `local.toml` and fill in the device serial, and
the APK path and test credentials if `setup --install` will be used. `local.toml` is gitignored.

**Which device:**

| Host | Offline modes |
|---|---|
| macOS, Apple Silicon | arm64 AVD; the arm64 host APK runs natively |
| Linux, x86_64 | a physical ARM64 phone over USB, or the x86_64 host APK on an x86_64 AVD |
| Linux, ARM64 | arm64 AVD |
| Windows, x86_64 | a physical phone, or the x86_64 host APK on an AVD with the hypervisor enabled |

An arm64-only APK on an x86_64 emulator crashes after login. `doctor` refuses that combination.

```bash
chat-eval doctor
```

## Smoke run (5 questions)

```bash
chat-eval setup --mode direct        # or --mode assisted
chat-eval run --source qa-uc2-2026-09-17 --mode direct \
    --only uc2_q001,uc2_q002,uc2_q003,uc2_q004,uc2_q096 --out runs/smoke-direct
chat-eval check runs/smoke-direct --corpus <corpus.json>
chat-eval report runs/smoke-direct --corpus <corpus.json>
```

`setup` signs in if needed, opens the assistant, picks "on this phone" and the answer style
(exact words for direct, simple words for assisted), restarts the app once in assisted mode
so the encoder is loaded, and turns airplane mode on. It never ticks the option that deletes
the downloaded model. It does not accept the "simple words" download offer for you; if that
offer is showing, accept it on the device and setup waits.

The corpus is the published module export the device synced: either the SDK's audit corpus
format or the QA team's `published_modules_lean.json`. It is passed by path, never committed.

Expected: `turns.jsonl` has 5 rows with empty `warnings`, `check` reports 0 warned, and
`report.md` lists every question.

## Full run

The same commands without `--only`. After an interruption, `--resume` skips the questions
already captured. To rerun a few, pass `--only <ids>` with the same `--out`; the later turn
wins and the report lists it as a rerun.

## Judging

1. Open `runs/<run>/judge_sheet.md`. The judge instructions are at the top.
2. Write a verdict file, one line per sheet entry, in the format in `JUDGING.md`. A model can
   do this from the sheet; a person can fill it by hand.
3. Store it: `chat-eval verdicts runs/<run> <file>`. A second judge adds `--as <name>`, and
   the report shows their agreement with the first.
4. Re-run `chat-eval report`.

Until every sheet entry has a verdict the report shows those rows as "awaiting judge" and
prints no answer totals.

## Adding questions

```bash
chat-eval bank import <qa-test-cases.json> --source <name> --prefix uc4 --corpus <corpus.json>
```

The importer converts the QA packs' 1-based card order to keys, refuses the whole pack if any
expected title differs from the corpus, and adds twin cards: cards with an identical title in
another module family.
