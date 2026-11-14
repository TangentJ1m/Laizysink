# Worker 4 — Android browser and zero-trust keychain

Read the repository `AGENTS.md` and `HANDOFF.md` before changing code. Own Android-specific browser, WebView, and device-vault integrations.

## Goal

Connect Android's hidden WebView pool and local encrypted credential vault to the human-in-the-loop flow, keeping pages loaded in the background and credentials on-device.

## Exclusive write scope

- `androidApp/src/**`
- `androidApp/assets/**`
- `androidApp/res/**`
- `androidApp/proguard-rules.pro`

Do not edit shared UI/state/network code, `desktopApp/**`, backend, contracts, module descriptors, or root docs. Ask Worker 3/lead for shared API changes rather than editing outside this scope.

## Tasks

1. Mount and manage hidden WebViews from the Android app lifecycle; keep hidden pages attached as described in `AGENTS.md` and reveal only the requested HITL tab.
2. Connect reveal/re-hide behavior and `spotlight.js` to the HITL event payload and page lifecycle.
3. Complete biometric-gated local vault use and form injection without transmitting raw credentials to the backend or LLM.
4. Handle cancellation, missing credentials, navigation changes, and cleanup safely. Keep the WebView's active content and domain visible to the user.

## Data and security rules

- Never use mock pages, credentials, login results, or fake HITL events as verification.
- Raw credentials must remain on-device. Emit only opaque `vault://` references and non-secret completion state.
- Backend service API keys are not Android app secrets and must not be bundled into the APK.
- Do not run tests/builds or navigate to live sites unless the lead/user has requested that verification for this work.

## Git workflow

Use an isolated Git worktree and dedicated branch, e.g. `worker/4-android-hitl`. Begin with `git status --short --branch`. Commit coherent slices (pool mounting, spotlight lifecycle, vault flow) separately with focused messages. Inspect diffs and status before committing; avoid a macro-commit and never amend another worker's commits. Report branch, commit IDs, changed paths, and verification limits to the lead.

## Done when

Android HITL events can reveal the intended live page, spotlight the requested target, safely perform an opted-in local biometric flow, and re-hide after resolution without sending credentials off device.
