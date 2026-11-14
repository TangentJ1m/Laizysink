# Group A — Canonical wire schema and models

## Scope
Own only `contracts/schema.json` and `backend/models.py`.

## Goal and brief
Make these the canonical, internally consistent definitions for REST payloads, WebSocket commands/events, task snapshots, graph nodes and work items, data barriers, Exa citations, freshness, HITL, and terminal/partial/failure states. Follow the event names and fields in `AGENTS.md`; use the detailed gaps and acceptance evidence in `HANDOFF2.md`. Preserve source provenance and opaque-only keychain references. Make model serialization unambiguous for Kotlin parity.

Coordinate the intended payload shape with A1 (server) and A3 (Kotlin transport/models) in writing before settling changes. Do not edit their owned files; publish the schema decision promptly so parallel work can proceed.

## Exclusive ownership
`contracts/schema.json`, `backend/models.py` only. No backend routes/DAG, Kotlin, UI, or docs.

## Git and completion
Use a dedicated branch and isolated worktree. Check `git status --short --branch` before work and before each focused commit. Review diffs; never amend another branch. Do not run tests/builds or provider calls unless requested.

Done when every documented wire payload has a clear schema/model representation, enum/event names agree, and downstream workers have the final contract to implement. Report branch, commits, changed paths, parity limitations, and verification.
