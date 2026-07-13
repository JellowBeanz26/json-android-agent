"""The Gemini 'brain' — wraps the google-genai client and one generate() call.

Kept deliberately thin so a different provider can later implement the same
generate(contents) -> response shape without touching the agent loop.
"""
import time

from google import genai
from google.genai import types

import config


class GeminiBrain:
    def __init__(self, tools, system_instruction, model=None):
        key = config.require_key()  # fail early with a clear message if .env isn't set
        if config.USE_VERTEX:
            # Vertex AI (Express mode) — uses the project's Vertex quota/credits.
            self.client = genai.Client(vertexai=True, api_key=key)
        else:
            self.client = genai.Client(api_key=key)
        self.model = model or config.GEMINI_MODEL
        self.tools = tools
        self.system_instruction = system_instruction

    def generate(self, contents):
        """One model turn: return Gemini's response, retrying transient 429/503 errors."""
        cfg = types.GenerateContentConfig(
            system_instruction=self.system_instruction,
            tools=self.tools,
            # We drive the loop ourselves, so turn OFF the SDK's auto-calling.
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
            temperature=0.0,
        )
        for attempt in range(5):
            try:
                return self.client.models.generate_content(
                    model=self.model, contents=contents, config=cfg
                )
            except Exception as exc:
                transient = any(s in str(exc) for s in ("429", "503", "RESOURCE_EXHAUSTED", "UNAVAILABLE"))
                if transient and attempt < 4:
                    time.sleep(2 ** attempt)  # back off: 1, 2, 4, 8s
                    continue
                raise
