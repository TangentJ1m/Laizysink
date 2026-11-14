import os

from exa_py import AsyncExa

from config import get_api_key


def _field(result, name, default=None):
    if isinstance(result, dict):
        return result.get(name, default)
    return getattr(result, name, default)


async def search(query: str) -> list[dict]:
    key = get_api_key("EXA_API_KEY")
    if not key:
        raise RuntimeError("EXA_API_KEY is not configured; live Exa research cannot run")

    client = AsyncExa(api_key=key)
    response = await client.search_and_contents(query, type="auto", num_results=5, highlights=True)
    results = _field(response, "results", []) or []
    citations = []
    for index, result in enumerate(results):
        url = _field(result, "url", "")
        domain = url.split("/")[2] if "://" in url else ""
        highlights = _field(result, "highlights", []) or []
        citations.append({
            "id": f"exa_{index}",
            "domain": domain,
            "url": url,
            "title": _field(result, "title") or domain,
            "favicon_url": f"https://www.google.com/s2/favicons?domain={domain}" if domain else None,
            "highlighted_quote": " ".join(highlights),
            "verification_badge": "Live Exa result",
            "selected": index == 0,
            "rejection_reason": None if index == 0 else "Ranked below the leading result for this query.",
        })
    return citations
