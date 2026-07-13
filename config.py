"""Loads configuration (API key, model) from a local .env file.

Copy .env.example to .env and put your real key there. .env is gitignored,
so your secret never gets committed.
"""
import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv(Path(__file__).parent / ".env")

GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "")
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")
# Use Vertex AI (uses the project's Vertex quota/credits) instead of the free Gemini API.
USE_VERTEX = os.environ.get("USE_VERTEX", "").lower() in ("1", "true", "yes")


def require_key() -> str:
    """Return the Gemini key, or raise a clear error if it's missing."""
    if not GEMINI_API_KEY or GEMINI_API_KEY == "your-gemini-api-key-here":
        raise RuntimeError(
            "GEMINI_API_KEY is missing. Copy .env.example to .env and paste your key."
        )
    return GEMINI_API_KEY
