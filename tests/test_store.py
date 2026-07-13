"""Tests for the key store's pure helpers (no disk writes)."""
from apikeys import store


def test_masked_hides_the_middle_of_a_key():
    assert store.masked("sk-ant-api03-1234567890") == "sk-ant…7890"


def test_masked_short_value_is_fully_hidden():
    assert store.masked("short") == "…"
