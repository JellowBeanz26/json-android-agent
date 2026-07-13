"""Device layer — the agent's eyes and hands.

Everything that touches the phone/emulator goes through here, over ADB:
  - read_screen(): dump the current UI tree and return its interactive elements  ("eyes")
  - tap / type_text / swipe / press / open_app: perform actions                  ("hands")

Keeping all device I/O in one module means the rest of the app never has to know
about ADB. Later we can swap this backend without touching the agent logic.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from dataclasses import dataclass


def _find_adb() -> str:
    """Locate adb.exe: PATH first, then the Android SDK, then the Windows default."""
    on_path = shutil.which("adb")
    if on_path:
        return on_path
    candidates = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env)
        if root:
            candidates.append(os.path.join(root, "platform-tools", "adb.exe"))
    local = os.environ.get("LOCALAPPDATA", "")
    if local:
        candidates.append(os.path.join(local, "Android", "Sdk", "platform-tools", "adb.exe"))
    for path in candidates:
        if os.path.isfile(path):
            return path
    raise FileNotFoundError("adb not found. Add platform-tools to PATH or set ANDROID_HOME.")


ADB = _find_adb()


@dataclass
class Element:
    """One node from the screen's UI tree that a user could see or interact with."""

    index: int
    text: str
    resource_id: str
    cls: str
    content_desc: str
    clickable: bool
    checkable: bool  # is this a toggle/switch/checkbox?
    checked: bool  # ...and is it currently on?
    bounds: tuple[int, int, int, int]  # (left, top, right, bottom) in pixels

    @property
    def center(self) -> tuple[int, int]:
        left, top, right, bottom = self.bounds
        return (left + right) // 2, (top + bottom) // 2

    def label(self) -> str:
        """Best human-readable name for this element."""
        return self.text or self.content_desc or self.resource_id or self.cls


def _adb(*args: str, timeout: int = 30) -> str:
    """Run an adb command and return its stdout, always decoded as UTF-8.

    (Screens can contain symbols/Hebrew/emoji that the Windows locale codec
    can't decode, so we force UTF-8 and replace any stray bytes.)
    """
    result = subprocess.run(
        [ADB, *args], capture_output=True, encoding="utf-8", errors="replace", timeout=timeout
    )
    return result.stdout or ""


def _parse_bounds(raw: str) -> tuple[int, int, int, int]:
    """'[0,0][1080,2400]' -> (0, 0, 1080, 2400)."""
    nums = [int(n) for n in re.findall(r"-?\d+", raw)]
    return nums[0], nums[1], nums[2], nums[3]


def parse_ui(xml: str) -> list[Element]:
    """Turn a uiautomator XML dump into a flat list of meaningful elements."""
    root = ET.fromstring(xml)
    elements: list[Element] = []
    for node in root.iter("node"):
        clickable = node.get("clickable") == "true"
        text = node.get("text", "")
        desc = node.get("content-desc", "")
        # Keep anything a user could tap or read; skip empty structural nodes.
        if not (clickable or text or desc):
            continue
        elements.append(
            Element(
                index=len(elements),
                text=text,
                resource_id=node.get("resource-id", ""),
                cls=node.get("class", ""),
                content_desc=desc,
                clickable=clickable,
                checkable=node.get("checkable") == "true",
                checked=node.get("checked") == "true",
                bounds=_parse_bounds(node.get("bounds", "[0,0][0,0]")),
            )
        )
    return elements


def read_screen() -> list[Element]:
    """The 'eyes': dump the current screen and return its interactive elements."""
    _adb("shell", "uiautomator", "dump", "/sdcard/window_dump.xml")
    xml = _adb("exec-out", "cat", "/sdcard/window_dump.xml")
    return parse_ui(xml)


def find_by_text(elements: list[Element], needle: str) -> Element | None:
    """First element whose visible label contains `needle` (case-insensitive)."""
    needle = needle.lower()
    for element in elements:
        if needle in element.label().lower():
            return element
    return None


def tap(x: int, y: int) -> None:
    _adb("shell", "input", "tap", str(x), str(y))


def tap_element(element: Element) -> None:
    x, y = element.center
    tap(x, y)


def type_text(text: str) -> None:
    # `adb shell input text` needs spaces escaped as %s (basic v1 handling).
    _adb("shell", "input", "text", text.replace(" ", "%s"))


def swipe(x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> None:
    _adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration_ms))


def press(key: str) -> None:
    keymap = {"back": "KEYCODE_BACK", "home": "KEYCODE_HOME", "enter": "KEYCODE_ENTER"}
    _adb("shell", "input", "keyevent", keymap.get(key, key))


def open_app(package: str) -> None:
    """Launch an app by its package name (e.g. com.android.deskclock)."""
    _adb("shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1")


def screen_size() -> tuple[int, int]:
    """Return the screen size in pixels, e.g. (1080, 2400)."""
    out = _adb("shell", "wm", "size")  # "Physical size: 1080x2400"
    match = re.search(r"(\d+)\s*x\s*(\d+)", out)
    return (int(match.group(1)), int(match.group(2))) if match else (1080, 2400)


def swipe_dir(direction: str, duration_ms: int = 300) -> None:
    """Scroll the screen in a direction: 'up', 'down', 'left', or 'right'."""
    width, height = screen_size()
    cx, cy = width // 2, height // 2
    dx, dy = int(width * 0.3), int(height * 0.3)
    gestures = {
        "down": (cx, cy + dy, cx, cy - dy),   # reveal content below
        "up": (cx, cy - dy, cx, cy + dy),     # reveal content above
        "left": (cx + dx, cy, cx - dx, cy),
        "right": (cx - dx, cy, cx + dx, cy),
    }
    x1, y1, x2, y2 = gestures.get(direction, gestures["down"])
    swipe(x1, y1, x2, y2, duration_ms)


def list_packages() -> list[str]:
    """All installed app package names."""
    out = _adb("shell", "pm", "list", "packages")
    return [ln.replace("package:", "").strip() for ln in out.splitlines() if ln.startswith("package:")]


def launch_app(query: str) -> str:
    """Open an app by package name (has a dot) or by a fuzzy name match ('youtube')."""
    query = query.strip()
    if not query:
        return "No app name given."
    if "." in query:
        package = query
    else:
        needle = query.lower()
        matches = [p for p in list_packages() if needle in p.lower()]
        if not matches:
            return f"No installed app matching {query!r}."
        package = sorted(matches, key=len)[0]  # shortest match is usually the main app
    open_app(package)
    return f"Launched {package}"
