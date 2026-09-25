"""The judging sheet a model or a human fills in, and validation of what comes back."""
from __future__ import annotations

from pathlib import Path

from chat_eval import files
from chat_eval.corpus import Corpus

VERDICTS = {"CORRECT", "PARTIAL", "INCORRECT"}
_INSTRUCTIONS = files.PROJECT_ROOT / "JUDGING.md"


class VerdictError(Exception):
    pass


def _card(corp: Corpus, key: str | None) -> dict | None:
    card = corp.get(key) if key else None
    return {"key": key, "title": card.title, "body": card.body} if card else ({"key": key, "title": None, "body": None} if key else None)


def write_sheet(run_dir: Path, checks: list[dict], bank_rows: list[dict], corp: Corpus, turns: list[dict]) -> list[dict]:
    by_id = {r["id"]: r for r in bank_rows}
    shown = {t["id"]: t.get("text_shown", "") for t in turns}
    entries = []
    for c in (c for c in checks if c.get("needs_judge")):
        row = by_id[c["id"]]
        expected_key = row["expect"]["cards"][0]
        served_key = c["served"] if c["served"] not in row["expect"]["cards"] else None
        entries.append({"id": c["id"], "question": row["question"], "question_type": row.get("question_type"),
                        "expected": _card(corp, expected_key), "served": _card(corp, served_key),
                        "path": c["path"], "shown": c["shown"], "shown_text": shown.get(c["id"], ""),
                        "before": c["before"], "after": c["after"]})
    files.write_jsonl(run_dir / "judge_sheet.jsonl", entries)
    lines = [_INSTRUCTIONS.read_text(encoding="utf-8").rstrip(), "", "---", ""] if _INSTRUCTIONS.exists() else []
    for e in entries:
        lines += [f"## {e['id']}", "", f"**Question** ({e['question_type']}): {e['question']}", "",
                  f"**Expected card** `{e['expected']['key']}` {e['expected']['title']}", "", f"> {e['expected']['body']}", ""]
        if e["served"]:
            lines += [f"**Served card** `{e['served']['key']}` {e['served']['title']}", "", f"> {e['served']['body']}", ""]
        lines += [f"**Path:** {e['path']}; the CHW saw {'a rewrite' if e['shown'] == 'rewrite' else 'the card verbatim'}. "
                  f"Card before the gate: {e['before']}; after: {e['after']}.", "",
                  "**Shown to the CHW:**", "", f"> {e['shown_text']}", ""]
    (run_dir / "judge_sheet.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return entries


def validate(entries: list[dict], verdicts: list[dict]) -> None:
    wanted = {e["id"] for e in entries}
    got = {v.get("id") for v in verdicts}
    problems = [f"missing {i}" for i in sorted(wanted - got)]
    problems += [f"not on the sheet: {i}" for i in sorted(got - wanted)]
    for v in verdicts:
        if v.get("verdict") not in VERDICTS:
            problems.append(f"{v.get('id')}: verdict {v.get('verdict')}")
        if not str(v.get("reason") or "").strip():
            problems.append(f"{v.get('id')}: empty reason")
        if not str(v.get("judge") or "").startswith(("model:", "human:")):
            problems.append(f"{v.get('id')}: judge must be model:<id> or human:<initials>")
        h = v.get("hallucination")
        if h is not None and not (isinstance(h, dict) and str(h.get("phrase") or "").strip()):
            problems.append(f"{v.get('id')}: hallucination must be null or {{\"phrase\": …}}")
    if problems:
        raise VerdictError("\n".join(problems))


def store(run_dir: Path, verdicts: list[dict], name: str) -> Path:
    folder = run_dir / "verdicts"
    folder.mkdir(exist_ok=True)
    path = folder / f"{name}.jsonl"
    files.write_jsonl(path, verdicts)
    return path


def load_all(run_dir: Path) -> dict[str, dict[str, dict]]:
    folder = run_dir / "verdicts"
    if not folder.exists():
        return {}
    return {p.stem: {v["id"]: v for v in files.read_jsonl(p)} for p in sorted(folder.glob("*.jsonl"))}
