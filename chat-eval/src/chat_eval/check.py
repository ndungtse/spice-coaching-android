"""Identity checks the harness can make without judgement: which card reached each stage,
and the verdicts that follow from identity alone. Everything else is marked for a judge."""
from __future__ import annotations

from pathlib import Path

from chat_eval import bank, files
from chat_eval.corpus import Corpus

_VERBATIM_KINDS = {"FALLBACK"}  # the CHW saw a card's own text


def card_check(key: str | None, refused: bool, expect: list[str]) -> str:
    if refused:
        return "REFUSED"
    if key is None:
        return "NONE"
    if key in expect:
        return "EXACT"
    if any(key.split(":")[0] == e.split(":")[0] for e in expect):
        return "NEIGHBOUR"
    return "OTHER"


def latest_turns(turns: list[dict]) -> tuple[dict[str, dict], dict[str, int]]:
    latest: dict[str, dict] = {}
    counts: dict[str, int] = {}
    for t in turns:
        counts[t["id"]] = counts.get(t["id"], 0) + 1
        latest[t["id"]] = t
    return latest, {k: v - 1 for k, v in counts.items() if v > 1}


def _foreign_text(shown: str, served: str | None, corp: Corpus) -> bool:
    """True when the shown text is verbatim another card's body: the previous turn's answer
    captured as this one. Twins of the served card share its body, so they never count."""
    text = (shown or "").strip()
    if len(text) < 20 or served is None:
        return False
    own = set(corp.with_twins([served]))
    return any(key not in own and card.body and (text in card.body or (len(card.body) >= 40 and card.body in text))
               for key, card in corp.cards.items())


def check_row(row: dict, turn: dict | None, corp: Corpus) -> dict:
    expect = bank.expected_cards(row)
    base = {"id": row["id"], "before": None, "after": None, "picked": None, "served": None,
            "shown": "none", "path": None, "warnings": [], "verdict": None, "verdict_by": None,
            "reason": None, "needs_judge": False}
    if turn is None:
        return base | {"warnings": ["not_captured"]}
    gate = turn.get("gate") or {}
    refused = gate.get("result") == "refuse"
    picked = (turn.get("pick") or {}).get("key")
    served = None if refused else gate.get("key")
    kind = (turn.get("outcome") or {}).get("kind")
    shown = "none" if refused else ("card" if kind in _VERBATIM_KINDS else "rewrite")
    warnings = list(turn.get("warnings") or [])
    if served and shown != "none" and _foreign_text(turn.get("text_shown", ""), served, corp):
        warnings.append("foreign_text")
    out = base | {"before": card_check(picked, False, expect), "after": card_check(served, refused, expect),
                  "picked": picked, "served": served, "shown": shown,
                  "path": "direct" if turn.get("mode") == "ON_DEVICE_DIRECT" else "assisted",
                  "warnings": warnings}
    if warnings:
        return out
    reason_code = gate.get("reason")
    if bank.is_off_topic(row):
        verdict = ("CORRECT", "refused an off-topic question") if refused else \
                  ("INCORRECT", f"answered an off-topic question with {served}")
    elif refused:
        why = f"gate refused the right card ({reason_code})" if out["before"] == "EXACT" else \
              f"refused ({reason_code}); ranker picked {picked}" if picked else f"refused ({reason_code})"
        verdict = ("INCORRECT", why)
    elif out["after"] == "EXACT" and shown == "card":
        verdict = ("CORRECT", "expected card served verbatim")
    else:
        return out | {"needs_judge": True}
    return out | {"verdict": verdict[0], "verdict_by": "harness", "reason": verdict[1]}


def run_checks(run_dir: Path, bank_rows: list[dict], corp: Corpus) -> list[dict]:
    latest, _ = latest_turns(files.read_jsonl(run_dir / "turns.jsonl"))
    # The run header lists every bank id that was asked, so a missing turn is reported, not dropped.
    asked = set(files.read_json(run_dir / "run.json")["bank_ids"])
    rows = [check_row(r, latest.get(r["id"]), corp) for r in bank_rows if r["id"] in asked]
    files.write_jsonl(run_dir / "checks.jsonl", rows)
    return rows
