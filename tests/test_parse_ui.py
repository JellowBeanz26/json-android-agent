"""Unit tests for the accessibility-tree parser (a pure function — no device needed)."""
from pathlib import Path

from device.adb import parse_ui

FIXTURE = (Path(__file__).parent / "fixtures" / "sample_screen.xml").read_text(encoding="utf-8")


def test_keeps_only_meaningful_elements():
    # The root FrameLayout and the empty spacer View have no text/desc and
    # aren't clickable, so they're filtered out. The other three remain.
    elements = parse_ui(FIXTURE)
    assert len(elements) == 3
    labels = [e.label() for e in elements]
    assert "Send" in labels
    assert "Dark theme" in labels
    assert "Just a label" in labels


def test_clickable_flag_is_parsed():
    send = next(e for e in parse_ui(FIXTURE) if e.text == "Send")
    assert send.clickable is True
    assert send.checkable is False


def test_toggle_state_is_captured():
    switch = next(e for e in parse_ui(FIXTURE) if e.content_desc == "Dark theme")
    assert switch.checkable is True
    assert switch.checked is True


def test_element_center_is_computed():
    send = next(e for e in parse_ui(FIXTURE) if e.text == "Send")
    # bounds [100,200][300,280] -> center (200, 240)
    assert send.center == (200, 240)


def test_indexes_are_sequential():
    elements = parse_ui(FIXTURE)
    assert [e.index for e in elements] == list(range(len(elements)))


def test_label_falls_back_to_content_desc():
    switch = next(e for e in parse_ui(FIXTURE) if e.content_desc == "Dark theme")
    assert switch.label() == "Dark theme"  # no text -> use content-desc
