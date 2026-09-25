"""Reads one chat turn out of the app's log: which block belongs to a question, and what
every pipeline stage recorded in it."""
from __future__ import annotations

import re

from chat_eval.corpus import card_key

# `-v time` ("I/ChatTrace( 8084): ") and the default threadtime ("8084 8084 I ChatTrace: ").
_PREFIX = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+(?:[VDIWEF]/[\w.]+\(\s*\d+\)|\d+\s+\d+\s+[VDIWEF]\s+[\w.]+)\s*:\s?")
_TURN = re.compile(r'──── turn ──── mode=(\S+).*?q="(.*)"')
_FUSED = re.compile(r"FUSED n=\d+ order=\[(.*)\]")
_FUSED_ENTRY = re.compile(r"(\S+) bm25=(?:-|(\d+)/([\d.]+)) dense=(?:-|(\d+)/([\d.]+))")
_RANK = re.compile(r"RANK pick=(\S+) fused=(\d+) band=\[([^\]]*)\]")
_GATE = re.compile(r"GATE (serve|refuse) (\S+)(?: reason=(\w+))?")
_POST = re.compile(r"POST groundedness=(\S+)(?: floor=(\S+))? validator=(\S+) translation=(\S+) shown=(\S+)")
_OUTCOME = re.compile(r"OUTCOME=(\w+)(?: (?:kind|key)=(\w+))?")
_OLD_SERVE = re.compile(r"SERVE-DECISION serve (\S+)")
_OLD_REFUSE = re.compile(r"SERVE-DECISION refuse reason=(\w+)(?: (\S+))?")
_LLM_RAW = re.compile(r'LLM raw: .*?text="(.*)"$')
_CRASH = ("Fatal signal", "has died", "ndk_translation")


def strip(line: str) -> str:
    return _PREFIX.sub("", line, count=1)


def _question_matches(preview: str, question: str) -> bool:
    if preview == question:
        return True
    if "…(" in preview:
        return question.startswith(preview.split("…(", 1)[0])
    return False


def anchored_block(lines: list[str], question: str) -> list[str] | None:
    """Lines from the latest turn line quoting `question` up to the next turn line."""
    starts = [i for i, ln in enumerate(lines) if (m := _TURN.search(ln)) and _question_matches(m.group(2), question)]
    if not starts:
        return None
    start = starts[-1]
    end = next((i for i in range(start + 1, len(lines)) if _TURN.search(lines[i])), len(lines))
    return lines[start:end]


def parse_block(block: list[str]) -> dict:
    out: dict = {"mode": None, "fused": None, "pick": None, "gate": None, "post": None,
                 "outcome": None, "text_en": None, "crash": None}
    for raw in block:
        line = strip(raw)
        if any(marker in raw for marker in _CRASH) and out["crash"] is None:
            out["crash"] = line
        if (m := _TURN.search(line)):
            out["mode"] = m.group(1)
        elif (m := _FUSED.search(line)):
            out["fused"] = [{"key": e.group(1),
                             "bm25_rank": int(e.group(2)) if e.group(2) else None,
                             "bm25_score": float(e.group(3)) if e.group(3) else None,
                             "dense_rank": int(e.group(4)) if e.group(4) else None,
                             "cos": float(e.group(5)) if e.group(5) else None}
                            for e in _FUSED_ENTRY.finditer(m.group(1))]
        elif (m := _RANK.search(line)):
            out["pick"] = {"key": m.group(1), "fused_rank": int(m.group(2)),
                           "band": [k for k in m.group(3).split(",") if k], "evidence": line.split(" | ", 1)[-1]}
        elif (m := _GATE.search(line)):
            key = None if m.group(2) == "-" else m.group(2)
            out["gate"] = {"result": m.group(1), "key": key, "reason": m.group(3)}
        elif (m := _OLD_SERVE.search(line)):
            key = card_key(m.group(1))
            out["pick"] = {"key": key, "fused_rank": None, "band": [], "evidence": line}
            out["gate"] = {"result": "serve", "key": key, "reason": None}
        elif (m := _OLD_REFUSE.search(line)):
            key = card_key(m.group(2) or "")
            out["pick"] = {"key": key, "fused_rank": None, "band": [], "evidence": line} if key else None
            out["gate"] = {"result": "refuse", "key": key, "reason": m.group(1)}
        elif "L0 deny-list" in line:
            out["gate"] = {"result": "refuse", "key": None, "reason": "DENY"}
        elif (m := _POST.search(line)):
            out["post"] = {"groundedness": m.group(1), "floor": m.group(2), "validator": m.group(3),
                           "translation": m.group(4), "shown": m.group(5)}
        elif (m := _LLM_RAW.search(line)):
            out["text_en"] = m.group(1)
        elif (m := _OUTCOME.search(line)) and out["outcome"] is None:
            out["outcome"] = {"kind": m.group(1), "detail": m.group(2)}
    # A refusal outcome with no gate line was refused before the gate ran: the deny-list,
    # whose own line is logged under a tag a capture may not keep.
    if out["gate"] is None and out["outcome"] and out["outcome"]["kind"] == "REFUSAL":
        detail = out["outcome"]["detail"]
        out["gate"] = {"result": "refuse", "key": None, "reason": "DENY" if detail == "refused_scope" else detail}
    return out
