# Group F — Android backend endpoint configuration

## Scope
Own only `shared/src@android/Platform.android.kt` and Android-specific backend endpoint settings resources under `androidApp/res/values/`.

## Goal and brief
Remove the emulator-only hardcoded backend address from the Android actual and provide a practical way to configure a host reachable from a physical phone, while retaining a clear emulator/local-development default if needed. Validate and normalize the WebSocket endpoint, avoid provider credentials, and expose enough connection guidance for the shared UI to explain common reachability failures. Follow F1's documented setting names and A3's platform API; coordinate before settling interfaces.

HANDOFF2.md identifies `10.0.2.2` and loopback-only backend startup as preventing physical-device use. Do not edit MainActivity (D3 owns it) or backend configuration (F1 owns it).

## Exclusive ownership
The listed platform actual and `androidApp/res/values/**` only. Do not edit Android manifest/activity, shared common/platform declaration, network client, backend, or docs.

## Git and completion
Use a dedicated branch/worktree. Check status before work and each focused commit; review diffs and do not amend others' commits. Do not build/test or connect to a physical device unless requested.

Done when Android no longer depends on the emulator alias for phone use, endpoint configuration is usable and safe, and connection failures have actionable user-facing guidance via the agreed interface. Report branch, commits, paths, and device-verification status.
