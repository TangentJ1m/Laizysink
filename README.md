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
# Set EXA_API_KEY and GEMINI_API_KEY in backend/.env when available.
cd backend
uvicorn main:app --reload
```

Run backend workflow tests from the repository root with `.venv\Scripts\python.exe -m pytest backend`.
