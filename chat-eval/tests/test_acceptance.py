"""Checks against real captures that live outside git. Skipped when they are absent."""
import os
from pathlib import Path

import pytest

from chat_eval import bank, capture, check, corpus, files

REPO = Path(__file__).resolve().parents[2]
SDK_RUN = Path(os.environ.get("CHAT_EVAL_SDK_RUN", REPO / "ignored/__archived_23_09/_chat-audit/gate-v2-assisted"))
CORPUS = REPO / "sdk-android/src/test/resources/retrieval/audit_corpus_2026-08.json"


@pytest.mark.skipif(not (SDK_RUN / "logs").exists() or not CORPUS.exists(), reason="local capture not present")
def test_sdk_assisted_run_of_23_september_card_checks():
    rows = [r for r in bank.load(REPO / "chat-eval/banks/qa_bn.jsonl") if r["source"] == "qa-uc2-2026-09-17"]
    corp = corpus.load(CORPUS)
    results = []
    for r in rows:
        log = SDK_RUN / "logs" / f"{r['id']}.log"
        lines = log.read_text(encoding="utf-8").splitlines() if log.exists() else []
        t = capture.build_turn(r, lines, "ON_DEVICE_ASSISTED", "shown", 0)
        t["warnings"] = [w for w in t["warnings"] if w != "no_text"]  # the old driver kept no UI text
        results.append(check.check_row(r, t, corp))
    answerable = [c for c, r in zip(results, rows) if not bank.is_off_topic(r)]
    after = {v: sum(c["after"] == v for c in answerable) for v in ("EXACT", "NEIGHBOUR", "OTHER", "REFUSED")}
    assert after == {"EXACT": 78, "NEIGHBOUR": 5, "OTHER": 3, "REFUSED": 9}
    off = [c for c, r in zip(results, rows) if bank.is_off_topic(r)]
    assert sum(c["after"] == "REFUSED" for c in off) == 5
