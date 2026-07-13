"""Tests for API-key provider detection (pure functions — no network)."""
from apikeys.providers import detect


def test_detects_anthropic():
    assert detect("sk-ant-api03-abc123").id == "anthropic"


def test_detects_google_aiza_and_aq():
    assert detect("AIzaSyABC123").id == "google"
    assert detect("AQ.demo_fake_test").id == "google"


def test_detects_groq():
    assert detect("gsk_abc123").id == "groq"


def test_detects_openai_project_key():
    assert detect("sk-proj-abc123").id == "openai"


def test_most_specific_prefix_wins():
    # sk-or- (OpenRouter) must win over any shorter stem
    assert detect("sk-or-v1-xyz").id == "openrouter"


def test_detects_xai():
    assert detect("xai-abc123").id == "xai"


def test_unknown_key_returns_none():
    assert detect("totally-random-key") is None
