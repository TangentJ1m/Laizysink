# Group A — WebSocket contract and task lifecycle

## Scope
Own only `backend/main.py`.

## Goal and brief
Bring the server routes and WebSocket lifecycle into line with the canonical protocol in `AGENTS.md` and `contracts/schema.json`. Support creation, attachment to an existing task, task-specific event delivery, authoritative snapshots, barrier resolution, opaque keychain completion, memory expiration, and clear errors for invalid commands. Keep multiple tasks isolated. Coordinate any contract discrepancy with A2 and transport expectations with A3; do not edit their files.

The handoff reports that current event names and snapshot behavior do not match the spec, the server reassigns one socket to a mutable task ID, and HITL/memory commands are not handled. Address those gaps without fabricating live provider output or weakening credential rules. Keep server task persistence limitations explicit if they remain in-memory.

## Exclusive ownership
`backend/main.py` only. Do not edit models, schema, DAG, client, UI, configuration, or docs. Ask the lead for cross-scope changes.

## Git and completion
Use a dedicated branch and isolated worktree. Check `git status --short --branch` before work and before each commit. Make focused commits, review each diff, and never amend another branch's commits. Do not run provider calls, builds, or tests unless the lead/user requests verification.

Done when routes and WebSocket commands follow the documented task identity and event contract, reconnect/reattach can return a full current snapshot, and task messages cannot leak across task IDs. Report branch, commits, paths, verification, and remaining limitations.
