# Laizysink

Laizysink is a prompt-first browser assistant built with Kotlin Multiplatform, Compose Multiplatform, Kotlin Toolchain, and a Python FastAPI backend. The configured Kotlin targets are Android and Desktop JVM. Kotlin Toolchain provisions Eclipse Temurin JDK 25.

## Project layout

- `androidApp/`: Android app entry point and Android browser/keychain integrations.
- `desktopApp/`: Desktop JVM application.
- `shared/`: shared Compose UI, state models, and Ktor WebSocket transport.
- `backend/`: FastAPI service, Exa retrieval, Gemini transcription, and temporal memory.
- `contracts/schema.json`: JSON Schema for REST and WebSocket payloads.

Build the Kotlin projects with declarative `project.yaml` and `module.yaml` files. There are no Gradle or Maven build files to maintain.

## Build and run

Windows:

```powershell
.\kotlin.bat build
.\kotlin.bat test
.\kotlin.bat run -m androidApp
.\kotlin.bat run -m desktopApp
```

macOS/Linux: use `./kotlin` with the same arguments.

## Backend

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r backend/requirements.txt
Copy-Item backend/.env.example backend/.env
# Edit backend/.env locally and provide your real provider credentials.
cd backend
uvicorn main:app --reload
```

The backend loads `backend/.env` automatically. Environment variables already set by your shell or deployment take precedence. `EXA_API_KEY` is required for live research; without it, research branches fail rather than emitting sample or fabricated citations. `GEMINI_API_KEY` enables Gemini planning and `/api/transcribe`; if it is absent, planning uses the local deterministic query plan and transcription returns a setup error.

`ELEVENLABS_API_KEY`, `ELEVENLABS_VOICE_ID`, and the `TIGERDATA_*` entries are reserved for future integrations and are not used by the current code. Never commit `backend/.env` or paste secrets into source control, worker briefs, or chat. The checked-in `.env.example` contains names only; supply credentials from the provider accounts when you are ready to enable each integration.

No provider credentials or live calls are needed to launch the service. Before using a provider integration, populate the matching local setting with a real credential. Do not represent placeholder configuration as a connected integration.
