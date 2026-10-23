"""The run report: every question with every stage and its verdict, plus the totals."""
from __future__ import annotations

from pathlib import Path

from chat_eval import bank, check, files, judge
from chat_eval.corpus import Corpus

CHECK_VALUES = ["EXACT", "NEIGHBOUR", "OTHER", "REFUSED", "NONE"]
VERDICT_ORDER = ["CORRECT", "PARTIAL", "INCORRECT"]


def final_verdicts(checks: list[dict], judged: dict[str, dict]) -> dict[str, dict]:
    out = {}
    for c in checks:
        if c["warnings"]:
            out[c["id"]] = {"verdict": "warned", "by": None, "reason": ", ".join(c["warnings"]), "hallucination": None}
        elif c["verdict"]:
            out[c["id"]] = {"verdict": c["verdict"], "by": "harness", "reason": c["reason"], "hallucination": None}
        elif c["id"] in judged:
            v = judged[c["id"]]
            out[c["id"]] = {"verdict": v["verdict"], "by": v["judge"], "reason": v["reason"], "hallucination": v.get("hallucination")}
        else:
            out[c["id"]] = {"verdict": "awaiting judge", "by": None, "reason": None, "hallucination": None}
    return out


def summarize(checks: list[dict], finals: dict[str, dict], bank_rows: list[dict], settings: dict) -> dict:
    by_id = {r["id"]: r for r in bank_rows}
    scored = [c for c in checks if not c["warnings"]]
    answerable = [c for c in scored if not bank.is_off_topic(by_id[c["id"]])]
    off_topic = [c for c in scored if bank.is_off_topic(by_id[c["id"]])]
    awaiting = sum(finals[c["id"]]["verdict"] == "awaiting judge" for c in scored)
    after = {v: sum(c["after"] == v for c in answerable) for v in CHECK_VALUES}
    before = {v: sum(c["before"] == v for c in answerable) for v in CHECK_VALUES}
    cells: dict[str, dict[str, list[str]]] = {}
    for c in scored:
        cells.setdefault(c["after"], {}).setdefault(finals[c["id"]]["verdict"], []).append(c["id"])
    bar = settings["bar"]["card_after_gate"]
    n = max(len(answerable), 1)
    off_refused = sum(c["after"] == "REFUSED" for c in off_topic)
    return {
        "rows": len(checks), "warned": len(checks) - len(scored), "awaiting_judge": awaiting,
        "answers": None if awaiting else {v: sum(finals[c["id"]]["verdict"] == v for c in scored) for v in VERDICT_ORDER},
        "hallucinations": sum(bool(finals[c["id"]]["hallucination"]) for c in scored),
        "decided_by": {"harness": sum(finals[c["id"]]["by"] == "harness" for c in scored),
                       "judge": sum((finals[c["id"]]["by"] or "harness") != "harness" for c in scored)},
        "card_before_gate": before, "card_after_gate": after, "cells": cells,
        "off_topic": {"refused": off_refused, "total": len(off_topic)},
        "bar": {
            "exact_min_fraction": {"value": round(after["EXACT"] / n, 3), "pass": after["EXACT"] / n >= bar["exact_min_fraction"]},
            "other_max": {"value": after["OTHER"], "pass": after["OTHER"] <= bar["other_max"]},
            "refused_max": {"value": after["REFUSED"], "pass": after["REFUSED"] <= bar["refused_max"]},
            "off_topic_refused_all": {"value": f"{off_refused}/{len(off_topic)}", "pass": off_refused == len(off_topic)},
            "warned_rows_max": {"value": len(checks) - len(scored),
                                "pass": len(checks) - len(scored) <= settings["bar"]["capture"]["warned_rows_max"]},
        },
    }


def _fused_text(fused: list[dict] | None) -> str:
    if not fused:
        return "not logged"
    return ", ".join(f"{f['key']} (bm25 {f['bm25_rank'] or '-'}, dense {f['dense_rank'] or '-'})" for f in fused)


