# Group D — Local encrypted keychain and biometrics

## Scope
Own only `androidApp/src/com/onecaramel/laizysink/security/ZeroTrustKeychain.kt`.

## Goal and brief
Complete the device-local credential vault using Android Keystore-backed encryption and biometric authorization. Support domain matching, masked identity display, safe local retrieval/injection inputs, opt-in save, no-match/manual flow, cancellation and invalidation recovery. Raw credentials must never enter shared state, prompts, logs, WebSocket payloads, or backend memory; only opaque `vault://domain` references may leave the device after successful authorized action. Coordinate the controller API with D3 without editing its files.

## Exclusive ownership
`ZeroTrustKeychain.kt` only. Do not edit browser pool, controller, activity, shared code, or backend.

## Git and completion
Use an isolated worktree and dedicated branch. Check status before work and each focused commit; inspect diffs; never amend another branch. Do not run builds/tests or interact with a live site unless requested.

Done when credentials are encrypted and device-bound, protected by explicit biometric authorization, and no raw secret can escape the Android boundary. Report branch, commits, paths, and verification limits.
