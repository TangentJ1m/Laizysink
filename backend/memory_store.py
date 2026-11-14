from datetime import date, datetime, timedelta, timezone
import asyncio
import re
from uuid import uuid4

from config import get_setting
from models import FreshnessTag, TemporalMemoryItem


class TemporalMemoryStore:
    """Bounded, in-process memory for cross-task context.

    Items survive within the running backend process only. Deployments that need
    context across restarts should replace this store with durable storage.

    Facts older than USED_TTL are candidates for an explicit refresh, not current
    context. `reuse` only returns current facts; `recent` and
    `relevant_candidates` expose stale tags for management and refresh workflows.
    """

    USED_TTL = timedelta(days=7)
    STALE_TTL = timedelta(days=60)
    MAX_ITEMS = 500
    MAX_LIMIT = 100
    MAX_KEY_LENGTH = 200
    MAX_VALUE_LENGTH = 4_000
    MAX_QUERY_LENGTH = 2_000
    _TOKEN_PATTERN = re.compile(r"[^\W_]+", re.UNICODE)
    _STOP_WORDS = frozenset({
        "about", "after", "again", "also", "and", "are", "from", "have",
        "into", "near", "please", "that", "the", "their", "them", "then",
        "there", "this", "with", "would", "your",
    })

    def __init__(self) -> None:
        self.items: list[TemporalMemoryItem] = []

    @classmethod
    def _bounded_limit(cls, limit: int) -> int:
        return max(0, min(limit, cls.MAX_LIMIT))

    @classmethod
    def _bounded_item_count(cls, limit: int) -> int:
        return max(0, min(limit, cls.MAX_ITEMS))

    @staticmethod
    def _validate_event_window(
        event_start_date: date | None,
        event_end_date: date | None,
    ) -> None:
        if event_start_date is not None and event_end_date is not None and event_start_date > event_end_date:
            raise ValueError("event_start_date must be on or before event_end_date")

    @classmethod
    def _validate_text(cls, task_id: str, key: str, value: str) -> tuple[str, str, str]:
        task_id = task_id.strip()
        key = key.strip()
        value = value.strip()
        if not task_id:
            raise ValueError("task_id must not be blank")
        if not key:
            raise ValueError("memory key must not be blank")
        if not value:
            raise ValueError("memory value must not be blank")
        if len(key) > cls.MAX_KEY_LENGTH:
            raise ValueError(f"memory key must be at most {cls.MAX_KEY_LENGTH} characters")
        if len(value) > cls.MAX_VALUE_LENGTH:
            raise ValueError(f"memory value must be at most {cls.MAX_VALUE_LENGTH} characters")
        return task_id, key, value

    @classmethod
    def _terms(cls, value: str) -> set[str]:
        return {
            token.casefold()
            for token in cls._TOKEN_PATTERN.findall(value)
            if len(token) >= 3 and token.casefold() not in cls._STOP_WORDS
        }

    @staticmethod
    def _utc(value: datetime) -> datetime:
        return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)

    @staticmethod
    def _event_terms(item: TemporalMemoryItem) -> set[str]:
        terms: set[str] = set()
        for event_date in (item.event_start_date, item.event_end_date):
            if event_date is None:
                continue
            terms.update({
                str(event_date.year),
                event_date.strftime("%B").casefold(),
                event_date.strftime("%b").casefold(),
            })
        return terms

    @classmethod
    def _matches_event_window(
        cls,
        item: TemporalMemoryItem,
        event_start_date: date | None,
        event_end_date: date | None,
        require_window_for_scoped: bool = False,
    ) -> bool:
        if event_start_date is None and event_end_date is None:
            return not require_window_for_scoped or (
                item.event_start_date is None and item.event_end_date is None
            )
        if item.event_start_date is None and item.event_end_date is None:
            # Unscoped preferences and facts can apply to any event window.
            return True

        requested_start = event_start_date or date.min
        requested_end = event_end_date or date.max
        item_start = item.event_start_date or date.min
        item_end = item.event_end_date or date.max
        return item_start <= requested_end and item_end >= requested_start

    @classmethod
    def _candidates(
        cls,
        items: list[TemporalMemoryItem],
        task_id: str,
        query: str,
        limit: int,
        now: datetime,
        event_start_date: date | None,
        event_end_date: date | None,
        require_window_for_scoped: bool = False,
    ) -> list[TemporalMemoryItem]:
        query_terms = cls._terms(query[: cls.MAX_QUERY_LENGTH])
        limit = cls._bounded_item_count(limit)
        if limit == 0 or not query_terms:
            return []

        today = now.date()
        matches: list[tuple[int, int, int, datetime, TemporalMemoryItem]] = []
        for item in items:
            # A task must not inherit its own output, and a scoped event fact is
            # only relevant to an overlapping requested window when one is given.
            if item.task_id == task_id or not cls._matches_event_window(
                item,
                event_start_date,
                event_end_date,
                require_window_for_scoped=require_window_for_scoped,
            ):
                continue

            item_text = f"{item.key[:cls.MAX_KEY_LENGTH]} {item.value[:cls.MAX_VALUE_LENGTH]}"
            item_terms = cls._terms(item_text) | cls._event_terms(item)
            overlap = len(query_terms & item_terms)
            if overlap == 0:
                continue

            freshness = cls._freshness(item, now)
            freshness_rank = {
                FreshnessTag.USED: 2,
                FreshnessTag.STALE_QUERIED: 1,
                FreshnessTag.ENTIRELY_STALE: 0,
            }[freshness]
            if item.event_start_date and item.event_start_date > today:
                event_rank = 2  # upcoming event
            elif item.event_end_date and item.event_end_date >= today:
                event_rank = 1  # current or ongoing event
            else:
                event_rank = 0  # unscoped or past event

            created_at = cls._utc(item.created_at)
            matches.append((overlap, freshness_rank, event_rank, created_at, item))

        matches.sort(key=lambda match: match[:4], reverse=True)
        return [
            item.model_copy(update={"freshness_tag": cls._freshness(item, now)})
            for _, _, _, _, item in matches[:limit]
        ]

    def add(
        self,
        task_id: str,
        key: str,
        value: str,
        *,
        event_start_date: date | None = None,
        event_end_date: date | None = None,
    ) -> TemporalMemoryItem:
        task_id, key, value = self._validate_text(task_id, key, value)
        self._validate_event_window(event_start_date, event_end_date)
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
        now = TemporalMemoryStore._utc(now)
        created_at = TemporalMemoryStore._utc(item.created_at)
        if item.event_end_date is not None and item.event_end_date < now.date():
            return FreshnessTag.ENTIRELY_STALE
        age = max(now - created_at, timedelta())
        if age > TemporalMemoryStore.STALE_TTL:
            return FreshnessTag.ENTIRELY_STALE
        if age > TemporalMemoryStore.USED_TTL:
            return FreshnessTag.STALE_QUERIED
        return FreshnessTag.USED

    def relevant_candidates(
        self,
        task_id: str,
        query: str,
        limit: int = 20,
        *,
        event_start_date: date | None = None,
        event_end_date: date | None = None,
    ) -> list[TemporalMemoryItem]:
        """Return relevant current and stale candidates, without endorsing them for use.

        Callers must refresh `STALE_QUERIED` candidates before relying on them and
        must not reuse `ENTIRELY_STALE` candidates as current context.
        """
        self._validate_event_window(event_start_date, event_end_date)
        now = datetime.now(timezone.utc)
        return self._candidates(
            self.items,
            task_id,
            query,
            self._bounded_limit(limit),
            now,
            event_start_date,
            event_end_date,
        )

    def reuse(
        self,
        task_id: str,
        query: str,
        limit: int = 20,
        *,
        event_start_date: date | None = None,
        event_end_date: date | None = None,
    ) -> list[TemporalMemoryItem]:
        """Return only current, relevant facts; stale values are never task context."""
        self._validate_event_window(event_start_date, event_end_date)
        now = datetime.now(timezone.utc)
        candidates = self._candidates(
            self.items,
            task_id,
            query,
            self.MAX_ITEMS,
            now,
            event_start_date,
            event_end_date,
            require_window_for_scoped=True,
        )
        current = [item for item in candidates if item.freshness_tag == FreshnessTag.USED]
        return current[: self._bounded_limit(limit)]

    def recent(self, limit: int = 50) -> list[TemporalMemoryItem]:
        """Return recent items with current, rather than insertion-time, tags."""
        limit = self._bounded_limit(limit)
        if limit <= 0:
            return []
        now = datetime.now(timezone.utc)
        recent_items = sorted(
            self.items,
            key=lambda item: self._utc(item.created_at),
            reverse=True,
        )[:limit]
        items = [
            item.model_copy(update={"freshness_tag": self._freshness(item, now)})
            for item in recent_items
        ]
        return items

    def _replace_value(self, memory_id: str, task_id: str, value: str) -> TemporalMemoryItem | None:
        # The existing contract has no updated_at/updated_by fields. Keep
        # task_id as the original creator provenance and reset created_at for TTL.
        task_id = task_id.strip()
        value = value.strip()
        if not task_id:
            raise ValueError("task_id must not be blank")
        if not value:
            raise ValueError("memory value must not be blank")
        if len(value) > self.MAX_VALUE_LENGTH:
            raise ValueError(f"memory value must be at most {self.MAX_VALUE_LENGTH} characters")
        now = datetime.now(timezone.utc)
        for index, item in enumerate(self.items):
            if item.id == memory_id:
                updated = item.model_copy(update={
                    "value": value,
                    "created_at": now,
                    "freshness_tag": FreshnessTag.USED,
                })
                self.items[index] = updated
                return updated
        return None

    def override(self, memory_id: str, task_id: str, value: str) -> TemporalMemoryItem | None:
        """Replace a fact with an explicit user override and reset its age."""
        return self._replace_value(memory_id, task_id, value)

    def refresh(self, memory_id: str, task_id: str, verified_value: str) -> TemporalMemoryItem | None:
        """Refresh a fact with externally verified content; this method calls no provider."""
        return self._replace_value(memory_id, task_id, verified_value)

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
        limit = TemporalMemoryStore._bounded_item_count(limit)
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
        task_id, key, value = TemporalMemoryStore._validate_text(task_id, key, value)
        TemporalMemoryStore._validate_event_window(event_start_date, event_end_date)
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

    async def relevant_candidates(
        self,
        task_id: str,
        query: str,
        limit: int = 20,
        *,
        event_start_date: date | None = None,
        event_end_date: date | None = None,
    ) -> list[TemporalMemoryItem]:
        """Return relevant current and stale candidates for explicit review/refresh."""
        TemporalMemoryStore._validate_event_window(event_start_date, event_end_date)
        now = datetime.now(timezone.utc)
        items = await self._recent_items(TemporalMemoryStore.MAX_ITEMS)
        return TemporalMemoryStore._candidates(
            items,
            task_id,
            query,
            TemporalMemoryStore._bounded_limit(limit),
            now,
            event_start_date,
            event_end_date,
        )

    async def reuse(
        self,
        task_id: str,
        query: str,
        limit: int = 20,
        *,
        event_start_date: date | None = None,
        event_end_date: date | None = None,
    ) -> list[TemporalMemoryItem]:
        """Return only current, relevant facts; stale values are never task context."""
        TemporalMemoryStore._validate_event_window(event_start_date, event_end_date)
        now = datetime.now(timezone.utc)
        items = await self._recent_items(TemporalMemoryStore.MAX_ITEMS)
        candidates = TemporalMemoryStore._candidates(
            items,
            task_id,
            query,
            TemporalMemoryStore.MAX_ITEMS,
            now,
            event_start_date,
            event_end_date,
            require_window_for_scoped=True,
        )
        current = [item for item in candidates if item.freshness_tag == FreshnessTag.USED]
        return current[: TemporalMemoryStore._bounded_limit(limit)]

    async def recent(self, limit: int = 50) -> list[TemporalMemoryItem]:
        now = datetime.now(timezone.utc)
        items = await self._recent_items(TemporalMemoryStore._bounded_limit(limit))
        return [item.model_copy(update={
            "freshness_tag": TemporalMemoryStore._freshness(item, now),
        }) for item in items]

    async def _replace_value(
        self,
        memory_id: str,
        task_id: str,
        value: str,
    ) -> TemporalMemoryItem | None:
        # Preserve creator task_id. The current contract cannot also store the
        # override/refresh actor or retain the original creation timestamp.
        task_id = task_id.strip()
        value = value.strip()
        if not task_id:
            raise ValueError("task_id must not be blank")
        if not value:
            raise ValueError("memory value must not be blank")
        if len(value) > TemporalMemoryStore.MAX_VALUE_LENGTH:
            raise ValueError(
                f"memory value must be at most {TemporalMemoryStore.MAX_VALUE_LENGTH} characters"
            )

        await self._ensure_schema()
        now = datetime.now(timezone.utc)
        try:
            async with await self._connect() as connection:
                async with connection.cursor() as cursor:
                    await cursor.execute(
                        f"""UPDATE {self.TABLE}
                            SET value = %s, created_at = %s, freshness_tag = %s
                            WHERE id = %s
                            RETURNING id, task_id, memory_key, value, event_start_date,
                                      event_end_date, created_at, freshness_tag""",
                        (value, now, FreshnessTag.USED.value, memory_id),
                    )
                    row = await cursor.fetchone()
            return self._from_row(row) if row else None
        except Exception as exc:
            raise RuntimeError(
                f"Tiger Data memory update failed ({type(exc).__name__}); "
                "check TIGERDATA_DATABASE_URL and database availability"
            ) from None

    async def override(
        self,
        memory_id: str,
        task_id: str,
        value: str,
    ) -> TemporalMemoryItem | None:
        """Replace a fact with an explicit user override and reset its age."""
        return await self._replace_value(memory_id, task_id, value)

    async def refresh(
        self,
        memory_id: str,
        task_id: str,
        verified_value: str,
    ) -> TemporalMemoryItem | None:
        """Refresh a fact with externally verified content; this method calls no provider."""
        return await self._replace_value(memory_id, task_id, verified_value)

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

    async def relevant_candidates(self, *args, **kwargs) -> list[TemporalMemoryItem]:
        if self._remote:
            return await self._remote.relevant_candidates(*args, **kwargs)
        return self._local.relevant_candidates(*args, **kwargs)

    async def recent(self, *args, **kwargs) -> list[TemporalMemoryItem]:
        if self._remote:
            return await self._remote.recent(*args, **kwargs)
        return self._local.recent(*args, **kwargs)

    async def expire(self, *args, **kwargs) -> bool:
        if self._remote:
            return await self._remote.expire(*args, **kwargs)
        return self._local.expire(*args, **kwargs)

    async def override(self, *args, **kwargs) -> TemporalMemoryItem | None:
        if self._remote:
            return await self._remote.override(*args, **kwargs)
        return self._local.override(*args, **kwargs)

    async def refresh(self, *args, **kwargs) -> TemporalMemoryItem | None:
        if self._remote:
            return await self._remote.refresh(*args, **kwargs)
        return self._local.refresh(*args, **kwargs)

    async def expire_task(self, *args, **kwargs) -> int:
        if self._remote:
            return await self._remote.expire_task(*args, **kwargs)
        return self._local.expire_task(*args, **kwargs)


memory_store = ConfiguredTemporalMemoryStore(get_setting("TIGERDATA_DATABASE_URL"))
