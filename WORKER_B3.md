# Group B — DAG orchestration, barriers, and result merge

## Scope
Own only `backend/dag_engine.py`.

## Goal and brief
Turn the plan and retrieval results into the specified two-layer execution graph: high-level dependent subtasks and granular work tasks. Run independent branches concurrently with strict branch timeouts, represent partial failures honestly, derive data barriers from plan requirements, unblock the branches that depend on a valid answer, and merge only evidence-supported results with alternatives and rejection rationale. Do not mark a task complete merely because one branch returned a citation. Coordinate interfaces with A2, B1, and B2; do not edit their files.

The handoff identifies a three-search graph, absent dependency edges/merge, generic post-search travel barriers, and restart-from-scratch barrier resolution. Preserve resumable branch state and make failures/retries visible in the graph model.

## Exclusive ownership
`backend/dag_engine.py` only. Do not edit API routes, models/schema, planner, Exa client, or clients.

## Git and completion
Use a dedicated branch and isolated worktree. Check status before work and each focused commit; review diffs and do not amend others' commits. Do not call providers or run tests/builds unless requested.

Done when execution produces a truthful dual-layer graph, resumes only relevant waiting work after a barrier, and produces an auditable evidence-backed merge or explicit partial/failure state. Report branch, commits, paths, and verification limits.
