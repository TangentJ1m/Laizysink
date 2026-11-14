from datetime import date, datetime, timedelta, timezone
import asyncio
from uuid import uuid4

from config import get_setting
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
        event_start_date: date | None = None,
        event_end_date: date | None = None,
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


class TigerDataTemporalMemoryStore:
    """Persist temporal context in Tiger Cloud's PostgreSQL-compatible service."""

    TABLE = "laizysink_temporal_memory"

    def __init__(self, connection_string: str) -> None:
        self._connection_string = connection_string
        self._schema_ready = False
        self._schema_lock = asyncio.Lock()

    async def _connect(self):
        from psycopg import AsyncConnection

        return await AsyncConnection.connect(self._connection_string, connect_timeout=10)

    async def _ensure_schema(self) -> None:
        if self._schema_ready:
            return
        async with self._schema_lock:
            if self._schema_ready:
                return
            try:
                async with await self._connect() as connection:
                    async with connection.cursor() as cursor:
                        await cursor.execute(f"""
                            CREATE TABLE IF NOT EXISTS {self.TABLE} (
                                id TEXT PRIMARY KEY,
                                task_id TEXT NOT NULL,
                                memory_key TEXT NOT NULL,
                                value TEXT NOT NULL,
                                event_start_date DATE,
                                event_end_date DATE,
                                created_at TIMESTAMPTZ NOT NULL,
                                freshness_tag TEXT NOT NULL
                            )
                        """)
                        await cursor.execute(f"""
                            CREATE INDEX IF NOT EXISTS {self.TABLE}_created_at_idx
                            ON {self.TABLE} (created_at DESC)
                        """)
                self._schema_ready = True
            except Exception as exc:
                raise RuntimeError(
                    f"Tiger Data memory setup failed ({type(exc).__name__}); "
                    "check TIGERDATA_DATABASE_URL and database access"
                ) from None

    @staticmethod
    def _from_row(row) -> TemporalMemoryItem:
        return TemporalMemoryItem(
            id=row[0],
            task_id=row[1],
            key=row[2],
            value=row[3],
            event_start_date=row[4],
            event_end_date=row[5],
            created_at=row[6],
            freshness_tag=FreshnessTag(row[7]),
        )

    async def _recent_items(self, limit: int) -> list[TemporalMemoryItem]:
        if limit <= 0:
            return []
        await self._ensure_schema()
        try:
            async with await self._connect() as connection:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        f"""SELECT id, task_id, memory_key, value, event_start_date,
                                   event_end_date, created_at, freshness_tag
                            FROM {self.TABLE}
                            ORDER BY created_at DESC
                            LIMIT %s""",
                        (min(limit, TemporalMemoryStore.MAX_ITEMS),),
                    )
                    rows = await cursor.fetchall()
            return [self._from_row(row) for row in rows]
        except Exception as exc:
            raise RuntimeError(
                f"Tiger Data memory read failed ({type(exc).__name__}); "
                "check TIGERDATA_DATABASE_URL and database availability"
            ) from None

    async def add(
        self,
        task_id: str,
        key: str,
        value: str,
        *,
        event_start_date: date | None = None,
        event_end_date: date | None = None,
    ) -> TemporalMemoryItem:
        await self._ensure_schema()
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
        try:
            async with await self._connect() as connection:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        f"""INSERT INTO {self.TABLE}
                            (id, task_id, memory_key, value, event_start_date,
                             event_end_date, created_at, freshness_tag)
                            VALUES (%s, %s, %s, %s, %s, %s, %s, %s)""",
                        (item.id, item.task_id, item.key, item.value,
                         item.event_start_date, item.event_end_date, item.created_at,
                         item.freshness_tag.value),
                    )
                    await cursor.execute(f"""
                        DELETE FROM {self.TABLE}
                        WHERE id IN (
                            SELECT id FROM {self.TABLE}
                            ORDER BY created_at DESC
                            OFFSET %s
                        )
                    """, (TemporalMemoryStore.MAX_ITEMS,))
            return item
        except Exception as exc:
            raise RuntimeError(
                f"Tiger Data memory write failed ({type(exc).__name__}); "
                "check TIGERDATA_DATABASE_URL and database permissions"
            ) from None

    async def reuse(self, task_id: str, query: str, limit: int = 20) -> list[TemporalMemoryItem]:
        terms = {word.casefold().strip(".,?!:;()[]{}\"'") for word in query.split() if len(word) > 3}
        if limit <= 0 or not terms:
            return []
        now = datetime.now(timezone.utc)
        matches = []
        for item in await self._recent_items(TemporalMemoryStore.MAX_ITEMS):
            if item.task_id == task_id:
                continue
            text = f"{item.key} {item.value}".casefold()
            if any(term in text for term in terms):
                matches.append(item.model_copy(update={
                    "freshness_tag": TemporalMemoryStore._freshness(item, now),
                }))
                if len(matches) >= limit:
                    break
        return matches

    async def recent(self, limit: int = 50) -> list[TemporalMemoryItem]:
        now = datetime.now(timezone.utc)
        items = await self._recent_items(limit)
        return [item.model_copy(update={
            "freshness_tag": TemporalMemoryStore._freshness(item, now),
        }) for item in items]

    async def expire(self, memory_id: str) -> bool:
        await self._ensure_schema()
        try:
            async with await self._connect() as connection:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        f"DELETE FROM {self.TABLE} WHERE id = %s RETURNING id",
                        (memory_id,),
                    )
                    return await cursor.fetchone() is not None
        except Exception as exc:
            raise RuntimeError(
                f"Tiger Data memory expiration failed ({type(exc).__name__})"
            ) from None

    async def expire_task(self, task_id: str) -> int:
        await self._ensure_schema()
        try:
            async with await self._connect() as connection:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        f"DELETE FROM {self.TABLE} WHERE task_id = %s RETURNING id",
                        (task_id,),
                    )
                    return len(await cursor.fetchall())
        except Exception as exc:
            raise RuntimeError(
                f"Tiger Data task memory expiration failed ({type(exc).__name__})"
            ) from None


class ConfiguredTemporalMemoryStore:
    """Use Tiger Data when configured, otherwise retain local prototype storage."""

    def __init__(self, connection_string: str | None) -> None:
        self._local = TemporalMemoryStore()
        self._remote = TigerDataTemporalMemoryStore(connection_string) if connection_string else None

    async def add(self, *args, **kwargs) -> TemporalMemoryItem:
        if self._remote:
            return await self._remote.add(*args, **kwargs)
        return self._local.add(*args, **kwargs)

    async def reuse(self, *args, **kwargs) -> list[TemporalMemoryItem]:
        if self._remote:
            return await self._remote.reuse(*args, **kwargs)
        return self._local.reuse(*args, **kwargs)

    async def recent(self, *args, **kwargs) -> list[TemporalMemoryItem]:
        if self._remote:
            return await self._remote.recent(*args, **kwargs)
        return self._local.recent(*args, **kwargs)

    async def expire(self, *args, **kwargs) -> bool:
        if self._remote:
            return await self._remote.expire(*args, **kwargs)
        return self._local.expire(*args, **kwargs)

    async def expire_task(self, *args, **kwargs) -> int:
        if self._remote:
            return await self._remote.expire_task(*args, **kwargs)
        return self._local.expire_task(*args, **kwargs)


memory_store = ConfiguredTemporalMemoryStore(get_setting("TIGERDATA_DATABASE_URL"))
