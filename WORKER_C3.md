# Group C — Client task and temporal-memory state

## Scope
Own only `shared/src/com/onecaramel/laizysink/state/AppStateStore.kt`.

## Goal and brief
Connect the shared state stores to the canonical transport: preserve full task snapshots, task selection, active barriers, Exa citations, staged HITL requests, memory items/freshness, and explicit command outcomes. Add state actions required to resolve/expire/override/refresh memory and to report transcription/prompt status, using the interfaces agreed with A3 and A2. Do not implement UI in this file beyond exposing observable state/actions. Do not create sample task or memory content.

`HANDOFF2.md` finds state is memory-only and cannot restore snapshots or safely represent several independent tasks. Keep task state reactive and make errors/connection state usable by E1/E2.

## Exclusive ownership
`shared/src/com/onecaramel/laizysink/state/AppStateStore.kt` only. Do not edit `Models.kt`, network, App.kt, backend, or docs.

## Git and completion
Use a dedicated branch/worktree; check status before work and each focused commit. Review diffs and do not amend others' commits. Do not run builds/tests unless requested.

Done when state consumes authoritative protocol events and exposes usable task/memory/HITL actions without fabricated state. Report branch, commits, paths, and verification limits.
