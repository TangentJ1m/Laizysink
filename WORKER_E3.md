# Group E — Spatial execution map

## Scope
Create/own only `shared/src/com/onecaramel/laizysink/ui/ExecutionMapScreen.kt`.

## Goal and brief
Implement the progressive-disclosure map as an independent screen: show real dependency edges, expand at most one Subtask Node, fan out its WorkTask satellites, keep neighboring nodes readable, support pan/zoom, and provide the two-tier scrollable inspector with summary, highlights, inherited context, rationale, and source citations. Blocked-node actions must route to barrier resolution or HITL. Support completed-task read-only audit. Use real graph state only, with explicit empty/partial/error states and the prescribed design tokens.

Coordinate composable signature with E1; E1 owns the only App.kt call site. Use the current shared models and agree on any mismatch with A2/C3 without editing their paths.

## Exclusive ownership
New file `shared/src/com/onecaramel/laizysink/ui/ExecutionMapScreen.kt` only. Do not edit App.kt, state/models/network, or platform code.

## Git and completion
Use a dedicated branch/worktree. Check status before work and each focused commit; review diffs and never amend others' commits. Do not run builds/tests unless requested.

Done when graph nodes, work items, inspector content, blocker actions, pan/zoom, and progressive disclosure all derive from actual state. Report branch, commits, paths, and verification limitations.
