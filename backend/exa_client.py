import asyncio
import hashlib
import math
from urllib.parse import urlparse

from exa_py import AsyncExa

from config import get_api_key


DEFAULT_TIMEOUT_SECONDS = 8.0


class ExaConfigurationError(RuntimeError):
    """Raised when live Exa retrieval cannot run because configuration is missing."""


class ExaSearchError(RuntimeError):
    """A safe, actionable Exa retrieval failure without raw provider response data."""

    def __init__(
        self,
        message: str,
        *,
        kind: str = "provider_error",
        retryable: bool = False,
        status_code: int | None = None,
    ) -> None:
        super().__init__(message)
        self.kind = kind
        self.retryable = retryable
        self.status_code = status_code


def _field(result, name, default=None):
    if isinstance(result, dict):
        return result.get(name, default)
    return getattr(result, name, default)


def _status_code(error: Exception) -> int | None:
    status = _field(error, "status_code")
    if status is None:
        status = _field(_field(error, "response"), "status_code")
    try:
        return int(status) if status is not None else None
    except (TypeError, ValueError):
        return None


def _safe_provider_error(error: Exception) -> ExaSearchError:
    status = _status_code(error)
    name = type(error).__name__.lower()

    if status in (401, 403):
        return ExaSearchError(
            "Exa rejected the configured API key. Check EXA_API_KEY and its access.",
            kind="authentication",
            status_code=status,
        )
    if status == 429:
        return ExaSearchError(
            "Exa rate limit reached. Retry after the provider limit resets.",
            kind="rate_limited",
            retryable=True,
            status_code=status,
        )
    if status == 408 or "timeout" in name:
        return ExaSearchError(
            "The Exa request timed out before the branch deadline. Retry the search.",
            kind="provider_timeout",
            retryable=True,
            status_code=status,
        )
    if status is not None and status >= 500:
        return ExaSearchError(
            "Exa is temporarily unavailable. Retry the search shortly.",
            kind="provider_unavailable",
            retryable=True,
            status_code=status,
        )
    if status is not None:
        return ExaSearchError(
            "Exa rejected the search request. Check the query and provider configuration.",
            kind="provider_rejected_request",
            status_code=status,
        )
    if any(token in name for token in ("connect", "network", "transport")):
        return ExaSearchError(
            "Could not connect to Exa. Check backend network access and retry the search.",
            kind="network_error",
            retryable=True,
        )
    return ExaSearchError(
        "Exa search failed. Check provider configuration and retry the search.",
        kind="provider_error",
    )


def _highlights(result) -> list[str]:
    values = _field(result, "highlights", []) or []
    if isinstance(values, str):
        values = [values]
    if not isinstance(values, (list, tuple)):
        return []
    return [value for value in values if isinstance(value, str) and value.strip()]


def _citation(result) -> dict | None:
    url = _field(result, "url")
    if not isinstance(url, str) or not url.strip():
        return None

    try:
        parsed_url = urlparse(url)
        domain = parsed_url.hostname
    except ValueError:
        return None
    if parsed_url.scheme.lower() not in {"http", "https"} or not domain:
        return None

    title = _field(result, "title")
    highlights = _highlights(result)
    if not isinstance(title, str) or not title.strip() or not highlights:
        return None

    domain = domain.lower()
    published_date = _field(result, "published_date")
    if published_date is not None and not isinstance(published_date, str):
        isoformat = getattr(published_date, "isoformat", None)
        published_date = isoformat() if callable(isoformat) else None
    if isinstance(published_date, str):
        published_date = published_date.strip() or None
    else:
        published_date = None

    provider_id = _field(result, "id")
    citation_id = str(provider_id).strip() if provider_id is not None else ""
    if not citation_id:
        digest = hashlib.sha256(url.encode("utf-8")).hexdigest()[:16]
        citation_id = f"exa_{digest}"

    verification_badge = "Live Exa result"
    if published_date:
        verification_badge += f" · Published {published_date}"

    return {
        "id": citation_id,
        "domain": domain,
        "url": url,
        "title": title.strip(),
        "favicon_url": f"https://www.google.com/s2/favicons?domain={domain}",
        "highlighted_quote": "\n".join(highlights),
        "verification_badge": verification_badge,
        "selected": False,
        "rejection_reason": None,
    }


async def search(query: str, *, timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS) -> list[dict]:
    """Return only usable citations backed by live Exa results and their highlights."""
    if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
        raise ValueError("timeout_seconds must be a finite positive number")
    if not query or not query.strip():
        raise ExaSearchError("Exa search requires a non-empty query.", kind="invalid_query")

    key = get_api_key("EXA_API_KEY")
    if not key:
        raise ExaConfigurationError("EXA_API_KEY is not configured; live Exa research cannot run")

    client = AsyncExa(api_key=key)
    try:
        response = await asyncio.wait_for(
            client.search_and_contents(query, type="auto", num_results=5, highlights=True),
            timeout=timeout_seconds,
        )
    except asyncio.TimeoutError:
        raise
    except Exception as error:
        raise _safe_provider_error(error) from None

    results = _field(response, "results", []) or []
    if not isinstance(results, (list, tuple)):
        return []
    return [citation for result in results if (citation := _citation(result)) is not None]
