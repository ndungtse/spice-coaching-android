# File formats

Every file is UTF-8. JSONL files hold one JSON object per line. A run folder holds `run.json`,
`modules.json`, `turns.jsonl`, `checks.jsonl`, `judge_sheet.jsonl`, `judge_sheet.md`, `verdicts/`,
`summary.json` and `report.md`. Nothing in a run folder is edited after the stage that wrote it,
except `run.json`, which `check` extends once with the corpus hash.

A card key is `family8:index`: the first 8 hex characters of the module family id and the
0-based position of the card in the module.

## `banks/qa_bn.jsonl`

| Field | Type | Meaning |
|---|---|---|
| `id` | string | unique, e.g. `uc2_q004` |
| `source` | string | the QA pack or run the row came from |
| `lang` | string | `bn` |
| `question` | string | the text typed into the chat |
| `question_type` | string | from the QA pack: `exact_match`, `paraphrased`, `code_mixed`, `typo_noisy`, `native_bengali`, `irrelevant` |
| `domain` | string or null | from the QA pack |
| `expect` | object | `{"cards": [keys]}`, the expected card and its twins, or `{"refuse": true}` |

## `run.json`

| Field | Meaning |
|---|---|
| `mode` | `ON_DEVICE_ASSISTED` or `ON_DEVICE_DIRECT` |
| `source` | the bank source asked |
| `bank_ids` | every id asked, so a missing turn is reported rather than dropped |
| `bank_sha256` | hash of the bank file at capture time |
| `device` | serial, model, ABI, RAM, emulator or not |
| `apk` | host app version name and code |
| `airplane_on` | airplane mode at capture start |
| `repo` | repository commit and whether the tree was dirty |
| `capture` | the `[capture]` settings used |
| `started_at`, `ended_at` | UTC timestamps |
| `corpus_file`, `corpus_sha256` | `modules.json` and its hash, exported from the device at the start; null when the build is not debuggable |
| `corpus_changed_during_run` | true when the end-of-run export differs, which keeps `modules.end.json` |

## `modules.json`

The modules in the SDK's database on the device when the run started, in the corpus format
`corpus.load` reads: `module_id`, `module_family_id`, `version`, `title_bn`, `title_json`,
`cards_json`, `search_metadata_json`, and `retired` (the family is in the SDK's retired list).
`check` and `report` use it when `--corpus` is not given.

## `turns.jsonl`

One line per question asked; a rerun appends another line for the same id, and the last wins.

| Field | Meaning |
|---|---|
| `id`, `question` | from the bank |
| `anchored` | a turn line quoting this question was found in the log |
| `mode` | from the turn line |
| `fused` | the `FUSED` line: list of `{key, bm25_rank, bm25_score, dense_rank, cos}`; null when not logged |
| `pick` | the `RANK` line: `{key, fused_rank, band, evidence}` |
| `gate` | the `GATE` line: `{result: serve|refuse, key, reason}`; `reason` is `DENY` for the deny-list |
| `post` | the `POST` line: `{groundedness, floor, validator, translation, shown}`; assisted only |
| `outcome` | `{kind, detail}` from `OUTCOME=`: `served_grounded`, `FALLBACK` with its kind, or `REFUSAL` with its key |
| `text_en` | the model's English answer preview, for diagnosis only |
| `text_shown` | the answer bubble the CHW saw |
| `latency_ms` | send to outcome |
| `warnings` | `no_anchor`, `timeout`, `mode_mismatch`, `crash`, `no_text` |
| `trace` | this turn's log lines, prefixes removed |
| `captured_at` | UTC timestamp |

## `checks.jsonl`

| Field | Meaning |
|---|---|
| `id` | bank id |
| `before` | the ranker's pick against the bank: `EXACT`, `NEIGHBOUR` (same module), `OTHER`, `NONE` |
| `after` | the served card against the bank: the same values, or `REFUSED` |
| `picked`, `served` | card keys |
| `shown` | `card` (verbatim), `rewrite`, or `none` |
| `path` | `direct` or `assisted` |
| `warnings` | the turn's warnings, plus `foreign_text` or `not_captured` |
| `verdict`, `verdict_by`, `reason` | set only when the harness decides (see `JUDGING.md`); else null |
| `needs_judge` | true when the row goes on the judging sheet |

## `judge_sheet.jsonl`

One entry per row with `needs_judge`: `id`, `question`, `question_type`, `expected` and
`served` (each `{key, title, body}`; `served` is null when it is an expected card), `path`,
`shown`, `shown_text`, `before`, `after`. `judge_sheet.md` shows the same entries under the
instructions from `JUDGING.md`.

## `verdicts/<name>.jsonl`

One line per sheet entry: `id`, `verdict` (`CORRECT`, `PARTIAL`, `INCORRECT`), `reason`,
`hallucination` (null or `{"phrase": …}`), `judge` (`model:<id>` or `human:<initials>`). The
first judge is stored as `primary`; totals come from it.

## `summary.json`

`rows`, `warned`, `awaiting_judge`, `answers` (counts per verdict, or null while any row awaits a
judge), `hallucinations`, `decided_by` (`harness`, `judge`), `card_before_gate` and
`card_after_gate` (counts per check value over answerable rows), `cells` (after-gate check →
verdict → ids), `off_topic` (`refused`, `total`), and `bar` (each bar line with its value and
pass).
