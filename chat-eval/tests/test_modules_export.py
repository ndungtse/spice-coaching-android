import shutil
import sqlite3
from pathlib import Path

from chat_eval import corpus, modules_export

PREFS = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <set name="retired_family_ids">
        <string>bbbbbbbb-0000-0000-0000-000000000002</string>
    </set>
    <long name="last_sync" value="1" />
</map>"""


def make_db(folder: Path) -> sqlite3.Connection:
    """A module_cache table left in WAL mode with its rows still in the -wal file, as Room leaves it."""
    con = sqlite3.connect(folder / "microcoaching.db")
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA wal_autocheckpoint=0")
    con.execute("CREATE TABLE module_cache (module_id TEXT, module_family_id TEXT, version INTEGER, title_json TEXT,"
                " cards_json TEXT, search_metadata_json TEXT)")
    con.execute("INSERT INTO module_cache VALUES ('m1','aaaaaaaa-0000-0000-0000-000000000001',3,"
                "'{\"bn\":\"ডায়রিয়া\",\"en\":\"Diarrhoea\"}',"
                "'[{\"title\":{\"bn\":\"ডায়রিয়ার কারণ\"},\"body\":{\"bn\":\"জীবাণু।\"}}]','{}')")
    con.execute("INSERT INTO module_cache VALUES ('m2','bbbbbbbb-0000-0000-0000-000000000002',1,'{\"bn\":\"পুরনো\"}','[]','{}')")
    con.commit()
    return con


def test_retired_ids_are_read_from_the_sync_preferences():
    assert modules_export.retired_ids(PREFS) == {"bbbbbbbb-0000-0000-0000-000000000002"}
    assert modules_export.retired_ids("") == set()


def test_export_reads_rows_still_in_the_wal_file(tmp_path: Path):
    src = tmp_path / "device"; src.mkdir()
    con = make_db(src)  # kept open so the rows stay in the -wal file
    copy = tmp_path / "copy"; copy.mkdir()
    for name in ("microcoaching.db", "microcoaching.db-wal"):
        shutil.copy(src / name, copy / name)
    rows = modules_export.export_modules(copy / "microcoaching.db", modules_export.retired_ids(PREFS))
    con.close()
    assert [r["module_id"] for r in rows] == ["m1", "m2"]
    assert rows[0]["title_bn"] == "ডায়রিয়া" and rows[0]["retired"] is False and rows[1]["retired"] is True


def test_exported_file_loads_as_a_corpus(tmp_path: Path):
    con = make_db(tmp_path); con.close()
    out = tmp_path / "modules.json"
    modules_export.write(out, modules_export.export_modules(tmp_path / "microcoaching.db", set()))
    assert corpus.load(out).get("aaaaaaaa:0").title == "ডায়রিয়ার কারণ"


def test_a_run_is_scored_against_its_own_export_unless_a_corpus_is_given(tmp_path: Path):
    (tmp_path / "modules.json").write_text("[]", encoding="utf-8")
    assert modules_export.resolve_corpus(tmp_path, None) == tmp_path / "modules.json"
    assert modules_export.resolve_corpus(tmp_path, "other.json") == Path("other.json")
    empty = tmp_path / "empty"; empty.mkdir()
    import pytest
    with pytest.raises(FileNotFoundError, match="--corpus"):
        modules_export.resolve_corpus(empty, None)
