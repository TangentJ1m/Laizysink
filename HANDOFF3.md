# Handoff - Remaining Problems

1. **Stale and event-scoped memory is not refreshed or wired into task reuse**
   - **Location:** `backend/memory_store.py` (`relevant_candidates`, `reuse`); `backend/dag_engine.py` (memory inheritance).
   - **Symptom / Error:** `reuse()` returns only fresh facts and excludes event-scoped facts unless date bounds are supplied. The DAG still calls `reuse(task_id, prompt)` without candidate review or event dates, so stale facts are not refreshed and event-specific facts are omitted.
   - **Context & Root Cause:** C1 added safe candidate/freshness APIs and made `reuse()` avoid silently using stale values. DAG and API wiring were outside C1's ownership and remain unchanged.
   - **Next Step:** Wire task planning to pass event date bounds, inspect relevant stale candidates, refresh them with live verified evidence before reuse, and expose the resulting freshness to the client.

2. **Memory overrides and refreshes lack server commands and authoritative client updates**
   - **Location:** `shared/src/com/onecaramel/laizysink/state/AppStateStore.kt` (`overrideMemory`, `refreshMemory`); `contracts/schema.json`; `backend/models.py`; `backend/main.py`.
   - **Symptom / Error:** The client can submit override/refresh requests and show command outcomes, but the current wire contract and server handle expiration only. No authoritative memory update is delivered for these operations, so client memory remains unchanged.
   - **Context & Root Cause:** C3 exposed request state without optimistic edits; A2's current contract/server work does not define or implement override/refresh or a memory-state update event.
   - **Next Step:** Define and implement the override/refresh commands, correlate their results, and return authoritative updated memory state after each operation.

3. **Memory updates cannot retain complete provenance**
   - **Location:** `backend/memory_store.py` (`_replace_value`); `backend/models.py` (`TemporalMemoryItem`); `contracts/schema.json` and Tiger Data table schema.
   - **Symptom / Error:** Override/refresh resets `created_at` to restart TTL while retaining the original `task_id`; the original creation time, update time, acting task/user, and refresh source URL cannot all be represented.
   - **Context & Root Cause:** C1 kept the existing memory model and persistence schema within its exclusive file scope, so no update/provenance fields or database migration were added.
   - **Next Step:** Extend the canonical model and schema with immutable creation provenance plus update/verification metadata, then migrate persistence and update the store operations.

4. **Memory is ephemeral unless Tiger Data is configured**
   - **Location:** `backend/memory_store.py` (`ConfiguredTemporalMemoryStore`, `TemporalMemoryStore`).
   - **Symptom / Error:** Without `TIGERDATA_DATABASE_URL`, memory exists only in process memory and is lost on backend restart.
   - **Context & Root Cause:** C1 retained the in-process fallback and did not configure or verify a durable provider.
   - **Next Step:** Configure a durable store for deployments that need cross-restart memory and verify persistence when provider verification is authorized.

5. **Kotlin task state does not fully consume or represent canonical events and fields**
   - **Location:** `shared/src/com/onecaramel/laizysink/state/Models.kt`; `shared/src/com/onecaramel/laizysink/state/AppStateStore.kt`; `shared/src/com/onecaramel/laizysink/network/LaizysinkSocketClient.kt` (`ServerEvent`, legacy adapter).
   - **Symptom / Error:** The typed model lacks `PARTIAL`, `provider_request_id`, and `retrieved_at`. The transport still projects canonical events into legacy values, loses snapshot semantics, and converts `PARTIAL` snapshots into errors.
   - **Context & Root Cause:** The transport and state work had separate ownership scopes, leaving canonical event handling and model parity incomplete.
   - **Next Step:** Add a canonical `ServerEvent` consumer, align Kotlin serialization fields and status rendering with the wire contract, replace state from authoritative snapshots, and retain the raw snapshot fallback for forward compatibility.

