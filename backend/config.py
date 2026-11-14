"""Local backend configuration, loaded from backend/.env when present."""

import os
from pathlib import Path

from dotenv import load_dotenv


load_dotenv(Path(__file__).resolve().with_name(".env"), override=False)


def get_setting(name: str) -> str | None:
    """Return a configured setting without exposing it in application responses."""
    value = os.getenv(name, "").strip()
    return value or None


def get_api_key(name: str) -> str | None:
    """Return a configured API key without exposing it in application responses."""
    return get_setting(name)
