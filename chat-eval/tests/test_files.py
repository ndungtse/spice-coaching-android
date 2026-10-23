from pathlib import Path

from chat_eval import files


def test_jsonl_round_trip_keeps_bangla(tmp_path: Path):
    p = tmp_path / "t.jsonl"
    files.append_jsonl(p, {"q": "ডায়রিয়ার কারণ"})
    files.append_jsonl(p, {"q": "নাভির যত্ন"})
    assert [r["q"] for r in files.read_jsonl(p)] == ["ডায়রিয়ার কারণ", "নাভির যত্ন"]
    assert "ডায়রিয়ার" in p.read_text(encoding="utf-8")


def test_read_jsonl_accepts_bom_and_crlf(tmp_path: Path):
    p = tmp_path / "v.jsonl"
    p.write_bytes('﻿{"id":"a"}\r\n{"id":"b"}\r\n\r\n'.encode("utf-8"))
    assert [r["id"] for r in files.read_jsonl(p)] == ["a", "b"]


def test_load_settings_without_local(tmp_path: Path):
    (tmp_path / "settings.toml").write_text('[capture]\nturn_timeout_s = 5\n', encoding="utf-8")
    s = files.load_settings(tmp_path)
    assert s["capture"]["turn_timeout_s"] == 5
    assert s["local"] == {}
