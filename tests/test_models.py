"""Tests for model listing + free/paid classification (pure parse, no network)."""
from apikeys.models import parse_models


def test_openrouter_splits_free_and_paid_by_price():
    data = {"data": [
        {"id": "meta/model:free", "pricing": {"prompt": "0", "completion": "0"}},
        {"id": "openai/gpt", "pricing": {"prompt": "0.00001", "completion": "0.00003"}},
    ]}
    by_id = {m["id"]: m["free"] for m in parse_models("openrouter", data)}
    assert by_id["meta/model:free"] is True
    assert by_id["openai/gpt"] is False


def test_google_flash_has_free_tier_pro_does_not():
    data = {"models": [
        {"name": "models/gemini-2.5-flash"},
        {"name": "models/gemini-2.5-pro"},
    ]}
    by_id = {m["id"]: m["free"] for m in parse_models("google", data)}
    assert by_id["gemini-2.5-flash"] is True
    assert by_id["gemini-2.5-pro"] is False


def test_openai_style_returns_ids_with_unknown_tier():
    data = {"data": [{"id": "gpt-4o"}, {"id": "gpt-4o-mini"}]}
    models = parse_models("openai", data)
    assert {m["id"] for m in models} == {"gpt-4o", "gpt-4o-mini"}
    assert all(m["free"] is None for m in models)


def test_anthropic_models_are_paid():
    data = {"data": [{"id": "claude-sonnet-5"}]}
    assert parse_models("anthropic", data) == [{"id": "claude-sonnet-5", "free": False}]
