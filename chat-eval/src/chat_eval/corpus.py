"""Published module cards, read from either export format, keyed `family8:index`."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from chat_eval import files

_CHUNK_RE = re.compile(r"([0-9a-f]{8})[0-9a-f-]*:card:(\d+)")


def card_key(chunk_id: str) -> str | None:
    m = _CHUNK_RE.search(chunk_id or "")
    return f"{m.group(1)}:{m.group(2)}" if m else None


@dataclass(frozen=True)
class Card:
    key: str
    title: str
    body: str


def _text(value: Any) -> str:
    """Plain text from a string, a {bn,en} map (Bangla first) or a TipTap node list."""
    if value is None:
        return ""
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, list):
        return " ".join(t for t in (_text(v) for v in value) if t).strip()
    if isinstance(value, dict):
        if "bn" in value or "en" in value:
            return _text(value.get("bn")) or _text(value.get("en"))
        own = value.get("text") if isinstance(value.get("text"), str) else ""
        return " ".join(t for t in (own, _text(value.get("content"))) if t).strip()
    return ""


def _norm_title(title: str) -> str:
    return re.sub(r"\s+", "", title)


class Corpus:
    def __init__(self, cards: dict[str, Card], sha256: str):
        self.cards = cards
        self.sha256 = sha256
        self._by_title: dict[str, list[str]] = {}
        for key, card in cards.items():
            if _norm_title(card.title):
                self._by_title.setdefault(_norm_title(card.title), []).append(key)

    def get(self, key: str) -> Card | None:
        return self.cards.get(key)

    def with_twins(self, keys: list[str]) -> list[str]:
        """The keys plus every card whose title is identical, in another module family."""
        out = list(keys)
        for key in keys:
            card = self.cards.get(key)
            for twin in self._by_title.get(_norm_title(card.title), []) if card else []:
                if twin not in out:
                    out.append(twin)
        return out


def load(path: Path | str) -> Corpus:
    data = files.read_json(path)
    cards: dict[str, Card] = {}
    if isinstance(data, list):  # SDK audit format
        for module in data:
            fam = module["module_family_id"][:8]
            raw = module.get("cards_json") or "[]"
            for i, c in enumerate(json.loads(raw) if isinstance(raw, str) else raw):
                cards[f"{fam}:{i}"] = Card(f"{fam}:{i}", _text(c.get("title")), _text(c.get("body")))
    else:  # QA published_modules_lean format
        for module in data.get("modules", []):
            fam = module["module_family_id"][:8]
            raw = module.get("cards") or []
            # card_order is 1-based and matches the device's card array position; the
            # export's device_chunk_id is also 1-based, so it is not used as a key.
            for c in json.loads(raw) if isinstance(raw, str) else raw:
                key = f"{fam}:{int(c['card_order']) - 1}"
                cards[key] = Card(key, _text(c.get("title_bn") or c.get("title_en")),
                                  _text(c.get("body_bn") or c.get("body_en")))
    return Corpus(cards, files.sha256_file(path))
