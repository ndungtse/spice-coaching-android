from pathlib import Path

from chat_eval import corpus

FIX = Path(__file__).parent / "fixtures"


def test_sdk_format_keys_titles_and_bodies():
    c = corpus.load(FIX / "corpus_sdk.json")
    assert c.get("aaaaaaaa:1").title == "ডায়রিয়ার লক্ষণ"
    assert c.get("aaaaaaaa:0").body == "জীবাণুর আক্রমণে ডায়রিয়া হয়।"


def test_tiptap_body_becomes_plain_text():
    c = corpus.load(FIX / "corpus_sdk.json")
    assert c.get("cccccccc:0").body == "নাভি শুকনো রাখুন।"


def test_lean_format_keys_by_card_order_not_its_one_based_chunk_id():
    c = corpus.load(FIX / "corpus_lean.json")
    assert c.get("aaaaaaaa:0").title == "ডায়রিয়ার কারণ"
    assert c.get("aaaaaaaa:1").title == "ডায়রিয়ার লক্ষণ"
    assert "dddddddd:0" not in c.cards


def test_twins_are_cards_with_an_identical_title_in_another_family():
    c = corpus.load(FIX / "corpus_sdk.json")
    assert c.with_twins(["aaaaaaaa:0"]) == ["aaaaaaaa:0", "bbbbbbbb:0"]
    assert c.with_twins(["aaaaaaaa:1"]) == ["aaaaaaaa:1"]


def test_card_key_from_chunk_id():
    assert corpus.card_key("6818eb87-4a2b-4b6b-9b3f-e2e9e1180d04:card:5") == "6818eb87:5"
    assert corpus.card_key("not a chunk") is None


def test_hash_is_recorded():
    assert len(corpus.load(FIX / "corpus_sdk.json").sha256) == 64
