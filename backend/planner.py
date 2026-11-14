import asyncio
import json
import os

from google import genai


def _fallback_plan(prompt: str) -> list[dict[str, str]]:
    return [
        {"title": "Find leading options", "query": f"{prompt} best options live sources"},
        {"title": "Compare alternatives", "query": f"{prompt} compare alternatives price features"},
        {"title": "Verify authoritative sources", "query": f"{prompt} official authoritative information"},
    ]


async def plan(prompt: str) -> list[dict[str, str]]:
    key = os.getenv("GEMINI_API_KEY")
    if not key:
        return _fallback_plan(prompt)
    client = genai.Client(api_key=key)
    instructions = (
        "Decompose the user request into exactly three independent web research branches. "
        "Return JSON only as {\"branches\":[{\"title\":string,\"query\":string}, ...]}. "
        "Use specific queries and never invent facts. Request official sources for legal questions.\n\n"
        f"User request: {prompt}"
    )
    try:
        result = await asyncio.wait_for(
            asyncio.to_thread(
                client.models.generate_content,
                model="gemini-2.5-flash",
                contents=instructions,
                config={"response_mime_type": "application/json"},
            ),
            timeout=8,
        )
        data = json.loads(result.text or "{}")
        branches = data.get("branches", [])
        valid = [{"title": str(item["title"]), "query": str(item["query"])} for item in branches[:3] if item.get("title") and item.get("query")]
        return valid or _fallback_plan(prompt)
    except Exception:
        return _fallback_plan(prompt)
