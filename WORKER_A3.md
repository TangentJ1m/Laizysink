# Group A — Shared WebSocket transport and recovery

## Scope
Own only `shared/src/com/onecaramel/laizysink/network/**` and shared platform endpoint declarations/actuals: `shared/src/Platform.kt`, `shared/src@android/Platform.android.kt`, and `shared/src@jvm/Platform.jvm.kt`.

## Goal and brief
Implement the client transport against A2's canonical contract and A1's server behavior. Cover task-specific connections, command acceptance/error visibility, reconnect and snapshot restoration, existing task attachment, task ID isolation, and safe handling of outbound commands during disconnects. Preserve platform-specific endpoint configuration and do not embed provider credentials. The handoff identifies the current `.../new` socket, missing snapshot recovery, and silent command loss as blockers.

Agree on message shapes with A1/A2. Keep state mutation in `AppStateStore.kt` with C3; expose transport events and outcomes through a clear API without editing that file.

## Exclusive ownership
The paths above only. Do not edit models, AppStateStore, Compose UI, backend, module descriptors, or docs.

## Git and completion
Use a dedicated branch/worktree; inspect status before work and commits. Make focused commits and review diffs. Do not run builds/tests unless requested.

Done when transport supports the canonical protocol and visibly reports disconnect/recovery/command failures without cross-task event leakage. Report branch, commits, paths, verification, and unresolved platform constraints.
