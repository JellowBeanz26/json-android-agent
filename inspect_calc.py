"""Inspect what Json 'sees' on the calculator screen (debugging helper)."""
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

from device import adb

print("Calculator-like packages installed:")
found = [p for p in adb.list_packages() if "calc" in p.lower()]
for p in found:
    print("  ", p)
if not found:
    print("  (none found!)")

print("\nLaunching 'calculator'...")
print(" ->", adb.launch_app("calculator"))
time.sleep(2.5)

els = adb.read_screen()
print(f"\n{len(els)} elements on the current screen:")
for e in els:
    print(f"  [{e.index:2}] {e.cls.split('.')[-1]:16} clickable={str(e.clickable):5} {e.label()!r}")
