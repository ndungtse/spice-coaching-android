from pathlib import Path

from chat_eval import check, corpus

FIX = Path(__file__).parent / "fixtures"
C = corpus.load(FIX / "corpus_sdk.json")
ANSWERABLE = {"id": "q1", "question": "ডায়রিয়ার কারণ কী?", "expect": {"cards": ["aaaaaaaa:0", "bbbbbbbb:0"]}}
OFF_TOPIC = {"id": "q2", "question": "আজকের আবহাওয়া?", "expect": {"refuse": True}}


def turn(gate, pick=None, kind="served_grounded", mode="ON_DEVICE_ASSISTED", shown_text="উত্তর", warnings=()):
    return {"id": "q1", "mode": mode, "anchored": True, "fused": None,
            "pick": {"key": pick, "fused_rank": 1, "band": [], "evidence": ""} if pick else None,
            "gate": gate, "post": None, "outcome": {"kind": kind, "detail": None},
            "text_en": None, "text_shown": shown_text, "warnings": list(warnings), "trace": []}


SERVE = lambda k: {"result": "serve", "key": k, "reason": None}
REFUSE = lambda k, r="NO_EVIDENCE": {"result": "refuse", "key": k, "reason": r}


def test_card_check_values():
    exp = ["aaaaaaaa:0", "bbbbbbbb:0"]
    assert check.card_check("bbbbbbbb:0", False, exp) == "EXACT"
    assert check.card_check("aaaaaaaa:1", False, exp) == "NEIGHBOUR"
    assert check.card_check("cccccccc:0", False, exp) == "OTHER"
    assert check.card_check("aaaaaaaa:0", True, exp) == "REFUSED"
    assert check.card_check(None, False, exp) == "NONE"


def test_off_topic_refused_is_correct_by_harness():
    r = check.check_row(OFF_TOPIC, turn(REFUSE(None, "DENY"), kind="REFUSAL"), C)
    assert (r["verdict"], r["verdict_by"], r["needs_judge"]) == ("CORRECT", "harness", False)


def test_off_topic_answered_is_incorrect_by_harness():
    r = check.check_row(OFF_TOPIC, turn(SERVE("aaaaaaaa:0"), pick="aaaaaaaa:0"), C)
    assert r["verdict"] == "INCORRECT" and "aaaaaaaa:0" in r["reason"]


def test_gate_refusing_the_right_card_is_named():
    r = check.check_row(ANSWERABLE, turn(REFUSE("aaaaaaaa:0"), pick="aaaaaaaa:0", kind="REFUSAL"), C)
    assert (r["before"], r["after"], r["verdict"]) == ("EXACT", "REFUSED", "INCORRECT")
    assert r["reason"] == "gate refused the right card (NO_EVIDENCE)"


def test_refusal_after_a_wrong_pick_names_the_pick():
    r = check.check_row(ANSWERABLE, turn(REFUSE("cccccccc:0"), pick="cccccccc:0", kind="REFUSAL"), C)
    assert r["reason"] == "refused (NO_EVIDENCE); ranker picked cccccccc:0"


def test_expected_card_shown_verbatim_is_correct_by_harness():
    r = check.check_row(ANSWERABLE, turn(SERVE("aaaaaaaa:0"), pick="aaaaaaaa:0", kind="FALLBACK",
                                         mode="ON_DEVICE_DIRECT"), C)
    assert (r["shown"], r["verdict"], r["verdict_by"]) == ("card", "CORRECT", "harness")


def test_rewrite_of_the_expected_card_goes_to_the_judge():
    r = check.check_row(ANSWERABLE, turn(SERVE("aaaaaaaa:0"), pick="aaaaaaaa:0"), C)
    assert (r["shown"], r["verdict"], r["needs_judge"]) == ("rewrite", None, True)


def test_neighbour_shown_verbatim_goes_to_the_judge():
    r = check.check_row(ANSWERABLE, turn(SERVE("aaaaaaaa:1"), pick="aaaaaaaa:1", kind="FALLBACK",
                                         mode="ON_DEVICE_DIRECT"), C)
    assert (r["after"], r["needs_judge"]) == ("NEIGHBOUR", True)


def test_warned_row_gets_no_verdict():
    r = check.check_row(ANSWERABLE, turn(SERVE("aaaaaaaa:0"), pick="aaaaaaaa:0", warnings=["timeout"]), C)
    assert (r["verdict"], r["needs_judge"], r["warnings"]) == (None, False, ["timeout"])


def test_foreign_text_warning_when_shown_text_is_another_cards_body():
    t = turn(SERVE("aaaaaaaa:1"), pick="aaaaaaaa:1", kind="FALLBACK", shown_text="জীবাণুর আক্রমণে ডায়রিয়া হয়।")
    r = check.check_row(ANSWERABLE, t, C)
    assert "foreign_text" in r["warnings"] and r["verdict"] is None


def test_twin_body_is_not_foreign_text():
    t = turn(SERVE("aaaaaaaa:0"), pick="aaaaaaaa:0", kind="FALLBACK", shown_text="জীবাণুর আক্রমণে ডায়রিয়া হয়।")
    assert "foreign_text" not in check.check_row(ANSWERABLE, t, C)["warnings"]


def test_missing_turn_is_reported_not_guessed():
    r = check.check_row(ANSWERABLE, None, C)
    assert r["warnings"] == ["not_captured"] and r["verdict"] is None


def test_latest_turn_wins_and_reruns_are_counted():
    first, second = dict(turn(SERVE("cccccccc:0")), captured_at="1"), dict(turn(SERVE("aaaaaaaa:0")), captured_at="2")
    latest, reruns = check.latest_turns([first, second])
    assert latest["q1"]["gate"]["key"] == "aaaaaaaa:0" and reruns == {"q1": 1}


def test_run_checks_reports_an_asked_row_with_no_turn(tmp_path):
    from chat_eval import files
    files.write_json(tmp_path / "run.json", {"bank_ids": ["q1", "q2"]})
    files.write_jsonl(tmp_path / "turns.jsonl", [dict(turn(REFUSE(None, "DENY"), kind="REFUSAL"), id="q2")])
    rows = check.run_checks(tmp_path, [ANSWERABLE, OFF_TOPIC], C)
    assert [r["warnings"] for r in rows] == [["not_captured"], []]