6. **Audio format checks do not fully validate media and Gemini was not live-verified**
   - **Location:** `backend/voice.py` (`_has_expected_audio_header`, `transcribe`).
   - **Symptom / Error:** Upload validation checks supported MIME types, size, and basic container signatures, but does not fully decode audio; real Gemini transcription behavior remains unverified.
   - **Context & Root Cause:** C2 implemented bounded validation and safe provider errors. The session explicitly did not run provider calls or tests.
   - **Next Step:** Add a decoder-level validity check if required, then verify the endpoint with authorized credentials and valid/malformed samples without logging audio or secrets.

7. **Client transcription state has no audio capture or upload path**
   - **Location:** `shared/src/com/onecaramel/laizysink/state/AppStateStore.kt` (`reportTranscriptionUploading`, `reportTranscriptionSucceeded`, `reportTranscriptionFailed`); `shared/src/App.kt` and platform audio integration.
   - **Symptom / Error:** The state store can report real transcription status and text, but does not capture audio or send it to `/api/transcribe`.
   - **Context & Root Cause:** C3 added reporting hooks only; audio capture/upload was outside its owned file and was not implemented in this work.
   - **Next Step:** Implement platform capture and upload, connect it to `/api/transcribe`, and feed only the returned transcript or actionable error into these state hooks.

8. **HITL completion does not resume a server work item**
   - **Location:** `backend/main.py` (`_handle_keychain_command`, `_pending_hitl_tabs`); `backend/dag_engine.py` (HITL work-item lifecycle).
   - **Symptom / Error:** `KEYCHAIN_AUTH_COMPLETE` records an opaque reference and returns an acknowledgement, but no HITL producer or resumable DAG work item consumes the completion, so the blocked branch cannot resume.
   - **Context & Root Cause:** The existing DAG does not emit a HITL request or expose a resume operation; Group A owned `main.py` only and left DAG changes out of scope.
   - **Next Step:** Add a real HITL work-item producer and resume operation, then connect validated completion to the matching task branch.

9. **Keychain completion can be accepted without a pending HITL tab**
   - **Location:** `backend/main.py` (`_handle_keychain_command`).
   - **Symptom / Error:** The handler rejects an unknown tab only when the task has at least one pending tab. If the pending-tab map is empty, an arbitrary `tab_id` with an opaque `vault://` reference is recorded and acknowledged.
   - **Context & Root Cause:** The current DAG has no HITL producer, leaving the pending-tab map empty in normal operation; the handler’s conditional validation permits this state.
   - **Next Step:** Require a matching pending HITL tab for every completion and validate the reference domain against that tab before recording it.

10. **Task attachments are rejected because ingestion is absent**
   - **Location:** `backend/main.py` (`CreateTask`, `_handle_create_command`, `/api/tasks`).
   - **Symptom / Error:** Any non-empty `attachments` list is rejected with an unsupported-attachments error; no document content reaches task planning.
   - **Context & Root Cause:** A1 confirmed the DAG has no attachment ingestion path and kept its changes within `main.py` ownership.
   - **Next Step:** Implement safe attachment upload/storage and document extraction, then pass extracted context into task planning before accepting attachments.

11. **The initial task snapshot omits planned branches**
   - **Location:** `backend/main.py` (`_publish_dag_event`, `_handle_create_command`); `backend/dag_engine.py` (`create_graph`).
   - **Symptom / Error:** The first `TASK_STATE_SYNC` after task creation contains an empty `nodes` list; nodes appear only as later branch callbacks arrive, so the initial snapshot does not show planned/running branches.
   - **Context & Root Cause:** The DAG’s `TASK_CREATED` callback carries the prompt but no initial graph. The server can assemble snapshots from observed events but cannot include branch records it has not received.
   - **Next Step:** Have the DAG emit its complete initial `TaskGraph` before research begins and publish that snapshot to the creator and task subscribers.

12. **Task snapshots are lost when the backend process restarts**
   - **Location:** `backend/main.py` (`tasks`, `subscribers`, `_keychain_completions`).
   - **Symptom / Error:** Task graphs and keychain completion records are process-local; a backend restart loses them, so reconnect cannot restore previous tasks.
   - **Context & Root Cause:** A1 kept persistence outside its route/lifecycle scope and documented the in-memory limitation.
   - **Next Step:** Persist task snapshots and completion state in a durable store, then rebuild WebSocket subscriptions from clients’ task-specific reconnects.

