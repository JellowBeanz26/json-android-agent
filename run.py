"""Entry point: chat with your phone agent.

Boot the emulator (or plug in the phone), then run:
    python run.py
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


def main():
    brain = GeminiBrain(tools=tools.TOOLS, system_instruction=tools.SYSTEM)
    print("📱 Phone Agent ready. Describe a task, or type 'quit'.\n")
    while True:
        goal = input("Task> ").strip()
        if goal.lower() in ("quit", "exit", ""):
            print("Bye!")
            break
        try:
            run_task(goal, brain, adb)
        except Exception as exc:  # keep the chat alive if one task errors
            print(f"⚠️ Error: {exc}")


if __name__ == "__main__":
    main()
