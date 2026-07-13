"""The tools the brain can call, plus how we execute them on the device."""
from google.genai import types

SYSTEM = """You are an agent that operates an Android phone to accomplish the user's task.

Each turn you receive the CURRENT SCREEN: a numbered list of on-screen elements, each \
with an index, type, and label. Toggle switches are marked [ON] or [OFF] so you can see \
their state — use this to confirm you actually changed a setting. To make progress, call exactly ONE tool:

- tap(index): tap the element with that index.
- type_text(text): type into the currently focused field (tap the field first).
- press(key): a navigation key — "back", "home", or "enter".
- swipe(direction): scroll "up", "down", "left", or "right" to reveal more.
- open_app(name): open an app by name, e.g. "Clock" or "YouTube".
- ask_user(question): ask the human. ALWAYS ask before anything irreversible \
(sending a message, deleting, paying, posting) or when you are unsure.
- done(summary): the task is finished — give a short summary or the answer.

How to work reliably AND efficiently (follow this):
1. PLAN: think through the SHORTEST path to the goal before acting.
2. USE SEARCH when available: many apps (Settings, YouTube, contacts...) have a search box — use it to jump STRAIGHT to what you need instead of browsing menus. It is usually far faster.
3. Be in the RIGHT app: if the task needs a specific app and you're not clearly in it, call open_app FIRST. Never assume an app is already open.
4. Look at the WHOLE screen list, then pick the SINGLE best action toward the goal. Don't tap around hoping — if unsure where something is, use search or scroll purposefully.
5. Start from a clean state when it matters (e.g. tap "AC" in a calculator).
6. Use the history: NEVER repeat an action that already didn't help.
7. After each action, check the updated screen; recover if it didn't do what you expected.
8. VERIFY the result on screen before finishing, then call done() with the answer.

Rules:
- Exactly ONE tool call per turn.
- Tap by element index from the CURRENT SCREEN; never guess coordinates.
- If what you need isn't visible, scroll or open the right app.
- Confirm with ask_user before any irreversible action (send, delete, pay, post).
- When the goal is achieved and verified, call done() with a clear summary or answer.
"""

_FUNCTIONS = [
    types.FunctionDeclaration(
        name="tap",
        description="Tap the on-screen element with the given index (from CURRENT SCREEN).",
        parameters_json_schema={
            "type": "object",
            "properties": {"index": {"type": "integer", "description": "Element index to tap."}},
            "required": ["index"],
        },
    ),
    types.FunctionDeclaration(
        name="type_text",
        description="Type text into the currently focused input field.",
        parameters_json_schema={
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    ),
    types.FunctionDeclaration(
        name="press",
        description="Press a navigation key: back, home, or enter.",
        parameters_json_schema={
            "type": "object",
            "properties": {"key": {"type": "string", "enum": ["back", "home", "enter"]}},
            "required": ["key"],
        },
    ),
    types.FunctionDeclaration(
        name="swipe",
        description="Scroll the screen to reveal more content.",
        parameters_json_schema={
            "type": "object",
            "properties": {"direction": {"type": "string", "enum": ["up", "down", "left", "right"]}},
            "required": ["direction"],
        },
    ),
    types.FunctionDeclaration(
        name="open_app",
        description="Open an app by its name, e.g. 'Clock' or 'YouTube'.",
        parameters_json_schema={
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"],
        },
    ),
    types.FunctionDeclaration(
        name="ask_user",
        description="Ask the human for confirmation or missing info. Always use before irreversible actions.",
        parameters_json_schema={
            "type": "object",
            "properties": {"question": {"type": "string"}},
            "required": ["question"],
        },
    ),
    types.FunctionDeclaration(
        name="done",
        description="The task is complete. Provide a short summary or the answer.",
        parameters_json_schema={
            "type": "object",
            "properties": {"summary": {"type": "string"}},
            "required": ["summary"],
        },
    ),
]

TOOLS = [types.Tool(function_declarations=_FUNCTIONS)]


def execute(name, args, device, elements):
    """Run one tool call on the device; return an observation dict for the model."""
    if name == "tap":
        index = int(args.get("index", -1))
        element = next((e for e in elements if e.index == index), None)
        if element is None:
            return {"result": f"No element with index {index} on this screen."}
        device.tap_element(element)
        return {"result": f"Tapped [{index}] {element.label()!r}"}
    if name == "type_text":
        text = args.get("text", "")
        device.type_text(text)
        return {"result": f"Typed {text!r}"}
    if name == "press":
        key = args.get("key", "back")
        device.press(key)
        return {"result": f"Pressed {key}"}
    if name == "swipe":
        direction = args.get("direction", "down")
        device.swipe_dir(direction)
        return {"result": f"Swiped {direction}"}
    if name == "open_app":
        return {"result": device.launch_app(args.get("name", ""))}
    return {"result": f"Unknown tool: {name}"}
