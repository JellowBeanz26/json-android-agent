"""Quick manual check of the device layer against a running emulator/phone.

Boot the emulator first, then run:  python try_device.py
"""
from device import adb

print(f"Using adb at: {adb.ADB}\n")
print("Reading the screen (the agent's 'eyes')...\n")

elements = adb.read_screen()
print(f"Found {len(elements)} meaningful elements. Showing up to 25:\n")
for el in elements[:25]:
    short_cls = el.cls.split(".")[-1]
    print(
        f"  [{el.index:2}] {short_cls:18} clickable={str(el.clickable):5} "
        f"center={el.center}  '{el.label()}'"
    )
