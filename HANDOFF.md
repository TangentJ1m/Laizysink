# Laizysink Handoff

## Goal and context

The user asked to carry out the proposed JetBrains Air implementation workflow in the existing Laizysink repository, using Kotlin Multiplatform + Compose Multiplatform, Kotlin Toolchain YAML builds, Android + Desktop JVM, and Temurin JDK 25. They also requested an initial Git commit and, later, this handoff for a fresh session.

The workspace already existed as a Kotlin Toolchain project. It was not an empty folder, so the work adapted the current modules instead of creating a parallel `kmp-app/` tree. This session cannot operate JetBrains Air's UI or create Air-managed isolated Git Worktrees; changes were made in the local checkout. Check `git log` and `git status` before doing more work.

## Current project configuration

- `project.yaml` actively lists only `androidApp`, `desktopApp`, and `shared`.
- `shared/module.yaml` is a KMP library targeting Android and JVM, with Compose, kotlinx.serialization, Ktor WebSockets, OkHttp for Android, and CIO for JVM.
- `shared/module.yaml` and `desktopApp/module.yaml` require Eclipse Temurin JDK 25. Kotlin Toolchain provisions JDKs when the shell `JAVA_HOME` does not meet the module requirement.
- `iosApp/` and `webApp/` folders remain in the repository but are not active `project.yaml` modules. The original whole-project build with Wasm enabled crashed in the Kotlin/Wasm compiler; after limiting active modules to the requested Android/Desktop targets, root build passed.
- No Gradle build scripts/wrapper or Maven POMs were added. Android APK packaging still delegates to Gradle internally through Kotlin Toolchain.

## Work completed

### Current follow-up

- Removed the synthetic `seed_exa` setup citation. Exa retrieval now requires `EXA_API_KEY`; without it the research branch fails instead of presenting non-live content as a source.
- Added `backend/config.py` to load the ignored `backend/.env` automatically while preserving process environment overrides. Added `python-dotenv` to backend requirements.
- Expanded `backend/.env.example` for active Exa/Gemini credentials and reserved ElevenLabs/Tiger Data settings. The reserved settings are not integrated into runtime code yet.
- Documented local provider setup and the real-data/no-fake-citation policy in `README.md` and `AGENTS.md`.
- Added disjoint role briefs `WORKER_1.md` through `WORKER_5.md` with exclusive path ownership and small, regular Git commit requirements.
- No provider API calls, mock-data checks, tests, or builds were run in this follow-up. Live API verification is pending user-supplied credentials and direction to verify.

- Reworked `AGENTS.md` around the current Kotlin Toolchain modules and source paths, Temurin 25, Air Worktree orchestration, backend contract paths, and the existing six-screen `App.kt` prototype.
- Added `contracts/schema.json` with schema definitions for task graphs, nodes, citations, barriers, keychain references, memory items, requests, and WebSocket events.
- Added matching Pydantic v2 models in `backend/models.py` and Kotlin `@Serializable` models in `shared/src/com/onecaramel/laizysink/state/Models.kt`.
- Added backend FastAPI routes in `backend/main.py`, Gemini planning with deterministic fallback in `backend/planner.py`, parallel `asyncio.TaskGroup` Exa branches with 8-second per-branch timeouts in `backend/dag_engine.py`, SDK Exa retrieval with highlights in `backend/exa_client.py`, temporal memory in `backend/memory_store.py`, and Gemini audio transcription in `backend/voice.py`.
- The backend supports REST task creation and `/ws/tasks/{task_id}`. A WebSocket client connects to `/ws/tasks/new`, sends a `CREATE_TASK`, receives branch events, and then remains attached to the generated task for barrier resolution.
- Added an Android/Desktop Ktor WebSocket transport in `shared/src/com/onecaramel/laizysink/network/LaizysinkSocketClient.kt` and platform HTTP client actuals for Android/JVM.
- Replaced the starter Compose demo with a six-destination dark UI prototype in `shared/src/App.kt` (Home, Tasks, Active, Map, Keychain, Completed). The Home prompt submits over WebSocket; the Active screen shows transport/event status.
- Added Android `HiddenWebViewPool`, an attached alpha-controlled composable host, `androidApp/assets/spotlight.js`, and an AndroidKeyStore AES-GCM encrypted SharedPreferences vault with platform BiometricPrompt utility.
- Updated the Android manifest with Internet, microphone, biometric, and cleartext local-development support.
- Added `backend/requirements.txt`, `.env.example`, setup instructions in `README.md`, and `.gitignore` entries for local configuration, secrets, caches, build outputs, IDE data, and a local PowerToys paste capture.
- Initialized Git on branch `main`; the initial commit should be present from this session. Confirm with `git log -1 --oneline`.

