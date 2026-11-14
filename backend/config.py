"""Local backend configuration, loaded from backend/.env when present."""

import os
from pathlib import Path

from dotenv import load_dotenv


load_dotenv(Path(__file__).resolve().with_name(".env"), override=False)


def get_api_key(name: str) -> str | None:
    """Return a configured secret without exposing it in application responses."""
    value = os.getenv(name, "").strip()
    return value or None
