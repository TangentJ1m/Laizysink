# Group E — Home, task hub, and screen navigation

## Scope
Own only `shared/src/App.kt`.

## Goal and brief
Implement the six-screen host/navigation wiring plus Home and All Tasks Hub, using live `AppStateStore` state. Match the relevant mockups and design tokens, with prompt/voice/attachment entry points, quick actions, hidden-tab status, task priority routing, active-memory banner/manage entry, and actionable loading/empty/error/offline states. Wire Active, Map, Keychain, and Completed to the standalone composables delivered by E2/E3; do not recreate their feature UI inside this file. Coordinate function signatures with E2/E3 early. Keep all displayed facts sourced from actual state.

`App.kt` currently contains all six screens in one prototype file. To avoid overlapping ownership, E2 and E3 will add new screen files and will not edit `App.kt`; this worker owns the integration call sites.

## Exclusive ownership
`shared/src/App.kt` only. Do not edit state, models, transport, platform, or Android files.

## Git and completion
Use a dedicated branch/worktree. Check status before work and every focused commit; review diffs and do not amend other branches. Do not run builds/tests unless requested. Coordinate against E2/E3 interfaces, not their source files.

Done when Home/Tasks and navigation are state-driven, the six destinations are wired, and the mockup hierarchy/actions are represented without hard-coded result data. Report branch, commits, paths, and verification.
