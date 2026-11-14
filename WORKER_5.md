# Worker 5 — Desktop JVM app integration

Read the repository `AGENTS.md` and `HANDOFF.md` before changing code. Own the Desktop JVM entry point and desktop-specific presentation/integration only.

## Goal

Make the desktop target a useful live client of the shared Laizysink application and backend, with platform-appropriate window behavior and configuration that contains no provider secrets.

## Exclusive write scope

- `desktopApp/**`

Do not edit `shared/**`, `androidApp/**`, backend, contracts, module descriptors, or root documentation. Request shared transport/UI changes from Worker 3 and backend contract changes from Worker 1.

## Tasks

1. Review current desktop launcher and ensure it starts the shared application cleanly as a JVM desktop client.
2. Provide desktop-local settings for the backend base/WebSocket endpoint as appropriate, but do not embed Exa, Gemini, ElevenLabs, or Tiger Data credentials in the client.
3. Ensure desktop navigation and window lifecycle cooperate with the shared app; surface unavailable server/connection state through shared interfaces requested from Worker 3 rather than hard-coded sample results.
4. Keep this target within the existing Kotlin Toolchain module and Temurin JDK 25 configuration. Do not introduce Gradle or Maven build files.

## Data rules

- Use real backend events and real provider-backed results only. No mock tasks, demo citations, or fabricated data.
- Do not run tests/builds or send live network requests unless the lead/user has requested that verification for this work.

## Git workflow

Use an isolated Git worktree and dedicated branch, e.g. `worker/5-desktop`. Start with `git status --short --branch`. Commit small reviewable slices with focused messages (configuration, lifecycle, integration), reviewing each diff and status before commit. Avoid an end-of-task macro-commit; never amend commits from other workers. Report branch, commit IDs, changed paths, and verification limits to the lead.

## Done when

The desktop client launches the shared application with configurable backend connectivity, introduces no client-side provider secrets or sample data, and remains within the established Kotlin Toolchain build setup.
