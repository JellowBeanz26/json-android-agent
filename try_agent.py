"""Run the agent on a single task (for testing).

Usage:
    python try_agent.py                     # runs the default demo task
    python try_agent.py "open settings and turn on dark mode"
"""
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")  # let emojis print on Windows consoles
except Exception:
    pass

from agent import tools
from agent.loop import run_task
from device import adb
from llm.gemini import GeminiBrain

TASK = " ".join(sys.argv[1:]) or "Open the Calculator app and compute 25 times 4."

print(f"TASK: {TASK}\n" + "-" * 50)
brain = GeminiBrain(tools=tools.TOOLS, system_instruction=tools.SYSTEM)
run_task(TASK, brain, adb)
