"""Registry of LLM providers.

Detects which provider an API key belongs to (by key prefix, most-specific
first) and records how to list that provider's models.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Provider:
    id: str
    name: str
    prefixes: tuple[str, ...]  # key prefixes that identify this provider
    models_url: str            # GET endpoint that lists the provider's models
    auth: str                  # how to authenticate: "bearer" | "x-goog" | "x-api-key"


PROVIDERS: list[Provider] = [
    Provider("anthropic", "Anthropic (Claude)", ("sk-ant-",),
             "https://api.anthropic.com/v1/models", "x-api-key"),
    Provider("openai", "OpenAI", ("sk-proj-", "sk-svcacct-"),
             "https://api.openai.com/v1/models", "bearer"),
    Provider("google", "Google Gemini", ("AIza", "AQ."),
             "https://generativelanguage.googleapis.com/v1beta/models", "x-goog"),
    Provider("groq", "Groq", ("gsk_",),
             "https://api.groq.com/openai/v1/models", "bearer"),
    Provider("openrouter", "OpenRouter", ("sk-or-",),
             "https://openrouter.ai/api/v1/models", "bearer"),
    Provider("xai", "xAI (Grok)", ("xai-",),
             "https://api.x.ai/v1/models", "bearer"),
]


def detect(key: str) -> Provider | None:
    """Return the provider whose longest matching prefix fits the key, else None.

    Longest-prefix-first so e.g. `sk-or-` (OpenRouter) wins over a shorter stem.
    """
    key = key.strip()
    candidates = [(prefix, provider) for provider in PROVIDERS for prefix in provider.prefixes]
    candidates.sort(key=lambda c: len(c[0]), reverse=True)
    for prefix, provider in candidates:
        if key.startswith(prefix):
            return provider
    return None
