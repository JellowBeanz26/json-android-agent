"""The agent loop: observe the screen -> ask the brain -> do one action -> repeat."""
import os
import time

from google.genai import types

from agent import tools

MAX_STEPS = 15
DEBUG = os.environ.get("JSON_DEBUG", "").lower() in ("1", "true", "yes")


def _render_screen(elements) -> str:
    if not elements:
        return "(no interactive elements found)"
    lines = []
    for el in elements:
        cls = el.cls.split(".")[-1]
        kind = "clickable" if el.clickable else "info"
        state = (" [ON]" if el.checked else " [OFF]") if el.checkable else ""
        lines.append(f'[{el.index}] {cls} "{el.label()}" ({kind}){state}')
    return "\n".join(lines)


def _extract(response):
    """Return (function_calls, text) from a Gemini response."""
    calls, text = [], []
    if not response.candidates:
        return calls, ""
    content = response.candidates[0].content
    for part in (content.parts or []):
        if getattr(part, "function_call", None):
            calls.append(part.function_call)
        elif getattr(part, "text", None):
            text.append(part.text)
    return calls, " ".join(text).strip()


def run_task(goal, brain, device):
    """Drive the phone until the task is done or we hit the step limit."""
    elements = device.read_screen()
    if DEBUG:
        print(f"--- screen at START ---\n{_render_screen(elements)}\n-----------------------")
    contents = [
        types.Content(
            role="user",
            parts=[types.Part.from_text(
                text=f"TASK: {goal}\n\nCURRENT SCREEN:\n{_render_screen(elements)}"
            )],
        )
    ]

    for step in range(1, MAX_STEPS + 1):
        response = brain.generate(contents)
        calls, text = _extract(response)

        if response.candidates:
            contents.append(response.candidates[0].content)

        usage = getattr(response, "usage_metadata", None)
        tokens = usage.total_token_count if usage else "?"

        if not calls:
            print(f"[step {step}] (no action) {text}  ~{tokens} tok")
            contents.append(types.Content(
                role="user",
                parts=[types.Part.from_text(text="Call exactly one tool, or done() if finished.")],
            ))
            continue

        call = calls[0]
        name = call.name
        args = dict(call.args) if call.args else {}
        note = f" — {text}" if text else ""
        print(f"[step {step}] {name}({args}){note}  ~{tokens} tok")

        if name == "done":
            print(f"\n✅ DONE: {args.get('summary', '')}")
            return

        if name == "ask_user":
            answer = input(f"\n🤖 {args.get('question', '(input needed)')}\n> ")
            observation = {"user_answer": answer}
        else:
            observation = tools.execute(name, args, device, elements)

        time.sleep(1.0)  # let the screen settle before observing the result
        elements = device.read_screen()
        observation["screen"] = _render_screen(elements)
        contents.append(types.Content(
            role="user",
            parts=[types.Part.from_function_response(name=name, response=observation)],
        ))

    print("\n⚠️ Reached the step limit without finishing.")
