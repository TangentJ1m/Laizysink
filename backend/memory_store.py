from datetime import datetime, timedelta, timezone
from uuid import uuid4

from models import FreshnessTag, TemporalMemoryItem


class TemporalMemoryStore:
    """In-process sliding-window memory for demo journeys."""

    def __init__(self) -> None:
        self.items: list[TemporalMemoryItem] = []

    def add(self, task_id: str, key: str, value: str) -> TemporalMemoryItem:
        item = TemporalMemoryItem(
            id=f"mem_{uuid4().hex[:10]}", task_id=task_id, key=key, value=value,
            created_at=datetime.now(timezone.utc), freshness_tag=FreshnessTag.USED,
        )
        self.items.append(item)
        return item

    def reuse(self, task_id: str, query: str, limit: int = 20) -> list[TemporalMemoryItem]:
        now = datetime.now(timezone.utc)
        terms = {word.casefold().strip(".,?!") for word in query.split() if len(word) > 3}
        matches = []
        for item in reversed(self.items):
            text = f"{item.key} {item.value}".casefold()
            if not any(term in text for term in terms):
                continue
            age = now - item.created_at
            if age > timedelta(days=60):
                freshness = FreshnessTag.ENTIRELY_STALE
            elif age > timedelta(days=7):
                freshness = FreshnessTag.STALE_QUERIED
            else:
                freshness = FreshnessTag.USED
            matches.append(item.model_copy(update={"freshness_tag": freshness}))
            if len(matches) == limit:
                break
        return matches

    def recent(self, limit: int = 50) -> list[TemporalMemoryItem]:
        return self.items[-limit:]


memory_store = TemporalMemoryStore()
