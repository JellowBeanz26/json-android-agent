"""Local store for several API keys, with one marked "active".

Kept in ``~/.json_agent/keys.json`` (in the user's home directory, never in the
repo). This is a simple first version — for production, an OS keyring is safer.
"""
from __future__ import annotations

import json
from pathlib import Path

from apikeys.providers import detect

STORE_PATH = Path.home() / ".json_agent" / "keys.json"


def _load() -> dict:
    if STORE_PATH.exists():
        return json.loads(STORE_PATH.read_text(encoding="utf-8"))
    return {"keys": [], "active": None}


def _save(data: dict) -> None:
    STORE_PATH.parent.mkdir(parents=True, exist_ok=True)
    STORE_PATH.write_text(json.dumps(data, indent=2), encoding="utf-8")


def add_key(key: str, label: str = "") -> str:
    """Store a key (provider auto-detected). Returns the detected provider id."""
    provider = detect(key)
    provider_id = provider.id if provider else "unknown"
    data = _load()
    data["keys"].append({"key": key, "provider": provider_id, "label": label or provider_id})
    if data["active"] is None:
        data["active"] = len(data["keys"]) - 1
    _save(data)
    return provider_id


def list_keys() -> list[dict]:
    return _load()["keys"]


def set_active(index: int) -> None:
    data = _load()
    if 0 <= index < len(data["keys"]):
        data["active"] = index
        _save(data)


def active_key() -> dict | None:
    data = _load()
    if data["active"] is None:
        return None
    return data["keys"][data["active"]]


def active_index() -> int | None:
    return _load()["active"]


def masked(key: str) -> str:
    """A safe-to-display version of a key (never print a full secret)."""
    return f"{key[:6]}…{key[-4:]}" if len(key) > 12 else "…"
