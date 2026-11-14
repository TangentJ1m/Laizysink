# Group F — Desktop JVM runtime configuration

## Scope
Own only `desktopApp/**`.

## Goal and brief
Make the Desktop JVM launcher a useful live client of the shared app with configurable backend connectivity, clear unavailable-server behavior through shared interfaces, and appropriate window/lifecycle handling. Follow the existing Kotlin Toolchain module and Temurin JDK 25 setup; do not add Gradle/Maven files or provider keys. Coordinate endpoint naming with A3/F1 without editing their paths.

The desktop target is active in `project.yaml`; retain compatibility with its current launcher and `DesktopRuntimeConfig.kt` rather than assuming the module is only a scaffold.

## Exclusive ownership
`desktopApp/**` only. No shared, Android, backend, schema, module descriptor, or README changes.

## Git and completion
Use an isolated worktree and dedicated branch. Check status before work and each focused commit; inspect diffs and never amend others' commits. Do not build/test or make live network requests unless requested.

Done when desktop launches the shared UI with configurable server address and useful connection lifecycle behavior, with no client-side provider secret. Report branch, commits, paths, and verification limits.