## Verification last run

- `.venv\\Scripts\\python.exe -m pytest -q backend` — **5 passed**, with a third-party `google-genai` deprecation warning.
- `kotlin.bat build` — **Build successful** for active Android and Desktop JVM modules.
- `kotlin.bat test` — **passed**; Kotlin Toolchain reported 6 shared tests passed and 3 app/module tests passed in the output.
- `kotlin.bat build -m shared`, `-m androidApp`, and `-m desktopApp` also passed during integration.
- Builds use the Kotlin Toolchain wrapper. The shell's system Java was 21, but Toolchain provisioned Temurin 25 for configured modules.

## Known gaps: continue from here

1. **Complete backend barrier flow.** `resolve_barrier()` records the answer and changes graph status, but it does not resume blocked DAG work or re-run Exa branches. The Compose UI currently does not submit `RESOLVE_BARRIER`.
2. **Render actual task state.** The Compose app currently only reflects a status string from events. Implement `TaskDagStore`/`AppStateStore` with StateFlow and render returned graph nodes, sources, barrier options, freshness, rejected alternatives, and completed results.
3. **Replace UI placeholders.** Map, task hub, keychain and completed screens are mock/demo content. Add actual pan/zoom, progressive disclosure, bottom inspector, citation links/favicons, alternatives, and data-driven navigation. The six destinations are currently co-located in `App.kt` rather than six screen files.
4. **Wire Android HITL.** `HiddenWebViewPool` and `HiddenWebViews` exist but are not mounted by `MainActivity` or navigation. Connect `HITL_BROWSER` events to the visible WebView, load spotlight JS, call clear/re-hide on page changes, and use the local vault only after biometric success.
5. **Security caveat.** `ZeroTrustKeychain` currently implements custom AES-GCM encrypted SharedPreferences backed by Android Keystore rather than the AndroidX `EncryptedSharedPreferences` class. Its biometric method is a utility, not a complete credential injection/authentication flow. Review char-array/String handling before production.
6. **Implement desktop platform functionality.** There are no Desktop browser/keychain/audio actuals yet. The requested plan asks for desktop equivalents; the current app only has a JVM Ktor engine.
7. **Improve Exa/Gemini resilience.** Exa missing-key behavior now fails research branches rather than returning a synthetic citation. Gemini fallback remains deterministic query decomposition only; it does not provide Exa evidence. Verify real Exa and Gemini behavior only when credentials are supplied and live verification is requested.
8. **Contract parity.** Add an automated parity test for `contracts/schema.json`, `backend/models.py`, and the Kotlin serialization models. Check that JSON Schema and Pydantic required/default fields agree.
9. **Voice and API polish.** Add tests for `/api/transcribe`, task REST routes, missing credentials, stale memory TTL, and WebSocket barrier resolution. Consider CORS and platform-specific WebSocket URL configuration before deployment.
10. **Product documentation.** `AGENTS.md` still contains extensive aspirational behavior specifications that the prototype does not yet implement. Keep the actual path structure accurate and clearly distinguish requirements from implemented state.

## Run commands (Windows)

```powershell
.\\kotlin.bat build
.\\kotlin.bat test
.\\kotlin.bat run -m androidApp
.\\kotlin.bat run -m desktopApp

.\\.venv\\Scripts\\python.exe -m pytest -q backend
Set-Location backend
..\\.venv\\Scripts\\python.exe -m uvicorn main:app --reload
```

The backend uses `EXA_API_KEY` and `GEMINI_API_KEY`; copy `backend/.env.example` to `backend/.env` for local secrets (that file is ignored). Do not stage API keys or clipboard captures.
