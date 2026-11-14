# Group C — Voice transcription and recovery

## Scope
Own only `backend/voice.py`.

## Goal and brief
Make `/api/transcribe` a safe, credential-gated real Gemini transcription endpoint. Validate upload type/size, handle empty or malformed audio, timeouts, missing keys, and provider errors with actionable responses, and return transcription text only. Do not log audio or secrets. Preserve the specified recovery path when transcription is unavailable; do not fabricate transcripts or add reserved ElevenLabs/TigerData calls. Coordinate the response contract with A2/A3, but do not edit their files.

## Exclusive ownership
`backend/voice.py` only. No API main, config, dependencies, shared client, or UI.

## Git and completion
Use a dedicated branch/worktree. Check status before work and each focused commit; review diffs and do not amend other branches. Do not call Gemini or run tests/builds unless requested.

Done when audio handling is bounded and safe, failures are recoverable and clear, and only real configured Gemini output is returned. Report branch, commits, paths, and live-verification status.
