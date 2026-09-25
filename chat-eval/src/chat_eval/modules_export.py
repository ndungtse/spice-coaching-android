"""The modules the device held during a run, exported from the SDK's database as a corpus.

The SDK keeps synced modules in `microcoaching.db`, table `module_cache`, and the family ids
the backend retired in its sync preferences. A debug build of the host lets `run-as` read
both, so every run can be scored against exactly the cards the device had.
"""
from __future__ import annotations

import json
import re
import sqlite3
import tempfile
from pathlib import Path

from chat_eval import device, files

DB = "microcoaching.db"
DB_FILES = (DB, f"{DB}-wal", f"{DB}-shm")
SYNC_PREFS = "shared_prefs/micro_coaching_sync.xml"


def retired_ids(prefs_xml: str) -> set[str]:
    block = re.search(r'<set name="retired_family_ids">(.*?)</set>', prefs_xml or "", re.S)
    return set(re.findall(r"<string>([^<]+)</string>", block.group(1))) if block else set()


def export_modules(db_path: Path, retired: set[str]) -> list[dict]:
    """Rows of module_cache in the corpus format `corpus.load` reads, each flagged `retired`."""
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        rows = con.execute("SELECT module_id, module_family_id, version, title_json, cards_json, "
                           "search_metadata_json FROM module_cache ORDER BY module_family_id, version").fetchall()
    finally:
        con.close()
    out = []
    for module_id, family, version, title_json, cards_json, metadata_json in rows:
        title = json.loads(title_json or "{}")
        out.append({"module_id": module_id, "module_family_id": family, "version": version,
                    "title_bn": title.get("bn") or title.get("en") or "", "title_json": title_json,
                    "cards_json": cards_json or "[]", "search_metadata_json": metadata_json or "{}",
                    "retired": family in retired})
    return out


def write(path: Path, modules: list[dict]) -> str:
    files.write_json(path, modules)
    return files.sha256_file(path)


def _run_as_cat(serial: str, package: str, rel: str) -> bytes | None:
    import subprocess
    r = subprocess.run([device.tool("adb"), "-s", serial, "exec-out", "run-as", package, "cat", rel],
                       capture_output=True, timeout=120)
    if r.returncode != 0 or r.stdout.startswith(b"run-as:") or b"No such file" in r.stdout[:200]:
        return None
    return r.stdout


def snapshot(serial: str, package: str, out: Path) -> str | None:
    """Copies the database off the device and writes `out`; returns its hash, or None when the
    build is not debuggable or the database is missing."""
    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        for name in DB_FILES:
            data = _run_as_cat(serial, package, f"databases/{name}")
            if data is not None:
                (tmpdir / name).write_bytes(data)
        if not (tmpdir / DB).exists():
            return None
        prefs = _run_as_cat(serial, package, SYNC_PREFS) or b""
        modules = export_modules(tmpdir / DB, retired_ids(prefs.decode("utf-8", "replace")))
    return write(out, modules)


def resolve_corpus(run_dir: Path, given: str | None) -> Path:
    """The corpus a run is scored against: the one given, else the run's own export."""
    if given:
        return Path(given)
    own = run_dir / "modules.json"
    if not own.exists():
        raise FileNotFoundError(f"{own} does not exist; pass --corpus <path>")
    return own
