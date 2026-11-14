# Group E — Active Task and Completed Task screens

## Scope
Create/own only `shared/src/com/onecaramel/laizysink/ui/TaskOutcomeScreens.kt`.

## Goal and brief
Implement the Active Task and Completed Task composables as independent reusable screens. Active must show the task stepper, dynamic data-barrier choices/other input, live Exa citations and selected/discarded source inspection, errors, and the Map handoff. Completed must show evidence-backed result, alternatives, freshness/inherited memory, citation preview actions, map audit navigation, and a staged final action that still requires user approval. Use the existing `Models.kt` and `AppStateStore.kt` interfaces; do not hard-code demo task content. Follow the phone mockups and design tokens.

Coordinate composable signatures with E1 promptly. E1 owns the only `App.kt` integration call sites; do not edit that file.

## Exclusive ownership
New file `shared/src/com/onecaramel/laizysink/ui/TaskOutcomeScreens.kt` only. Do not edit App.kt, state/models/network, or platform code.

## Git and completion
Use a dedicated branch/worktree, inspect status before work and each focused commit, review diffs, and never amend another branch. Do not run builds/tests unless requested.

Done when both screens render only actual task data, expose required actions and error/empty states, and match the intended hierarchy. Report branch, commits, paths, and verification limits.
