from datetime import datetime, timedelta, timezone
from uuid import uuid4

from models import FreshnessTag, TemporalMemoryItem


class TemporalMemoryStore:
    """Bounded, in-process memory for cross-task context.

    Items survive within the running backend process only. Deployments that need
    context across restarts should replace this store with durable storage.
    """

    USED_TTL = timedelta(days=7)
    STALE_TTL = timedelta(days=60)
    MAX_ITEMS = 500

    def __init__(self) -> None:
        self.items: list[TemporalMemoryItem] = []

    def add(
        self,
        task_id: str,
        key: str,
        value: str,
        *,
        event_start_date=None,
        event_end_date=None,
    ) -> TemporalMemoryItem:
        item = TemporalMemoryItem(
            id=f"mem_{uuid4().hex[:10]}",
            task_id=task_id,
            key=key,
            value=value,
            event_start_date=event_start_date,
            event_end_date=event_end_date,
            created_at=datetime.now(timezone.utc),
            freshness_tag=FreshnessTag.USED,
        )
        self.items.append(item)
        if len(self.items) > self.MAX_ITEMS:
            del self.items[: len(self.items) - self.MAX_ITEMS]
        return item

    @staticmethod
    def _freshness(item: TemporalMemoryItem, now: datetime) -> FreshnessTag:
        created_at = item.created_at
        # Older records may not have a timezone. Treat them as UTC rather than
        # allowing a naive/aware comparison to break task creation.
        if created_at.tzinfo is None:
            created_at = created_at.replace(tzinfo=timezone.utc)
        age = max(now - created_at, timedelta())
        if age > TemporalMemoryStore.STALE_TTL:
            return FreshnessTag.ENTIRELY_STALE
        if age > TemporalMemoryStore.USED_TTL:
            return FreshnessTag.STALE_QUERIED
        return FreshnessTag.USED

    def reuse(self, task_id: str, query: str, limit: int = 20) -> list[TemporalMemoryItem]:
        """Return the most recent relevant items with freshness recalculated."""
        if limit <= 0:
            return []

        terms = {word.casefold().strip(".,?!:;()[]{}\"'") for word in query.split() if len(word) > 3}
        if not terms:
            return []

        now = datetime.now(timezone.utc)
        matches = []
        for item in reversed(self.items):
            # Do not accidentally return data being created by the same task as
            # inherited context; the caller should use its current graph state.
            if item.task_id == task_id:
                continue
            text = f"{item.key} {item.value}".casefold()
            if not any(term in text for term in terms):
                continue
            matches.append(item.model_copy(update={"freshness_tag": self._freshness(item, now)}))
            if len(matches) >= limit:
                break
        return matches

    def recent(self, limit: int = 50) -> list[TemporalMemoryItem]:
        """Return recent items with current, rather than insertion-time, tags."""
        if limit <= 0:
            return []
        now = datetime.now(timezone.utc)
        return [
            item.model_copy(update={"freshness_tag": self._freshness(item, now)})
            for item in self.items[-limit:]
        ]

    def expire(self, memory_id: str) -> bool:
        """Remove an item from active memory. Returns false when it is absent."""
        for index, item in enumerate(self.items):
            if item.id == memory_id:
                del self.items[index]
                return True
        return False

    def expire_task(self, task_id: str) -> int:
        """Remove all memory owned by a task and return the number removed."""
        retained = [item for item in self.items if item.task_id != task_id]
        removed = len(self.items) - len(retained)
        self.items = retained
        return removed


memory_store = TemporalMemoryStore()
