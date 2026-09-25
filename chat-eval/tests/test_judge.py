from pathlib import Path

import pytest

from chat_eval import corpus, files, judge

FIX = Path(__file__).parent / "fixtures"
C = corpus.load(FIX / "corpus_sdk.json")
BANK = [{"id": "q1", "question": "ডায়রিয়ার কারণ কী?", "question_type": "exact_match",
         "expect": {"cards": ["aaaaaaaa:0"]}},
        {"id": "q2", "question": "লক্ষণ কী?", "question_type": "paraphrased", "expect": {"cards": ["aaaaaaaa:0"]}}]
CHECKS = [{"id": "q1", "needs_judge": True, "served": "aaaaaaaa:0", "picked": "aaaaaaaa:0", "shown": "rewrite",
           "path": "assisted", "before": "EXACT", "after": "EXACT"},
          {"id": "q2", "needs_judge": True, "served": "aaaaaaaa:1", "picked": "aaaaaaaa:1", "shown": "card",
           "path": "direct", "before": "NEIGHBOUR", "after": "NEIGHBOUR"}]
TURNS = [{"id": "q1", "text_shown": "সংক্রমণ।"}, {"id": "q2", "text_shown": "ঘন ঘন পাতলা পায়খানা।"}]


def good(i, v="CORRECT"):
    return {"id": i, "verdict": v, "reason": "ok reason", "hallucination": None, "judge": "human:ab"}


def test_sheet_holds_expected_and_served_card_text(tmp_path):
    entries = judge.write_sheet(tmp_path, CHECKS, BANK, C, TURNS)
    assert entries[0]["expected"]["body"] == "জীবাণুর আক্রমণে ডায়রিয়া হয়।"
    assert entries[0]["served"] is None
    assert entries[1]["served"]["title"] == "ডায়রিয়ার লক্ষণ"
    assert entries[1]["shown_text"] == "ঘন ঘন পাতলা পায়খানা।"
    md = (tmp_path / "judge_sheet.md").read_text(encoding="utf-8")
    assert md.startswith("# Judging instructions") and "## q1" in md and "## q2" in md


def test_validate_accepts_a_complete_file():
    entries = [{"id": "q1"}, {"id": "q2"}]
    judge.validate(entries, [good("q1"), good("q2", "PARTIAL")])


@pytest.mark.parametrize("verdicts, message", [
    ([good("q1")], "missing q2"),
    ([good("q1"), good("q2"), good("q9")], "not on the sheet: q9"),
    ([good("q1"), good("q2", "MAYBE")], "q2: verdict MAYBE"),
    ([good("q1"), dict(good("q2"), reason=" ")], "q2: empty reason"),
    ([good("q1"), dict(good("q2"), judge="")], "q2: judge"),
])
def test_validate_rejects(verdicts, message):
    with pytest.raises(judge.VerdictError, match=message):
        judge.validate([{"id": "q1"}, {"id": "q2"}], verdicts)


def test_store_and_load_named_judges(tmp_path):
    judge.store(tmp_path, [good("q1")], "primary")
    judge.store(tmp_path, [good("q1", "PARTIAL")], "second")
    loaded = judge.load_all(tmp_path)
    assert loaded["primary"]["q1"]["verdict"] == "CORRECT"
    assert loaded["second"]["q1"]["verdict"] == "PARTIAL"


def test_verdict_file_with_bom_and_crlf_imports(tmp_path):
    p = tmp_path / "v.jsonl"
    p.write_bytes(('﻿' + '{"id":"q1","verdict":"CORRECT","reason":"r","hallucination":null,"judge":"human:ab"}\r\n').encode())
    judge.validate([{"id": "q1"}], files.read_jsonl(p))
