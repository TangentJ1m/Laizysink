# Worker 2 — Backend memory and voice

Read the repository `AGENTS.md` and `HANDOFF.md` before changing code. Own temporal memory and audio transcription work only.

## Goal

Improve temporal memory freshness/reuse and make Gemini transcription robust while keeping provider credentials local and secrets out of prompts, events, logs, and source control.

## Exclusive write scope

- `backend/memory_store.py`
- `backend/voice.py`

Do not edit backend DAG, planner, Exa client, models/contracts, configuration, requirements, or documentation. Request cross-scope changes from the lead rather than editing another worker's files.

## Tasks

1. Review current memory reuse, timestamps, and freshness handling against the user requirements in `AGENTS.md`.
2. Implement an appropriate temporal memory lifecycle and freshness behavior within `memory_store.py`; keep persistence requirements explicit if in-memory storage remains a prototype limitation.
3. Preserve `/api/transcribe` as a real Gemini call with actionable missing-key and provider-error responses. Handle uploaded audio safely and return only transcription text.
4. Do not add ElevenLabs or Tiger Data runtime calls: those integrations are not currently implemented. Keep their reserved credentials unused until the lead explicitly assigns integration work.

## Provider and data rules

- Use the real Gemini API only with an explicitly configured local `GEMINI_API_KEY`.
- Never use mocked audio, fixture transcripts, seeded memory, or generated responses as provider verification.
- Do not print or commit secrets. If credentials are unavailable, report that live transcription verification is pending.
- Do not call provider APIs or run tests unless the lead/user has requested that verification for this work.

## Git workflow

Use an isolated Git worktree and dedicated branch, e.g. `worker/2-memory-voice`. Begin with `git status --short --branch`. Commit each coherent change as soon as it is reviewable, with focused messages for memory and transcription slices. Inspect the diff and check status before every commit. Avoid a single large end-of-task commit, and do not amend another worker's commits. Report branch, commit IDs, changed paths, and verification limits to the lead.

## Done when

Memory freshness/reuse behavior is clear and bounded, transcription remains credential-gated and real-provider-only, and no credentials or simulated provider results are introduced.