13. **Exa provenance fields are modeled but not populated**
   - **Location:** `backend/models.py` (`ExaCitation`); `backend/exa_client.py`; `backend/dag_engine.py` (citation construction).
   - **Symptom / Error:** `provider_request_id` and `retrieved_at` are optional and remain unset in emitted citations, so clients cannot show retrieval time or trace a citation to its provider request.
   - **Context & Root Cause:** A2 added optional provenance fields to the schema/models, but the current Exa/DAG path does not populate them.
   - **Next Step:** Capture provider request metadata and retrieval time in the Exa client and pass both through DAG citation construction.

14. **Rejected command results have schema/model validation mismatch**
   - **Location:** `contracts/schema.json` (`$defs.CommandResult`); `backend/models.py` (`CommandResult`).
   - **Symptom / Error:** The schema requires `message` when `accepted` is false, while the Pydantic model permits `accepted=False` with `message=None`.
   - **Context & Root Cause:** The current server supplies a message for rejections, but the model itself does not enforce the conditional rule declared by the schema.
   - **Next Step:** Add matching Pydantic validation or relax the schema, then keep both definitions and all server results consistent.

15. **Manual HITL completion has no shared/backend acknowledgment path**
   - **Location:** `shared/src/App.kt` (manual completion callback); `shared/src/com/onecaramel/laizysink/state/AppStateStore.kt` (`dismissHitl`, `completeKeychainAuth`); `contracts/schema.json`; `backend/main.py`.
   - **Symptom / Error:** A user can finish sign-in manually in the foreground browser, but the request cannot be authoritatively completed; the Android sheet keeps the request open unless the Keychain-backed path is used.
   - **Context & Root Cause:** D3 kept manual navigation from counting as success and did not send a fake `vault://` reference. The shared callback exposes only keychain completion/local dismissal; no distinct acknowledged manual-completion event is available.
   - **Next Step:** Add a task/tab-scoped manual-completion command and server acknowledgment, then clear the client request only after that acknowledgment.

16. **Keychain completion is not confirmed by the server before local dismissal**
   - **Location:** `shared/src/com/onecaramel/laizysink/state/AppStateStore.kt` (`completeKeychainAuth`); `shared/src/App.kt`; `contracts/schema.json`; `backend/main.py`.
   - **Symptom / Error:** After the user confirms sign-in, the client queues `KEYCHAIN_AUTH_COMPLETE` and clears the active HITL request without waiting for an authoritative server result. The UI can return while the backend task remains blocked or the command failed.
   - **Context & Root Cause:** D3 waits for biometric authorization, successful field injection, and explicit user confirmation, but its Android-only ownership cannot add or consume a server acknowledgment contract.
   - **Next Step:** Define a correlated success/failure response for `KEYCHAIN_AUTH_COMPLETE`, keep the request pending while it is in flight, and resume the task only after server confirmation.

17. **Browser reveal and Keychain host matching use different domain policies**
   - **Location:** `androidApp/src/com/onecaramel/laizysink/browser/HiddenWebViewPool.kt` (`matchesDomain`); `androidApp/src/com/onecaramel/laizysink/security/ZeroTrustKeychain.kt` (`matchesDomain`); `androidApp/src/com/onecaramel/laizysink/browser/AndroidHitlController.kt` (`canUseKeychain`, `isExpectedOrigin`).
   - **Symptom / Error:** The browser pool accepts the requested domain and its subdomains, while the Keychain requires an exact HTTPS host match. A request that opens on an authentication subdomain can therefore be revealed but have autofill and completion disabled.
   - **Context & Root Cause:** D1 retained suffix-based subdomain matching for reveal; D2 deliberately chose exact-host matching for credential protection. D3 gates credential actions through the stricter Keychain policy.
   - **Next Step:** Choose and document the canonical allowed-host policy, then align reveal, credential access, and redirect handling with explicit tests for apex, subdomain, and external identity-provider hosts.

