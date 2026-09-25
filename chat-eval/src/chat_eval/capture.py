"""Asks every bank question on the device and appends one turn record per question."""
from __future__ import annotations

import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from chat_eval import app, device, files, trace

MODES = {"assisted": "ON_DEVICE_ASSISTED", "direct": "ON_DEVICE_DIRECT"}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def fresh_lines(before: list[str], after: list[str]) -> list[str]:
    """Log lines that were not in the buffer before the send. Each logcat line carries a
    millisecond timestamp, so an earlier turn for the same question is never among them."""
    seen = set(before)
    return [ln for ln in after if ln not in seen]


def build_turn(row: dict, lines: list[str], run_mode: str, text_shown: str, latency_ms: int) -> dict:
    block = trace.anchored_block(lines, row["question"])
    parsed = trace.parse_block(block) if block else trace.parse_block([])
    warnings = []
    if block is None:
        warnings.append("no_anchor")
    else:
        if parsed["outcome"] is None:
            warnings.append("timeout")
        if parsed["mode"] != run_mode:
            warnings.append("mode_mismatch")
        if parsed["crash"]:
            warnings.append("crash")
        served = (parsed["gate"] or {}).get("result") == "serve"
        if served and parsed["outcome"] and not text_shown.strip():
            warnings.append("no_text")
    return {"id": row["id"], "question": row["question"], "anchored": block is not None, **parsed,
            "text_shown": text_shown, "latency_ms": latency_ms, "warnings": warnings,
            "trace": [trace.strip(ln) for ln in (block or [])], "captured_at": _now()}


def _repo_state() -> dict:
    def git(*a: str) -> str:
        r = subprocess.run(["git", *a], capture_output=True, text=True, encoding="utf-8", cwd=files.PROJECT_ROOT)
        return r.stdout.strip()
    return {"commit": git("rev-parse", "HEAD"), "dirty": bool(git("status", "--porcelain"))}


def run(settings: dict, serial: str, bank_rows: list[dict], source: str, mode: str, out_dir: Path,
        resume: bool, only: set[str] | None, allow_mode_drift: bool = False) -> Path:
    cap = settings["capture"]
    run_mode = MODES[mode]
    rows = [r for r in bank_rows if r["source"] == source and (only is None or r["id"] in only)]
    out_dir.mkdir(parents=True, exist_ok=True)
    turns_path = out_dir / "turns.jsonl"
    done = {t["id"] for t in files.read_jsonl(turns_path)} if (resume and turns_path.exists()) else set()
    header_path = out_dir / "run.json"
    header = files.read_json(header_path) if header_path.exists() else {
        "mode": run_mode, "source": source, "bank_ids": [r["id"] for r in rows],
        "bank_sha256": files.sha256_file(files.PROJECT_ROOT / "banks" / "qa_bn.jsonl"),
        "device": device.probe(serial), "apk": device.apk_version(serial, cap["package"]),
        "airplane_on": device.adb(serial, "shell", "settings", "get", "global", "airplane_mode_on").stdout.strip() in {"1", "true"},
        "repo": _repo_state(), "capture": cap, "started_at": _now()}
    files.write_json(header_path, header)
    driver = app.connect(settings, serial)
    crashes = 0
    try:
        for row in rows:
            if row["id"] in done and only is None:
                continue
            if not app.is_chat_ready(driver):
                for _ in range(cap["screen_recover_tries"]):
                    driver.press_keycode(4)
                    time.sleep(1)
                    if app.is_chat_ready(driver):
                        break
                app.open_assistant(driver, cap["package"], timeout_s=60)
            before = app.visible_texts(driver)
            log_before = device.logcat(serial, cap["package"], cap["logcat_tags"])
            sent = time.time()
            app.send(driver, row["question"])
            lines: list[str] = []
            while time.time() - sent < cap["turn_timeout_s"]:
                time.sleep(cap["poll_interval_s"])
                lines = fresh_lines(log_before, device.logcat(serial, cap["package"], cap["logcat_tags"]))
                block = trace.anchored_block(lines, row["question"])
                if block and trace.parse_block(block)["outcome"]:
                    break
            latency = int((time.time() - sent) * 1000)
            time.sleep(1.5)  # the answer bubble renders just after the outcome is logged
            shown = app.newest_answer(driver, before, row["question"])
            t = build_turn(row, lines, run_mode, shown, latency)
            files.append_jsonl(turns_path, t)
            print(f"{row['id']}: gate={(t['gate'] or {}).get('result')} {(t['gate'] or {}).get('key')} "
                  f"outcome={(t['outcome'] or {}).get('kind')} warnings={t['warnings']}")
            if "mode_mismatch" in t["warnings"] and not allow_mode_drift:
                raise RuntimeError(f"{row['id']} ran in {t['mode']}, not {run_mode}; stopping the run")
            crashes = crashes + 1 if "crash" in t["warnings"] else 0
            if crashes >= cap["consecutive_crash_stop"]:
                raise RuntimeError(f"{crashes} consecutive crashes; stopping the run")
    finally:
        driver.quit()
        files.write_json(header_path, files.read_json(header_path) | {"ended_at": _now()})
    return out_dir
