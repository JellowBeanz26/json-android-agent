"""Prove the full perceive -> act -> perceive cycle with the device layer (no AI yet).

Boot the emulator, make sure the home screen is showing, then run:
    python try_action.py
"""
import time

from device import adb

TARGET = "Messages"

print("1) Reading the home screen...")
screen = adb.read_screen()

element = adb.find_by_text(screen, TARGET)
if element is None:
    print(f"   Could not find '{TARGET}'. Visible labels were:")
    print("   ", [e.label() for e in screen])
    raise SystemExit(1)

print(f"2) Found '{element.label()}' at {element.center} -> tapping it")
adb.tap_element(element)
time.sleep(2)  # give the app a moment to open

print("3) Reading the NEW screen (proves we can observe the result of our action)...")
after = adb.read_screen()
print(f"   Now showing {len(after)} elements. Top ones:")
for e in after[:12]:
    print(f"     - {e.cls.split('.')[-1]:16} '{e.label()}'")

print("\nPerceive -> act -> perceive cycle complete. This is the skeleton of the agent loop.")