def render(run_dir: Path, bank_rows: list[dict], corp: Corpus, settings: dict) -> str:
    header = files.read_json(run_dir / "run.json")
    latest, reruns = check.latest_turns(files.read_jsonl(run_dir / "turns.jsonl"))
    checks = files.read_jsonl(run_dir / "checks.jsonl")
    judged_all = judge.load_all(run_dir)
    finals = final_verdicts(checks, judged_all.get("primary", {}))
    s = summarize(checks, finals, bank_rows, settings)
    files.write_json(run_dir / "summary.json", s)
    by_id = {r["id"]: r for r in bank_rows}
    L = [f"# Chat evaluation report: {run_dir.name}", "",
         "| Field | Value |", "|---|---|"] + [f"| {k} | {v} |" for k, v in header.items() if k != "bank_ids"]
    L += ["", "## Capture health", "", f"Rows: {s['rows']}. Warned: {s['warned']}. "
          f"Reruns: {', '.join(f'{k} ({v})' for k, v in reruns.items()) or 'none'}.", ""]
    L += ["## Answer verdicts", ""]
    if s["answers"] is None:
        L += [f"{s['awaiting_judge']} rows are awaiting a judge; no totals until every row has a verdict.", ""]
    else:
        L += ["| Verdict | Rows |", "|---|---|"] + [f"| {k} | {v} |" for k, v in s["answers"].items()]
        L += ["", f"Decided by the harness: {s['decided_by']['harness']}; by a judge: {s['decided_by']['judge']}. "
              f"Hallucinations recorded: {s['hallucinations']}.", ""]
    for other, verdicts in judged_all.items():
        if other != "primary":
            both = [i for i in verdicts if i in judged_all.get("primary", {})]
            agree = sum(verdicts[i]["verdict"] == judged_all["primary"][i]["verdict"] for i in both)
            L += [f"Agreement of `{other}` with `primary`: {agree}/{len(both)}.", ""]
    L += ["## Card checks", "", "| Check | Before the gate | After the gate |", "|---|---|---|"]
    L += [f"| {v} | {s['card_before_gate'][v]} | {s['card_after_gate'][v]} |" for v in CHECK_VALUES]
    L += ["", f"Off-topic refused: {s['off_topic']['refused']}/{s['off_topic']['total']}.", "",
          "| Bar | Value | Pass |", "|---|---|---|"] + [f"| {k} | {b['value']} | {'yes' if b['pass'] else 'no'} |" for k, b in s["bar"].items()]
    L += ["", "## Where it went wrong", "",
          "Refused with the right card picked: look at the gate. Wrong card picked: look at fusion and the ranker. "
          "Right card served but not CORRECT: look at the rewrite and the post-model checks.", "",
          "| After the gate | Verdict | Questions |", "|---|---|---|"]
    for after_value, verdicts in sorted(s["cells"].items()):
        for verdict, ids in sorted(verdicts.items()):
            L.append(f"| {after_value} | {verdict} | {len(ids)}: {', '.join(ids)} |")

    def entry(c: dict) -> list[str]:
        t, row, f = latest.get(c["id"], {}), by_id[c["id"]], finals[c["id"]]
        exp = row["expect"].get("cards", [])
        exp_card = corp.get(exp[0]) if exp else None
        served = corp.get(c["served"]) if c["served"] else None
        gate = t.get("gate") or {}
        expected_text = "refuse" if not exp else f"`{exp[0]}` " + (exp_card.title if exp_card else "")
        return [f"### {c['id']}", "",
                f"- **Question** ({row.get('question_type')}): {row['question']}",
                f"- **Expected:** {expected_text}",
                f"- **Fusion:** {_fused_text(t.get('fused'))}",
                f"- **Ranker pick:** {c['picked'] or '-'} ({c['before']})",
                f"- **Gate:** {gate.get('result', '-')} {gate.get('key') or ''} {gate.get('reason') or ''}".rstrip(),
                f"- **Served:** {c['served'] or '-'} {served.title if served else ''} ({c['after']}); CHW saw: {c['shown']}",
                f"- **Post-model:** {t.get('post') or '-'}",
                f"- **Verdict:** {f['verdict']}" + (f" by {f['by']}" if f["by"] else "") + (f": {f['reason']}" if f["reason"] else ""),
                f"- **Hallucination:** {f['hallucination']['phrase']}" if f["hallucination"] else "- **Hallucination:** none recorded",
                f"- **Latency:** {t.get('latency_ms', '-')} ms" + (f"; warnings: {', '.join(c['warnings'])}" if c["warnings"] else ""),
                "", "Shown to the CHW:", "", f"> {t.get('text_shown') or '(nothing)'}", ""]

    L += ["", "## Every question", ""]
    for c in checks:
        L += entry(c)
    misses = [c for c in checks if finals[c["id"]]["verdict"] != "CORRECT"]
    L += ["## Misses", "", f"{len(misses)} rows are not CORRECT. Each is listed above; its full trace is below.", ""]
    for c in misses:
        L += [f"### {c['id']} trace", "", "```", *latest.get(c["id"], {}).get("trace", []), "```", ""]
    text = "\n".join(L) + "\n"
    (run_dir / "report.md").write_text(text, encoding="utf-8")
    return text


def diff(run_a: Path, run_b: Path) -> str:
    a, b = files.read_json(run_a / "run.json"), files.read_json(run_b / "run.json")
    if a.get("bank_sha256") != b.get("bank_sha256"):
        raise ValueError("the two runs used different banks")
    ca = {c["id"]: c for c in files.read_jsonl(run_a / "checks.jsonl")}
    cb = {c["id"]: c for c in files.read_jsonl(run_b / "checks.jsonl")}
    fa = final_verdicts(list(ca.values()), judge.load_all(run_a).get("primary", {}))
    fb = final_verdicts(list(cb.values()), judge.load_all(run_b).get("primary", {}))
    L = [f"# {run_a.name} vs {run_b.name}", "", "| Question | Card after gate | Verdict |", "|---|---|---|"]
    for i in sorted(set(ca) & set(cb)):
        if ca[i]["after"] != cb[i]["after"] or fa[i]["verdict"] != fb[i]["verdict"]:
            L.append(f"| {i} | {ca[i]['after']} → {cb[i]['after']} | {fa[i]['verdict']} → {fb[i]['verdict']} |")
    return "\n".join(L) + "\n"
