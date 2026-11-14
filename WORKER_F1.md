# Group F — Backend runtime configuration and operator setup

## Scope
Own only `backend/config.py` and `README.md`.

## Goal and brief
Make local backend setup safe and workable for the phone/desktop demo. Document and implement reachable host/port binding, endpoint configuration, environment loading, eight-second branch timeout, health checks, and provider-key setup using names only. Fix stale or misleading setup instructions discovered by `HANDOFF2.md` (including the `.env.example` deletion issue) without placing credentials in docs or committing secrets. Do not change provider behavior or API routes.

Coordinate actual config names with A1/A3/F2/F3. Keep `backend/.env` ignored; restore a names-only `.env.example` if appropriate.

## Exclusive ownership
`backend/config.py` and `README.md` only. No routes, provider code, platform source, or secrets.

## Git and completion
Use a dedicated branch/worktree, check status before work and each focused commit, review diffs, and never amend another branch. Do not run provider calls/builds/tests unless requested.

Done when a developer can start a reachable backend with documented settings and understand health/provider setup without exposing secrets. Report branch, commits, paths, and verification limitations.
