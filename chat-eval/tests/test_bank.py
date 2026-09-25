from pathlib import Path

import pytest

from chat_eval import bank, corpus, files

FIX = Path(__file__).parent / "fixtures"


def pack_row(card_id="aaaaaaaa-0000-0000-0000-000000000001:card:1", title="ডায়রিয়ার কারণ", qtype="exact_match"):
    return {"question": "ডায়রিয়ার কারণ কী?", "expected_card_id": card_id, "expected_card_title": title,
            "expected_module_family_id": "aaaaaaaa-0000-0000-0000-000000000001",
            "question_type": qtype, "expected_domain": "diarrhoea"}


def test_import_converts_one_based_order_and_adds_twins():
    c = corpus.load(FIX / "corpus_sdk.json")
    rows = bank.import_qa_pack([pack_row()], "qa-x", "uc2", c)
    assert rows == [{"id": "uc2_q001", "source": "qa-x", "lang": "bn", "question": "ডায়রিয়ার কারণ কী?",
                     "question_type": "exact_match", "domain": "diarrhoea",
                     "expect": {"cards": ["aaaaaaaa:0", "bbbbbbbb:0"]}}]


def test_import_refuses_a_title_mismatch():
    c = corpus.load(FIX / "corpus_sdk.json")
    with pytest.raises(bank.BankError, match="aaaaaaaa:0"):
        bank.import_qa_pack([pack_row(title="ভুল শিরোনাম")], "qa-x", "uc2", c)


def test_irrelevant_rows_become_refuse():
    c = corpus.load(FIX / "corpus_sdk.json")
    row = bank.import_qa_pack([pack_row(qtype="irrelevant", title="")], "qa-x", "uc2", c)[0]
    assert row["expect"] == {"refuse": True}


def test_load_rejects_duplicates_and_bad_keys(tmp_path: Path):
    p = tmp_path / "b.jsonl"
    files.write_jsonl(p, [
        {"id": "a", "question": "q", "expect": {"cards": ["aaaaaaaa:0"]}},
        {"id": "a", "question": "q", "expect": {"cards": ["AAAA:0"]}},
    ])
    with pytest.raises(bank.BankError) as e:
        bank.load(p)
    assert "duplicate id a" in str(e.value) and "bad key AAAA:0" in str(e.value)


def test_sdk_fixture_rows_convert():
    rows = bank.from_sdk_fixture([
        {"id": "uc2_q001", "source": "s", "lang": "bn", "question": "q", "acceptable": ["6818eb87:0"],
         "question_type": "exact_match", "domain": "anc", "native_query": "q", "qa_status": "ok"},
        {"id": "uc2_q096", "source": "s", "lang": "bn", "question": "q2", "acceptable": "refuse",
         "question_type": "irrelevant", "domain": None},
    ])
    assert rows[0]["expect"] == {"cards": ["6818eb87:0"]}
    assert rows[1]["expect"] == {"refuse": True}
    assert "qa_status" not in rows[0]
