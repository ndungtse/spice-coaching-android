from pathlib import Path

from chat_eval import corpus, files, report

FIX = Path(__file__).parent / "fixtures"
C = corpus.load(FIX / "corpus_sdk.json")
SETTINGS = {"bar": {"card_after_gate": {"exact_min_fraction": 0.5, "other_max": 0, "refused_max": 0,
                                        "off_topic_refused_all": True}, "capture": {"warned_rows_max": 0}}}
BANK = [{"id": "q1", "question": "ডায়রিয়ার কারণ কী?", "question_type": "exact_match", "expect": {"cards": ["aaaaaaaa:0"]}},
        {"id": "q2", "question": "কারণ?", "question_type": "paraphrased", "expect": {"cards": ["aaaaaaaa:0"]}},
        {"id": "q3", "question": "আবহাওয়া?", "question_type": "irrelevant", "expect": {"refuse": True}}]
CHECKS = [
    {"id": "q1", "before": "EXACT", "after": "EXACT", "picked": "aaaaaaaa:0", "served": "aaaaaaaa:0", "shown": "rewrite",
     "path": "assisted", "warnings": [], "verdict": None, "verdict_by": None, "reason": None, "needs_judge": True},
    {"id": "q2", "before": "EXACT", "after": "REFUSED", "picked": "aaaaaaaa:0", "served": None, "shown": "none",
     "path": "assisted", "warnings": [], "verdict": "INCORRECT", "verdict_by": "harness",
     "reason": "gate refused the right card (NO_EVIDENCE)", "needs_judge": False},
    {"id": "q3", "before": "NONE", "after": "REFUSED", "picked": None, "served": None, "shown": "none",
     "path": "assisted", "warnings": [], "verdict": "CORRECT", "verdict_by": "harness",
     "reason": "refused an off-topic question", "needs_judge": False},
]


def test_awaiting_judge_rows_block_totals():
    finals = report.final_verdicts(CHECKS, {})
    assert finals["q1"]["verdict"] == "awaiting judge"
    s = report.summarize(CHECKS, finals, BANK, SETTINGS)
    assert s["answers"] is None and s["awaiting_judge"] == 1


def test_judged_totals_and_where_it_went_wrong():
    judged = {"primary": {"q1": {"id": "q1", "verdict": "PARTIAL", "reason": "omits types",
                                 "hallucination": {"phrase": "সংক্রমণ"}, "judge": "model:x"}}}
    finals = report.final_verdicts(CHECKS, judged["primary"])
    s = report.summarize(CHECKS, finals, BANK, SETTINGS)
    assert s["answers"] == {"CORRECT": 1, "PARTIAL": 1, "INCORRECT": 1}
    assert s["hallucinations"] == 1
    assert s["card_after_gate"]["EXACT"] == 1 and s["card_after_gate"]["REFUSED"] == 1
    assert s["cells"]["REFUSED"]["INCORRECT"] == ["q2"]
    assert s["bar"]["refused_max"]["pass"] is False


def test_report_lists_every_question_and_the_rerun(tmp_path):
    files.write_json(tmp_path / "run.json", {"mode": "ON_DEVICE_ASSISTED", "bank_ids": ["q1", "q2", "q3"]})
    turns = [{"id": i, "text_shown": f"shown {i}", "latency_ms": 10, "fused": None, "gate": None, "post": None,
              "pick": None, "outcome": None, "captured_at": "t"} for i in ("q1", "q1", "q2", "q3")]
    files.write_jsonl(tmp_path / "turns.jsonl", turns)
    files.write_jsonl(tmp_path / "checks.jsonl", CHECKS)
    md = report.render(tmp_path, BANK, C, SETTINGS)
    for i in ("q1", "q2", "q3"):
        assert f"### {i}" in md
    assert "shown q2" in md and "awaiting judge" in md
    assert "Reruns: q1 (1)" in md
    assert (tmp_path / "summary.json").exists()
