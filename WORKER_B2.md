# Group B — Exa retrieval and evidence quality

## Scope
Own only `backend/exa_client.py`.

## Goal and brief
Make Exa `search_and_contents` the only source of live research evidence, including exact highlights, source URL/title/domain, freshness metadata where actually available, and actionable failure information. Enforce the configured per-branch timeout (eight seconds by default), handle missing keys and provider errors without fake citations, and preserve enough provenance for later comparison and display. Coordinate the result interface with B3/A2; do not edit their files.

`HANDOFF2.md` reports misleading/incomplete citation presentation and stresses that every factual result must trace to an actual Exa URL and highlight. Never use Gemini as a source fallback, or fixtures/seeds in product behavior.

## Exclusive ownership
`backend/exa_client.py` only. No planner, graph, schema/models, config, tests, UI, or docs.

## Git and completion
Use a dedicated branch/worktree. Inspect status before work and every commit; make focused commits and review diffs. Do not call Exa or run tests/builds unless requested.

Done when successful results preserve real Exa evidence, and timeout/missing-key/provider failures remain explicit with no placeholder citation. Report branch, commits, paths, and live-verification status.
