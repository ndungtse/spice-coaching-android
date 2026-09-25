"""UTF-8 file helpers shared by every stage, and the settings loader."""
from __future__ import annotations

import hashlib
import json
import os
import tomllib
from pathlib import Path
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parents[2]


def read_json(path: Path | str) -> Any:
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write_json(path: Path | str, obj: Any) -> None:
    Path(path).write_text(json.dumps(obj, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def read_jsonl(path: Path | str) -> list[dict]:
    text = Path(path).read_text(encoding="utf-8-sig")
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def write_jsonl(path: Path | str, rows: list[dict]) -> None:
    Path(path).write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")


def append_jsonl(path: Path | str, row: dict) -> None:
    """Appends one line and flushes it to disk, so a crash keeps every finished turn."""
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")
        f.flush()
        os.fsync(f.fileno())


def sha256_file(path: Path | str) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_settings(root: Path = PROJECT_ROOT) -> dict:
    settings = tomllib.loads((root / "settings.toml").read_text(encoding="utf-8"))
    local = root / "local.toml"
    settings["local"] = tomllib.loads(local.read_text(encoding="utf-8")) if local.exists() else {}
    return settings
