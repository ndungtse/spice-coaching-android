"""The question bank: one row per question, with the expected card or a refusal."""
from __future__ import annotations

import re
from pathlib import Path

from chat_eval import files
from chat_eval.corpus import Corpus

KEY_RE = re.compile(r"^[0-9a-f]{8}:\d+$")


class BankError(Exception):
    pass


def expected_cards(row: dict) -> list[str]:
    return list(row["expect"].get("cards", []))


def is_off_topic(row: dict) -> bool:
    return bool(row["expect"].get("refuse"))


def _problems(rows: list[dict]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for r in rows:
        rid = r.get("id")
        if not rid or not r.get("question"):
            out.append(f"row without id or question: {r}")
            continue
        if rid in seen:
            out.append(f"duplicate id {rid}")
        seen.add(rid)
        expect = r.get("expect") or {}
        if bool(expect.get("refuse")) == bool(expect.get("cards")):
            out.append(f"{rid}: expect needs exactly one of cards or refuse")
        for key in expect.get("cards", []):
            if not KEY_RE.match(key):
                out.append(f"{rid}: bad key {key}")
    return out


def load(path: Path | str) -> list[dict]:
    rows = files.read_jsonl(path)
    problems = _problems(rows)
    if problems:
        raise BankError("\n".join(problems))
    return rows


def import_qa_pack(pack_rows: list[dict], source: str, prefix: str, corp: Corpus) -> list[dict]:
    """QA test-case rows to bank rows. Card order in the packs is 1-based; keys are 0-based."""
    out, mismatches = [], []
    for i, q in enumerate(pack_rows, start=1):
        if q.get("question_type") == "irrelevant" or not q.get("expected_card_title"):
            expect: dict = {"refuse": True}
        else:
            order = int(q["expected_card_id"].rsplit(":card:", 1)[1])
            key = f"{q['expected_module_family_id'][:8]}:{order - 1}"
            card = corp.get(key)
            if card is None or re.sub(r"\s+", "", card.title) != re.sub(r"\s+", "", q["expected_card_title"]):
                mismatches.append(f"{prefix}_q{i:03d} {key}: corpus title {card.title if card else None!r} "
                                  f"!= pack title {q['expected_card_title']!r}")
                continue
            expect = {"cards": corp.with_twins([key])}
        out.append({"id": f"{prefix}_q{i:03d}", "source": source, "lang": "bn", "question": q["question"],
                    "question_type": q.get("question_type"), "domain": q.get("expected_domain"),
                    "expect": expect})
    if mismatches:
        raise BankError("\n".join(mismatches))
    return out


def from_sdk_fixture(rows: list[dict]) -> list[dict]:
    """The SDK's former `qa_questions_bn.json` rows to bank rows; twins are already present."""
    return [{"id": r["id"], "source": r["source"], "lang": r.get("lang", "bn"), "question": r["question"],
             "question_type": r.get("question_type"), "domain": r.get("domain"),
             "expect": {"refuse": True} if r["acceptable"] == "refuse" else {"cards": list(r["acceptable"])}}
            for r in rows]
