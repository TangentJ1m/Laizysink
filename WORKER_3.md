# Worker 3 — Shared Compose UI and client state

Read the repository `AGENTS.md` and `HANDOFF.md` before changing code. Implement the shared Android/Desktop UI from actual task state and the canonical Kotlin models.

## Goal

Replace the status-only/prototype presentation with reactive state, working task navigation, useful task/source/barrier details, and the progressive-disclosure execution map described in `AGENTS.md`.

## Exclusive write scope

- `shared/src/App.kt`
- `shared/src/com/onecaramel/laizysink/state/**`
- `shared/src/com/onecaramel/laizysink/network/**`
- `shared/src/Platform.kt`
- `shared/src@android/**`
- `shared/src@jvm/**`

Do not edit `androidApp/**`, `desktopApp/**`, backend code, contracts, root module descriptors, or README. Platform integration outside `shared/` belongs to Workers 4 and 5.

## Tasks

1. Implement shared `StateFlow` stores for task graphs, background-tab metadata where exposed, and temporal memory using real server events and the shared models.
2. Render task state, sources, alternatives/rejection reasons, and data barriers from received payloads; wire supported actions to the WebSocket contract.
3. Implement data-driven Home, Tasks, Active, Map, Keychain handoff, and Completed views without hard-coded sample results.
4. Add map progressive disclosure and inspector behavior from the requirements, using actual graph nodes and citations.
5. Keep Kotlin serialization aligned with `contracts/schema.json`; coordinate any backend contract changes through the lead.

## Data rules

- Never add demo/mock task results or fake Exa citations to the product UI. Empty/loading/error states must be explicit.
- Do not put provider keys in the shared client or Android/Desktop binaries. All provider keys stay in backend local environment configuration.
- Do not run tests or builds unless the lead/user has requested verification for this work.

## Git workflow

Use an isolated Git worktree and branch, e.g. `worker/3-shared-ui`. Start with `git status --short --branch`. Commit small, reviewable vertical slices (state/transport, task views, map/navigation) with focused messages. Review diffs and status before each commit; do not batch the entire feature into one macro-commit or amend other workers' commits. Report branch, commit IDs, changed paths, and verification limits to the lead.

## Done when

The shared UI is driven by live contract state, sends supported user actions through the shared transport, and contains no hard-coded mock result content.
