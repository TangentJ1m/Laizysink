# Worker 1 — Backend DAG and Exa research

Read the repository `AGENTS.md` and `HANDOFF.md` before changing code. Implement the backend research graph against real provider responses and the canonical contract.

## Goal

Make prompt planning, parallel Exa retrieval, graph status, citations, and barrier handling reliable for arbitrary live user prompts. Exa is the source of research evidence. Never synthesize, seed, or relabel data as a live citation. Gemini must not replace Exa or be called as an Exa fallback. The separate deterministic query-plan fallback is permitted when Gemini planning is unavailable.

## Exclusive write scope

- `backend/main.py`
- `backend/models.py`
- `backend/planner.py`
- `backend/dag_engine.py`
- `backend/exa_client.py`
- `contracts/schema.json`

Do not edit other paths. Coordinate requested contract changes through your own changes to the listed canonical files; downstream Kotlin parity belongs to Worker 3.

## Tasks

1. Review current implementations and the handoff gaps. Preserve the Exa `search_and_contents` highlights path and per-branch timeout behavior.
2. Keep missing Exa credentials and provider failures visible as failed research; never return placeholder/fake citation objects.
3. Ensure parallel branches produce useful state transitions and citations matching `contracts/schema.json` and `backend/models.py`.
4. Complete barrier handling in the backend so a valid answer is recorded and any dependent research resumes with the resolved user context. Keep invalid barrier IDs/answers explicit.
5. Keep the wire contract stable or update both owned canonical contract files together. Do not weaken citation provenance or silently swallow configuration errors.

## Provider and data rules

- Use real Exa/Gemini APIs only when valid credentials are supplied in the local ignored `backend/.env` or process environment.
- Never use mock providers, fixtures, seeded examples, fabricated citations, or synthetic live results for implementation verification.
- Do not print or commit secrets. If credentials are unavailable, implement the path and report that live verification remains pending; do not substitute test data.
- Do not call provider APIs or run tests unless the lead/user has requested that verification for this work.

## Git workflow

Work in an isolated Git worktree and a dedicated branch, e.g. `worker/1-backend-exa`. Start with `git status --short --branch`; do not share a mutable checkout with another worker. Commit each coherent vertical slice (for example, Exa error semantics, then graph/barrier flow, then contract alignment) with a focused message. Check `git status` and review each diff before committing. Avoid one large end-of-task commit. Do not rewrite or amend commits from other workers. Send the lead your branch name, commit IDs, changed paths, and any live-API verification limitation.

## Done when

The owned backend and schema code handles live Exa branches, contains no fabricated source fallback, resumes valid barriers, and keeps model/schema payloads aligned. Report commits and verification performed without inventing results.
