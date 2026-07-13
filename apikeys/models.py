"""List a provider's models for a given key, and label each one free vs paid.

`parse_models` is a pure function (unit-tested); `fetch_models` does the live
HTTP request against the provider's endpoint.
"""
from __future__ import annotations

import json
import urllib.request

from apikeys.providers import PROVIDERS, Provider


def _provider(provider_id: str) -> Provider | None:
    return next((p for p in PROVIDERS if p.id == provider_id), None)


def _is_zero(value) -> bool:
    try:
        return float(value) == 0.0
    except (TypeError, ValueError):
        return False


def parse_models(provider_id: str, data: dict) -> list[dict]:
    """Turn a provider's raw /models response into a list of {id, free}.

    `free` is True/False when we can tell, or None when it depends on the account.
    """
    if provider_id == "openrouter":
        models = []
        for m in data.get("data", []):
            pricing = m.get("pricing", {})
            free = _is_zero(pricing.get("prompt")) and _is_zero(pricing.get("completion"))
            models.append({"id": m.get("id", ""), "free": free})
        return models

    if provider_id == "google":
        models = []
        for m in data.get("models", []):
            model_id = m.get("name", "").replace("models/", "")
            # Flash / embedding families have a free tier; Pro/Veo/Imagen are paid-only.
            free = "flash" in model_id or "embedding" in model_id
            models.append({"id": model_id, "free": free})
        return models

    if provider_id == "anthropic":
        return [{"id": m.get("id", ""), "free": False} for m in data.get("data", [])]

    # OpenAI / Groq / xAI expose an OpenAI-style data[].id list without pricing.
    return [{"id": m.get("id", ""), "free": None} for m in data.get("data", [])]


def fetch_models(entry: dict) -> list[dict]:
    """Live-list the models a stored key can access (GET the provider's endpoint)."""
    provider = _provider(entry.get("provider", ""))
    if provider is None:
        return []
    key = entry["key"]
    request = urllib.request.Request(provider.models_url)
    if provider.auth == "bearer":
        request.add_header("Authorization", f"Bearer {key}")
    elif provider.auth == "x-goog":
        request.add_header("x-goog-api-key", key)
    elif provider.auth == "x-api-key":
        request.add_header("x-api-key", key)
        request.add_header("anthropic-version", "2023-06-01")
    with urllib.request.urlopen(request, timeout=15) as response:
        data = json.loads(response.read())
    return parse_models(entry["provider"], data)