18. **Android HITL behavior has not been verified on a device or by a build**
   - **Location:** `HiddenWebViewPool.kt`, `spotlight.js`, `ZeroTrustKeychain.kt`, `AndroidHitlController.kt`, and `MainActivity.kt` in `androidApp/`.
   - **Symptom / Error:** Compilation and runtime behavior remain unverified, including attached hidden WebView loading, spotlight alignment after layout changes, biometric-gated encryption/autofill, cancellation, and the anchored browser sheet.
   - **Context & Root Cause:** This work intentionally ran only source/diff review and `git diff --check`; no build, tests, emulator/device session, or biometric prompt was run.
   - **Next Step:** When verification is authorized, build the integrated branch and exercise reveal/hide, selector retry, biometric save/autofill/cancel, origin changes, and task completion on an Android device.

19. **Group D commits are integrated on a branch but not merged into `main`**
   - **Location:** Git branch `group-d-integration`; primary checkout branch `main`; Group D changes under `androidApp/`.
   - **Symptom / Error:** The Group D implementation is available on `group-d-integration`, while the primary `main` checkout still has pre-existing local edits in `MainActivity.kt` and `AndroidHitlController.kt` and was left untouched.
   - **Context & Root Cause:** The worker commits were replayed onto the current integration base in a separate worktree to preserve the primary checkout's uncommitted work.
   - **Next Step:** Reconcile or preserve the primary local edits, then merge `group-d-integration` into the intended integration branch.

20. **DAG resume state is lost on backend restart**
   - **Location:** `backend/dag_engine.py` (`TaskRun`, `_task_runs`, `_create_run`, `resolve_barrier`).
   - **Symptom / Error:** Barrier resolution raises `resumable DAG state is unavailable; this backend may have restarted` when the process-local run registry has been lost.
   - **Context & Root Cause:** Group B preserved completed branch outcomes and answers in `_task_runs` so a barrier resumes only newly unblocked branches. `TaskGraph` has no serialized plan, query, answer, or branch-attempt state, so that resume behavior is only durable for the lifetime of one process.
   - **Next Step:** Add an authoritative serialized execution-state model and persistence path, restore it when loading a task, and resume the affected branches after restart.

21. **Data barriers do not expose every blocked subtask**
   - **Location:** `backend/models.py` / `contracts/schema.json` (`DataBarrier`); `backend/dag_engine.py` (`_barriers`, `TaskRun.input_blockers`).
   - **Symptom / Error:** A barrier exposes only one `source_node_id`, even when the required input blocks multiple branches. The complete blocker set exists only in process-local DAG state and is unavailable to clients and persisted task snapshots.
   - **Context & Root Cause:** The current contract has no `blocked_node_ids` field. Group B kept the wire model unchanged and used the in-memory blocker map to resume the right branches.
   - **Next Step:** Add `blocked_node_ids` to the canonical barrier model/schema and Kotlin model, populate it from the plan, and include it in task snapshots and barrier events.

22. **Branch retry and failure details are not typed in the graph**
   - **Location:** `backend/models.py` (`WorkTask`); `backend/dag_engine.py` (`BranchOutcome`, `_run_branch`, `_node_summary`, `_build_graph`).
   - **Symptom / Error:** Retryability and attempt counts are private to the in-process `BranchOutcome`; the client sees failure text in summaries but cannot inspect structured retryability, error kind, or attempt count, and no branch retry operation is exposed.
   - **Context & Root Cause:** The current `WorkTask` schema has only identity, label, type, parent, and status. Group B retained failure details in process state and rendered a summary, but could not add retry fields or a retry command within the assigned DAG file.
   - **Next Step:** Extend the work-task contract with structured failure/retry metadata, persist it with execution state, and add a task-scoped retry operation that reruns only the selected eligible branch.

23. **Evidence merge ranks highlight term overlap but does not compare candidate options**
   - **Location:** `backend/dag_engine.py` (`_branch_sources`, `_build_graph`).
   - **Symptom / Error:** A citation is marked as a lead only when its Exa highlight contains more planned comparison terms. The graph does not extract and compare candidate options or establish which option best satisfies the criteria, so it cannot produce a fully supported recommendation or option-level alternative rationale.
   - **Context & Root Cause:** Group B deliberately kept the merge evidence-conservative and avoided claiming a winner from keyword overlap alone. The current Exa result and graph models do not carry structured candidate facts for cross-branch comparison.
   - **Next Step:** Add evidence-grounded candidate extraction with source-linked facts, compare candidates against the plan criteria, and retain explicit supporting and rejection evidence for each result.

