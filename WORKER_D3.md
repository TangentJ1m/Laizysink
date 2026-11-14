# Group D — Android HITL orchestration and presentation

## Scope
Own only `androidApp/src/com/onecaramel/laizysink/browser/AndroidHitlController.kt` and `androidApp/src/MainActivity.kt`.

## Goal and brief
Bridge shared HITL requests to D1's browser pool and D2's keychain. Present the correct foreground browser with an anchored keychain/action sheet, support biometric autofill and manual completion, send only opaque completion data through shared actions, and return to the correct task only after a valid completion signal. Handle cancellation, repeated callbacks, navigation/session confirmation, and activity cleanup. Maintain a clear boundary between WebView DOM operations and local secrets. Agree on the shared callback shape with C3/E1, without editing their files.

The handoff reports that current local dismissal can look complete while the server remains blocked, and the current overlay does not match the browser-plus-sheet design.

## Exclusive ownership
The two listed files only. No pool/assets/security/shared/backend paths.

## Git and completion
Use a dedicated branch/worktree, check status before work and focused commits, review diffs, and never amend others' commits. Do not build/test or visit live sites unless requested.

Done when a requested tab reaches the user, biometric/manual completion is acknowledged for the right task/tab, and transaction approval remains explicit. Report branch, commits, paths, and verification limits.
