# Group B — Prompt planning and branch decomposition

## Scope
Own only `backend/planner.py`.

## Goal and brief
Replace fixed generic search branches with useful task-adaptive planning for arbitrary prompts. Produce a bounded, structured plan that supports independent subtasks, dependencies, required user inputs, and task-specific comparison criteria. Preserve deterministic query planning when Gemini is unavailable; Gemini may propose queries but never stand in for Exa evidence. Do not invent findings, choices, citations, or user facts. Coordinate the plan/model interface with B3 and A2 without editing their files.

Use `HANDOFF2.md` for the current audit, especially the finding that the implementation always creates three generic searches and has only a travel-specific regex barrier. Keep planner output bounded and failures explicit.

## Exclusive ownership
`backend/planner.py` only. No models/schema, DAG, Exa, API, memory, or docs.

## Git and completion
Use a dedicated branch and isolated worktree. Check status before work and before focused commits. Never amend another worker's commit. Do not call Gemini/Exa or run tests/builds unless requested.

Done when planning handles varied prompt shapes, yields a stable interface for DAG execution, and remains useful without a Gemini key without pretending fallback queries are evidence. Report branch, commits, paths, verification, and provider limitations.