24. **Exa publication dates are only carried in a display badge**
   - **Location:** `backend/exa_client.py` (`_citation`); `backend/models.py` (`ExaCitation`); `contracts/schema.json` (`ExaCitation`).
   - **Symptom / Error:** When Exa supplies `published_date`, the client appends `Published <date>` to `verification_badge`; there is no structured publication-date field for sorting, freshness logic, or consistent client display.
   - **Context & Root Cause:** Group B2 preserved only provider-supplied dates and avoided inventing refresh timestamps. The current citation contract has `retrieved_at` but no distinct publication-date field, and the Exa client does not populate publication date structurally.
   - **Next Step:** Add a structured optional `published_at` field to the canonical Python/schema/Kotlin citation models and populate it only when Exa returns a valid value; keep publication time distinct from retrieval time.

25. **Planner, Exa retrieval, and DAG behavior remain unverified with live queries**
   - **Location:** `backend/planner.py` (`plan`, `_fallback_plan`); `backend/exa_client.py` (`search`); `backend/dag_engine.py` (`create_graph`, `_execute_ready_branches`, `resolve_barrier`).
   - **Symptom / Error:** The session verified only diffs, whitespace, and Python syntax. Gemini plan quality/fallback behavior, live Exa highlights and timeout/error behavior, and end-to-end dependency, barrier-resume, and partial-result behavior have not been exercised.
   - **Context & Root Cause:** The Group B instructions prohibited builds, tests, and provider calls unless requested; no Gemini or Exa calls, builds, or tests were run.
   - **Next Step:** When authorized, run the backend build/test workflow and exercise varied prompts, missing-key/provider failures, timeouts, multi-branch barriers and resumes, and mixed-success tasks; use credentials only through the configured environment and do not log them.

26. **Home voice and attachment actions are not connected to platform capabilities**
   - **Location:** `shared/src/App.kt` (`App`, `HomeScreen`); Android and Desktop app call sites.
   - **Symptom / Error:** Voice and file buttons report that their capability is unavailable because `onVoiceInput` and `onPickAttachments` are optional and are not supplied by current call sites.
   - **Context & Root Cause:** E1 added prompt UI callbacks and graceful unavailable messages, but platform capture/upload and file selection are outside this screen integration.
   - **Next Step:** Implement platform voice capture/transcription and attachment selection, then pass callbacks into `App` and preserve selected URIs through task creation.

27. **Task Hub memory management cannot expire or refresh individual facts**
   - **Location:** `shared/src/App.kt` (`TasksScreen`, `MemoryManagerDialog`); `shared/src/com/onecaramel/laizysink/state/AppStateStore.kt`.
   - **Symptom / Error:** Manage lists sourced facts and freshness labels and offers only a global context reuse toggle; users cannot expire or refresh a fact from the Hub.
   - **Context & Root Cause:** E1 found no matching store operations in `AppStateStore`; the dialog intentionally avoids pretending a local toggle changes server-owned memory.
   - **Next Step:** Add authoritative expire/refresh operations and outcomes to the store and wire per-fact controls in the Hub.

28. **Completed task review has no merged recommendation or staged action payload**
   - **Location:** `shared/src/com/onecaramel/laizysink/ui/TaskOutcomeScreens.kt` (`CompletedTaskScreen`); `shared/src/com/onecaramel/laizysink/state/Models.kt` (`TaskGraph`).
   - **Symptom / Error:** The page can show returned node summaries, citations, and source alternatives, but cannot show a merged decision or stage a booking/email action; its available CTA opens a selected source for review.
   - **Context & Root Cause:** The current `TaskGraph` has no merged result or staged URL, so E2 kept the handoff transparent and did not invent a destination.
   - **Next Step:** Add evidence-backed merge and staged-action fields to the backend/client contract, then expose the staged target behind explicit user approval.

