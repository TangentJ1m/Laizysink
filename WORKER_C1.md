# Group C — Temporal memory lifecycle

## Scope
Own only `backend/memory_store.py`.

## Goal and brief
Implement bounded, event-aware memory behavior for facts reused across separate task roots: relevance, provenance, timestamps/TTL, explicit override/expiration, and the freshness states `USED`, `STALE_QUERIED`, and `ENTIRELY_STALE`. Expired or stale facts must not be silently presented as current. Keep persistence limitations explicit; do not create seeded/demo facts. Coordinate the memory API and freshness semantics with C3 and A2 without editing their files.

Use the memory acceptance checklist in `HANDOFF2.md`: users must see what was inherited, refresh stale facts before relying on them, and manage/expire context.

## Exclusive ownership
`backend/memory_store.py` only. No API routes, models/schema, Kotlin, UI, or docs.

## Git and completion
Use a dedicated branch/worktree. Check status before work and each focused commit, review diffs, and do not amend others' commits. Do not run tests/builds or provider calls unless requested.

Done when memory lifecycle and freshness decisions are explicit, bounded, and suitable for C3/API integration. Report branch, commits, paths, persistence limits, and verification.
