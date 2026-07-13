"""A small CLI to manage API keys across providers.

Run with:  python -m apikeys

Commands: keys | add | use <n> | models | quit
"""
from __future__ import annotations

import sys

from apikeys import store
from apikeys.models import fetch_models
from apikeys.providers import detect

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

_TAG = {True: "FREE", False: "paid", None: "tier?"}


def show_keys() -> None:
    keys = store.list_keys()
    active = store.active_index()
    if not keys:
        print("  (no keys yet — use 'add')")
        return
    for i, entry in enumerate(keys):
        mark = "*" if i == active else " "
        print(f" {mark}[{i}] {entry['label']:14} {entry['provider']:11} {store.masked(entry['key'])}")


def add() -> None:
    key = input("Paste an API key: ").strip()
    if not key:
        return
    provider = detect(key)
    name = provider.name if provider else "unknown provider"
    label = input(f"Detected: {name}. Label (optional): ").strip()
    store.add_key(key, label)
    print(f"Added ({name}).")


def show_models() -> None:
    entry = store.active_key()
    if entry is None:
        print("No active key — use 'add' first.")
        return
    print(f"Fetching models for {entry['label']} ({entry['provider']})...")
    try:
        models = fetch_models(entry)
    except Exception as exc:
        print(f"Could not list models: {exc}")
        return
    for m in models:
        print(f"  [{_TAG[m['free']]:5}] {m['id']}")


def main() -> None:
    print("API key manager. Commands: keys | add | use <n> | models | quit")
    while True:
        try:
            parts = input("\nkeys> ").strip().split()
        except EOFError:
            break
        if not parts:
            continue
        cmd = parts[0]
        if cmd in ("quit", "exit"):
            break
        elif cmd == "keys":
            show_keys()
        elif cmd == "add":
            add()
        elif cmd == "use" and len(parts) > 1 and parts[1].isdigit():
            store.set_active(int(parts[1]))
            show_keys()
        elif cmd == "models":
            show_models()
        else:
            print("Commands: keys | add | use <n> | models | quit")


if __name__ == "__main__":
    main()