29. **Map HITL action cannot identify the tab associated with a work item**
   - **Location:** `shared/src/com/onecaramel/laizysink/ui/ExecutionMapScreen.kt` (`ExecutionMapScreen`, `InspectorSheet`); `shared/src/com/onecaramel/laizysink/state/Models.kt` (`WorkTask`).
   - **Symptom / Error:** A blocked HITL work item invokes a generic callback for the current active request; `WorkTask` carries no tab ID to route to the exact browser tab.
   - **Context & Root Cause:** E3 confirmed the available model has no tab identifier, so the map cannot associate a selected work item with its specific paused tab.
   - **Next Step:** Add the tab/request reference to the canonical work-item state and pass that identifier through the map callback to the host.

30. **Execution Map expansion jumps and displays at most six work-task satellites**
   - **Location:** `shared/src/com/onecaramel/laizysink/ui/ExecutionMapScreen.kt` (`ExecutionMapScreen`, `satellitePositions`).
   - **Symptom / Error:** Selecting another node repositions the layout without a smooth transition, and nodes with more than six work tasks show only six satellite chips in the canvas.
   - **Context & Root Cause:** E3 implemented direct pan/zoom and progressive disclosure within the standalone screen; the satellite layout caps its visible positions at six while the inspector lists all work tasks.
   - **Next Step:** Animate node position changes and provide a readable way to navigate or disclose every satellite when a node has more than six work tasks.

31. **Source cards do not render domain favicons**
   - **Location:** `shared/src/com/onecaramel/laizysink/ui/ExecutionMapScreen.kt` (`SourceCitationCard`); `shared/src/com/onecaramel/laizysink/ui/TaskOutcomeScreens.kt` citation/source cards.
   - **Symptom / Error:** Map source cards use a domain initial as an icon placeholder rather than the `favicon_url` image.
   - **Context & Root Cause:** E3 found no image loader available in the shared module and kept the UI dependency-free; the same limitation affects the shared source presentation.
   - **Next Step:** Add or connect a supported shared image-loading path and render the actual source favicon with a fallback for missing or failed images.

32. **Task Hub ordering cannot guarantee most-recent task priority**
   - **Location:** `shared/src/App.kt` (`prioritizedTasks`, `TaskGroup`); `shared/src/com/onecaramel/laizysink/state/Models.kt` (`TaskGraph`).
   - **Symptom / Error:** Tasks are ordered by status and their current map iteration index, with no task timestamp to guarantee newest-first ordering within a status group.
   - **Context & Root Cause:** E1 used the available live `TaskGraph` state, which has no creation/update timestamp; the index is only a fallback ordering.
   - **Next Step:** Add a server-provided task update/creation timestamp to the contract and sort each priority group by that timestamp.

33. **Keychain screen remains embedded in the App host**
   - **Location:** `shared/src/App.kt` (`KeychainScreen`, `App` route for `AppScreen.KEYCHAIN`).
   - **Symptom / Error:** Active, Completed, and Map use standalone screen composables, while Keychain remains a private screen implementation inside the host file.
   - **Context & Root Cause:** E2 delivered Active/Completed and E3 delivered Map; neither brief delivered a standalone Keychain screen, so E1 retained the existing host-level surface to keep the route working.
   - **Next Step:** Assign an owner to extract the Keychain surface into a reusable composable and keep `App.kt` limited to route and callback wiring.

34. **Integrated UI changes remain uncompiled and untested**
   - **Location:** `shared/src/App.kt`; `shared/src/com/onecaramel/laizysink/ui/TaskOutcomeScreens.kt`; `shared/src/com/onecaramel/laizysink/ui/ExecutionMapScreen.kt`.
   - **Symptom / Error:** The session did not establish that the combined Compose changes compile or behave at runtime.
   - **Context & Root Cause:** The group instructions explicitly prohibited builds and tests; source review and whitespace checks were the only verification performed.
   - **Next Step:** Run the Kotlin Toolchain build and tests when verification is requested, then resolve any compile or runtime issues found.
