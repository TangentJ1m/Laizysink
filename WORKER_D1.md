# Group D — Hidden WebView pool and spotlight

## Scope
Own only `androidApp/src/com/onecaramel/laizysink/browser/HiddenWebViewPool.kt` and `androidApp/assets/spotlight.js`.

## Goal and brief
Keep background pages attached and loading while visually hidden, enforce a single revealed tab, connect the requested URL/selector/tooltip to the correct WebView, inject/remove the spotlight safely, and handle missing selectors and navigation lifecycle without treating any redirect as proof of login. Make the current URL/domain and reveal state available to D3. Follow the `alpha=0` attached-view requirement and HITL acceptance evidence in `AGENTS.md`/`HANDOFF2.md`.

## Exclusive ownership
The two listed paths only. Do not edit Android activity/controller/security, shared code, or backend.

## Git and completion
Use a dedicated branch/worktree; inspect status before work and each focused commit. Commit coherent slices and review diffs; do not amend others' commits. Do not build/test or visit live sites unless requested.

Done when the intended live tab can be revealed, spotlighted, and re-hidden safely with no two foreground tabs. Report branch, commits, paths, and runtime verification limitations.
