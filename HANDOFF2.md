# Laizysink review handoff 2

## Review scope and status

This is a reviewer-only gap list. It records implementation defects, incomplete behaviors, specification conflicts, and missing deliverables observed in the current checkout. It does not propose or apply code changes.

Reviewed materials:

- Repository `AGENTS.md` (swarm/build rules, architecture, protocol, six screen requirements, visual tokens, and acceptance checklist).
- All files under `ui_mock/`: six screen `code.html`/`screen.png` pairs, `laizysink_project_prd_product_brief.md`, `obsidian_kinetic/DESIGN.md`, and the additional PowerToys note.
- The supplied Junction/Exa challenge screenshot and the two PowerToys prompt/reply text files.
- Current shared Compose UI/state/network code, Android WebView/keychain implementation, backend, schema, project descriptors, and README.

No build, test suite, emulator, physical-device, backend, Exa, Gemini, database, or provider verification was run. `AGENTS.md` expressly says not to run provider calls unless requested. This is a static review, not a verified runtime report.

The checkout was already dirty when reviewed (`HANDOFF.md`, `MainActivity.kt`, `AndroidHitlController.kt`, deleted `backend/.env.example`, and untracked `ui_mock/`); those files were not changed as part of this review. The only intended addition is this handoff file.

## Executive review

The repository has a useful prototype foundation: YAML-based Kotlin Toolchain descriptors, shared Ktor WebSocket code, an Android WebView pool, a device-local AES-GCM store, Exa search code, eight-second Exa branch timeouts, and six Compose screen branches. Those pieces do not yet make the specified product work end-to-end.

The two must-fix blockers called out in the request are confirmed:

1. **A physical phone cannot use the current Android backend URL.** Android hardcodes `ws://10.0.2.2:8000/ws/tasks/new`, which is the Android Emulator host alias, not the host computer's LAN address. README's Uvicorn command binds to the default loopback interface. A phone and host therefore have no usable configured path as documented.
2. **The current UI does not match the supplied mockups.** It is a functional-looking generic Compose prototype with a shared header/footer and simple stacked cards. The mockups prescribe screen-specific hierarchy, mobile layouts, typography, controls, status/empty states, navigation, and content that are mostly absent.

The larger end-to-end blocker is the protocol and execution gap. The client and backend currently use `TASK_CREATED`/`NODE_UPDATED`/`DATA_BARRIER`/`HITL_BROWSER` events rather than the `TASK_STATE_SYNC`/`BARRIER_TRIGGERED`/`HITL_TAB_REQUIRED` protocol in `AGENTS.md`. The backend does not implement browser/HITL events or handle `KEYCHAIN_AUTH_COMPLETE`; `EXPIRE_MEMORY` is also not handled. Consequently, the Android WebView and keychain are not reachable through a real research execution, and a locally completed keychain interaction cannot unblock the backend graph.

## Severity guide

- **P0 — Demonstration/runtime blocker:** prevents physical-device use or breaks the central task-to-human-handoff loop.
- **P1 — Required product capability absent or misleading:** contradicts an explicit product, source-clarity, memory, security, or mockup requirement.
- **P2 — UI/quality/operational gap:** materially limits fidelity, usability, or supportability but does not alone block a basic prompt-to-Exa query.

## P0 — Real-phone backend connection is not configured

**Evidence:** `shared/src@android/Platform.android.kt:8-11` hardcodes `ws://10.0.2.2:8000/ws/tasks/new`. `README.md:37` documents `uvicorn main:app --reload`, which binds to loopback by default.

- `10.0.2.2` is an emulator-to-host alias. A physical Android device does not resolve it to the developer computer; it needs an address reachable on the device's network or a deployed endpoint.
- There is no Android runtime/build setting, preferences screen, intent extra, or documented device setup for selecting the backend URL. The JVM target has `LAIZYSINK_BACKEND_WS_URL`; the Android actual does not.
- The documented backend launch does not listen on the LAN interface. Even replacing the phone's URL with a LAN IP would leave the service unreachable under the documented command.
- The URL is plaintext `ws://` for all Android environments. `android:usesCleartextTraffic="true"` in `AndroidManifest.xml:8` allows cleartext app traffic, but there is no environment distinction between local demo and remote deployment.
- There is no phone-oriented connection diagnostic that distinguishes wrong host/IP, unavailable server, firewall/network isolation, and backend service errors. The connection message exposes the low-level exception but does not tell a judge/operator how to verify reachability.

**Required completion evidence:** a physical Android phone on the intended network can be configured to reach the backend; the server is bound/reachable at that address; connection health is visible; a prompt creates a task and returns live task updates on that same phone. Emulator success alone does not satisfy the requested real-phone requirement.

## P0 — WebSocket contract, task identity, reconnection, and synchronization gaps

**Evidence:** `shared/src/com/onecaramel/laizysink/network/LaizysinkSocketClient.kt:17-38`; `shared/src/com/onecaramel/laizysink/state/AppStateStore.kt:54-93,128-235`; `shared/src/com/onecaramel/laizysink/state/Models.kt:83-87`; `backend/main.py:56-103`; `contracts/schema.json` event definitions.

- **The protocol in the instructions is not the one implemented.** Instructions define `TASK_STATE_SYNC`, `BARRIER_TRIGGERED`, `HITL_TAB_REQUIRED`, `CREATE_TASK`, `RESOLVE_BARRIER`, `KEYCHAIN_AUTH_COMPLETE`, and `EXPIRE_MEMORY`. Implemented event enum is `TASK_CREATED`, `NODE_UPDATED`, `DATA_BARRIER`, `HITL_BROWSER`, `TASK_COMPLETED`, `ERROR`. The client will not deserialize the specified event names into its enum.
- `TASK_STATE_SYNC` is needed for a full authoritative snapshot on reconnect/re-entry; no such event exists. On a reconnect to `/ws/tasks/new`, the server has no task named `new` and sends no current task snapshot. The client's task maps are only in memory and the app does not re-fetch prior task IDs after process recreation.
- The server's existing-task handshake at `backend/main.py:60-61` sends a `TASK_CREATED` event whose payload is a complete `TaskGraph`. `TaskDagStore.apply` handles every `TASK_CREATED` as only `{prompt}` and creates a new `RUNNING` graph with no nodes, barriers, or memory. Thus even if a client connects to an existing task ID, the handshake discards all snapshot data.
- The shared client opens one socket using the platform URL and keeps one mutable server-side `task_id`. The transport is not explicitly connected to `/ws/tasks/{task_id}` for each task as specified; task association is changed by mutating the server handler's local ID after `CREATE_TASK`.
- Current node events are incremental and the client upserts nodes, but the blocked barrier event changes task status and adds one barrier without delivering a full canonical graph snapshot. The home/task hub can show partial or stale state until a terminal snapshot is sent.
- A normal socket close exits `events()` and sets `DISCONNECTED`, then the enclosing `while (isActive)` reconnects immediately without the exception-path delay. The reconnect path does not resubscribe to the task, restore the task ID, re-send pending mutations, or request state. A disconnect during a request can therefore lose it or leave UI state unsynchronized.
- `outgoing` is a single channel consumed by a single socket session. Messages queued when there is no collector, during reconnection, or after the channel closes have no durable acknowledgement/replay model. `trySend` only reports queue failure; there is no server acknowledgement or idempotency key to tell the user whether a prompt/barrier response was accepted.
- The UI sets `selectedTaskId` to `null` on create and waits for `TASK_CREATED` to select it. If the initial event is lost, the task has no selected screen state even though the UI already navigated to Active.
- Client code uses `HITL_BROWSER`; `AGENTS.md` requires `HITL_TAB_REQUIRED` with task/tab/url/selector/tooltip/domain. The mismatch currently prevents the required event from being surfaced.
- The REST contract is incomplete relative to the documented protocol: only task create/read routes exist, and the app primarily uses WebSocket. There is no client fallback to fetch task state after a stream failure.

**Required completion evidence:** documented client/server event names and payloads match the canonical schema; new and existing task attachment both work; reconnect emits/retrieves a full snapshot and resumes live updates; task IDs remain correct with multiple tasks; outbound commands are acknowledged or their failure is surfaced without silent loss.

## P0 — HITL/browser handoff cannot complete end-to-end

**Evidence:** `backend/dag_engine.py:40-150`, `backend/main.py:64-101`, `shared/src/com/onecaramel/laizysink/state/AppStateStore.kt:82,184-197`, `androidApp/src/com/onecaramel/laizysink/browser/AndroidHitlController.kt`, `androidApp/src/com/onecaramel/laizysink/browser/HiddenWebViewPool.kt`.

- The backend never constructs/emits `HITL_BROWSER`/`HITL_TAB_REQUIRED`. It does not create a browser work item, tab ID, staged URL, target selector, or tooltip. The Android WebView pool's only input is a backend HITL request; it is not connected to Exa branch execution.
- `backend/main.py` has no `KEYCHAIN_AUTH_COMPLETE` branch. `AppStateStore.completeKeychainAuth` sends that event and immediately removes the local pending HITL tab, but the backend ignores the event type. Server state cannot record the opaque vault reference, resume an operation, or transition an interrupted work task.
- Manual completion only hides/resumes the local view (`dismissHitl` / `onManualNavigationCompleted`); it sends no completion/resume message to the backend. The UI can say it returned to Active while the server graph remains blocked or unchanged.
- Backend model `WorkTaskType` includes `HITL_BROWSER` and `KEYCHAIN_REF`, but current `create_graph` only creates an `EXA_SEARCH` work task. There is no transition, authorization handshake, or graph update for either task type.
- The pool's automatic re-hide treats any navigation after the first loaded page as proof that manual authentication finished (`HiddenWebViewPool.kt:64-88`). Navigation may be a redirect, a failed-login reload, an intermediate challenge, or another page hop. There is no authenticated-session check or explicit user-completion state before the callback reports completion.
- App's described HITL screen is composed separately from the native WebView layer. `MainActivity` puts the WebView over the shared UI when visible, while the mock calls for a page viewport plus anchored bottom keychain sheet. The current overlay only shows a top banner and a return button (`MainActivity.kt:57-82`); it is not the mock's complete foreground browser/sheet composition.
- No foreground-tab exclusivity is enforced at the backend graph level. The Android pool hides a previous local view on reveal, but server state has no equivalent invariant or active tab synchronization.
- No real work ever stages an airline reservation/check-out URL, pre-fills dates/party/promo data, or asks for the final irreversible approval. The completion CTA that would initiate this is absent too.

**Required completion evidence:** one real execution emits a valid HITL request; Android opens and spotlights that exact tab; user biometric or manual completion sends the matching task/tab/ref or manual completion; backend records only opaque references and resumes the correct blocked branch; graph and phone stay synchronized. Any transaction still requires an explicit user approval.

## P1 — The current DAG is three searches, not the specified dual-layer execution graph

**Evidence:** `backend/planner.py:10-44`; `backend/dag_engine.py:40-150`; `backend/models.py:62-75`; `shared/src/com/onecaramel/laizysink/state/Models.kt:35-48`; `shared/src/App.kt:386-531`.

- There is no distinct `SubtaskNode` model in Python, Kotlin, or the contract. `WorkNode` is used as both a graph node and the parent of a single `WorkTask`, so the required high-level independent task nodes and low-level work-item satellites are not represented as two separate layers.
- Gemini (or deterministic fallback) creates exactly three generic search branches; each branch is represented with no parent IDs and one Exa search task. There are no `DECOMPOSE`, `DATA_BARRIER`, `MERGE`, `KEYCHAIN_REF`, or `HITL_BROWSER` work tasks in an actual graph.
- The graph does not model dependencies/barriers as edges. `parent_ids` is always `[]` in `create_graph`; `source_node_id` points at the first search branch regardless of which work needs the missing data.
- The model does not preserve branch query text, Exa retrieval timing, provider request IDs, selection score, structured option data, staged action, transaction state, or final merge payload. Consequently the visual map and completed audit cannot explain the actual execution path.
- The backend marks nodes completed as soon as each has citations and marks the entire task `COMPLETED` if any branch has a source. A partly failed graph can therefore be treated as a completed result without a merge/quality threshold that determines if the user has a defensible answer.
- If one branch is missing Exa configuration or times out it is failed, but there is no explicit overall partial-result contract. `TASK_COMPLETED` is emitted even for `FAILED` graph state. No recovery, retry, cancel, or branch-level retry affordance exists.
- A resolved barrier invokes `create_graph` from scratch with the original prompt plus user answer; it re-plans/re-searches all three branches instead of resuming the precise waiting branch set. The answer is stored as a single `trip_constraints` string, and all waiting branches/parameters are not tracked or separately released.
- The barrier detector only uses a regex for words like trip/flight/hotel/travel and the presence of month/year, party number, and budget. Other arbitrary tasks never get dynamic barriers; exact number/date parsing is weak; an answer such as "flexible" is not parsed into resolved date data. Barriers are generated after searches have completed and use the same generic choices, not Exa findings.
- There is no `CANCEL_TASK`, task removal, branch progress percentage, status timestamp, or restart-safe task engine.

**Required completion evidence:** separate graph node and work-item semantics; meaningful parent/dependency edges; task-adaptive barriers that hold only dependent branches; comparison/merge output with runner-ups and rationale; partial-failure behavior; a task snapshot that represents both graph layers and can drive all views.

## P1 — Exa grounding and citation presentation are incomplete or misleading

**Evidence:** `backend/exa_client.py:18-42`; `backend/dag_engine.py:100-129`; `shared/src/App.kt:386-410,505-531,576-620`.

- Exa search is real and requests `search_and_contents(..., highlights=True)`; no static seed data was found in the provider path. That is a good foundation, but search results are not converted into a real decision.
- The first Exa result is unconditionally labeled `selected=True`; every later result is labeled rejected with the generic text "Ranked below the leading result for this query." This is not a user/task-specific comparison or evidence-based rejection rationale. The UI presents these fields as a source-selection audit even though no selection criteria/merge occurred.
- Each node's summary says only how many Exa sources were found, and the rationale says no selection criteria were applied. This fails the mock's specific synthesis (e.g. ANA route, fare, guests, dates, best-balance reasoning) and the AGENT requirement for selected recommendation plus discarded alternatives.
- Citations use a generic `Live Exa result` badge. No `retrieved_at`/exact refresh time, official-source verification state, or source snapshot is stored. `verification_badge` cannot substantiate mock labels like `Official Statute`, `Live Index Data`, or `Refreshed today`.
- A favicon URL is synthesized through Google's favicon endpoint; no favicon asset is fetched/cached or guaranteed available offline. The source rows in Compose display domain text only and omit the favicon.
- Completed-task citations open through Android `ACTION_VIEW` in an external app (`AndroidHitlController.openExternalUrl`), whereas PRD/mock require an in-app source preview. There is no cached raw page/snapshot or excerpt provenance to prevent link rot/dynamic drift as required by PRD §6.
- There is no grouped comparison drawer. `NodeCard` lists source cards inline and `Completed` lists all sources without Selected/Leading vs Considered/Discarded groups.
- Search branches ask generic queries derived from the prompt; there is no task-type-specific source policy for official legislation, statistics, airline fares, restaurant hours, package/carrier status, nor structured extraction/verification.
- Source clarity is a 20% judged criterion in the supplied challenge screenshot. The screenshot lists five equally weighted criteria (Use of Exa, Usefulness, Clarity, Originality, Working Implementation: 20% each). `AGENTS.md` instead allocates 40% to usefulness/originality combined. That weighting conflict should be resolved against the challenge's actual five-item rubric before acceptance reporting.

**Required completion evidence:** all presented citation claims trace to real Exa result payloads; selected/rejected decisions have actual task-level criteria and reasons; each claim has source, exact highlight, domain/favicon, retrieval freshness, and click path; legal/financial conclusions have verified official evidence; no badge overstates what was checked.

## P1 — Temporal memory is not the specified shared, manageable, event-aware memory

**Evidence:** `backend/memory_store.py:9-109,111-330`; `backend/dag_engine.py:52,61-66,153-174`; `backend/main.py`; `shared/src/com/onecaramel/laizysink/state/AppStateStore.kt:114-125`; `shared/src/App.kt:269-302`.

- New memory is only recorded when a data barrier is answered, under the fixed key `trip_constraints`. Facts from completed results, user preferences, hotel/location choices, statutory findings, budgets, dates, or transactions are not extracted and saved.
- Event start/end dates are optional but never extracted or populated by the current graph engine. The intended temporal sliding window `[now - 7 days, now + 60 days]` / event relevance behavior from the supplied prompt/reply is not implemented; selection is a substring overlap of query tokens with memory key/value.
- Items older than the 60-day TTL are tagged `ENTIRELY_STALE` but still returned from `reuse`; the original prompt says entirely stale context should be excluded unless explicitly referenced. `STALE_QUERIED` is a timestamp tag only: no Exa refresh is triggered before reuse.
- `create_graph` places inherited memory in node context, but planner and Exa branch queries are built from the prompt alone. The context is not used to fill in follow-up task research in a meaningful way.
- There is no user-visible active-window grouping/title/count, no `Manage` flow, no manual refresh, no expire UI, and no task memory editing/override. The Tasks screen renders each fact as a plain list. Backend store methods `expire`/`expire_task` are not exposed in `main.py` and no WebSocket `EXPIRE_MEMORY` handler exists.
- `TemporalMemoryStore` in the client only projects `memory` included in task snapshots already held in its current in-memory task map. It cannot load recent global memory at startup, list memory not attached to a currently loaded task, or receive expiry/refresh updates.
- The user prompt expects memories to apply across separate task/subgraph paths. Current memory is process-local if Tiger Data is unconfigured, has no cross-user ownership/auth boundary, and is not exposed by REST. The README claims durable Tiger Data is configurable, but no first-run UI/config validation exists.
- There is no explicit user consent/choice indicator per task for inherited memory. `CREATE_TASK` always defaults to `use_active_memory=true` in the UI.

**Required completion evidence:** reusable facts are task-independent and time/event scoped; relevance and freshness change execution; stale values are refreshed before they affect a final answer; expired values are excluded unless requested; users can inspect, override, refresh, and expire them; restart/reload returns the correct memory state.

## P1 — Mock screen 1, Laizysink Home, is materially different and incomplete

**Reference:** `ui_mock/laizysink_home/screen.png` and `code.html`; PRD §4 Flow 1; `shared/src/App.kt:217-268`.

- The reference has the Laizysink brand header with a `Tabs Hidden • N Active` pill, `AUTONOMOUS AGENT TASKS`/Live heading, `Review all`, two compact priority cards, a centered assistant pulse/brand focal point, large prompt headline and explanatory copy, exactly three starter chips, sandbox security status, and a single bottom omnibar with attachment/send/mic controls.
- Current Home is a vertically stacked title, one paragraph, connection-status text, a row of three chips, a standalone Compose `TextField`, a separate full-width `Research this` button, then tasks. There is no centered prompt hero/pulse, bottom-anchored omnibar, mic, attachment picker, sandbox status strip, or mock layout hierarchy.
- Current chips are `Research cheap flights`, `Compare options`, `Summarize open articles`; `Track package delivery` is missing and `Compare options` is not in the reference. Chips only prefill the field; no action scaffolding or immediate execution behavior is defined.
- Voice is absent from the Compose screen even though Android requests `RECORD_AUDIO` and backend `/api/transcribe` exists. There is no recording state, permission request, audio capture/upload, Android SpeechRecognizer fallback, transcript insertion, silence detection, or auto-submit.
- Attachment behavior is absent: `submitPrompt()` calls `createTask(query)` with defaults; there is no attachment picker or persisted local URI list even though `AppStateStore.createTask` accepts attachment strings.
- Tasks are not ranked by the specified status priority and recency. `prioritizedTasks` needs an audit; the preview only takes two from its result and no `Live` carousel/context indicator appears in the screenshot.
- `Tabs Hidden` counts only client pool entries; that pool tracks only explicit HITL requests, not active hidden browsing sandboxes, so it cannot report the screenshot's active hidden-tab count.
- `Connected: Private Sandbox Mesh · Encrypted` is not represented as sandbox telemetry. Current `StatusLine` is generic backend connectivity and could imply a security posture not actually established.
- The mock's `Review all` link, priority routing, and prompt submission navigation are approximated, but there is no focus management when returning to Home or `+ New`.

## P1 — Mock screen 2, All Tasks Hub, is missing core organization and controls

**Reference:** `ui_mock/laizysink_all_tasks_hub/screen.png` and `code.html`; PRD §4 Flow 2; `shared/src/App.kt:269-309`.

- The mock is a dedicated page with a back button, centered `All Tasks`, indigo `+ New`, a prominent `Active Context Window` strip with event label/date/fact count and `Manage`, and ordered sections for Needs Attention, Working in Background, and Finished.
- Current screen has no dedicated back/header composition or context strip. It appends `Active context` after all task groups and lists every fact separately; there is no event title/date/count and no `Manage` control.
- Attention cards do not deep-link to the exact barrier/tab. All tasks route through `openTask`, which routes only by overall task status to Active or Completed. A blocked graph with a paused auth tab cannot jump to the specific interaction.
- The screenshot's in-progress task shows live progress (`65%`) and its work summary; `TaskGraph` has no progress field or per-branch percentage, and TaskCard only shows node/citation counts.
- Finished cards show outcome and recency. Current cards show prompt, generic status, and counts; there is no saved time, financial delta, active source count summary, or candidate outcome-specific card presentation.
- The screenshot includes an in-memory `JAL Mileage Login` task/verification state. This is only a design sample, not a requirement to seed it; production equivalent requires a real persisted HITL state and route.
- `+ New` only returns to Home; prompt focus and keyboard are not requested. This usability behavior should be present as described.
- Task lists are built from the in-memory map; there is no server list/history API or persisted task reload, so Finished is not an archive across app/backend restarts.

## P1 — Mock screen 3, Active Task, lacks the progress and barrier interaction design

**Reference:** `ui_mock/japan_trip_active_task/screen.png` and `code.html`; `shared/src/App.kt:310-409`, `backend/dag_engine.py:18-38,131-149`.

- The screenshot's header has back, task title/subtitle, hidden-tab pill, and a four-step horizontal stepper (`Plan`, `3 Paths`, `Needs Input`, `Book`) with emerald/amber/inactive states. Current Active shows task prompt/status and a `Map` button; there is no stepper or return header.
- The focal barrier card must say which paths it unblocks, present task-adaptive radio options, allow `Other details` with text and voice, and use a prominent `Continue Task` CTA. Current barrier has a generic title, three static backend options, plain text input, and `Send answer`; `allow_other_voice_text` only conditionally exposes text, not voice.
- The mock barrier options reflect live findings (`Nov 18–23 • 2 Guests • ~£1,500`, etc.). Backend `_barrier()` returns generic options (`Specific dates`, `Flexible dates`, `I'm not sure yet`) and is generated from a regex after Exa is done.
- The mock places a compact Live Exa Research card after the barrier, with source count, `View All`, favicon, background tab, exact quote, and retrieval time. Current Active renders all nodes inline, no dedicated research card/drawer/source count grouping, and no tab number.
- The screenshot has a sandbox footer naming paused tabs. Current screen shows a generic connection message and has no task-to-tab telemetry.
- A barrier answer is stored, but task graph branches are re-run wholesale; there is no proof all dependent branches unblock together or unrelated branches continue independently.
- Errors/partial results are shown as one text line, not inline retry/fallback state associated with branch and source.
- No accessibility/touch-target audit was done; the UI uses small text chips and generic controls without verifying the 48dp hit areas in the design guide.

## P1 — Mock screen 4, Spatial Execution Map, is only a basic node layout

**Reference:** `ui_mock/japan_trip_spatial_execution_map/screen.png` and `code.html`; PRD §4 Flow 3; `shared/src/App.kt:411-531`.

- The mock reserves about 58% of screen height for a dot-grid graph canvas and 42% for a bottom inspector sheet. Current map is an ordinary vertically scrolling column with a 280dp charcoal canvas followed by inspector/other node cards. It is not a pinned, two-tier bottom drawer and the graph can scroll away.
- Canvas model has only the three search WorkNodes; there is no distinct `Japan Trip` root, subtask tracks (`Find Flights`, `Accommodation`, `Local Transit`, `Final Booking`), or satellite work-task chips (`ANA & JAL Directs`, `Fare Comparison`, `vault://jal`). All current nodes have empty parents, so the rendered dependency graph contains no edges.
- Collapsed nodes show title/status only; required `+N items` counts are missing. All siblings are then duplicated as stacked cards underneath the selected inspector.
- `mapPoint()` positions nodes on a fixed radial formula. There is no collision/repulsion algorithm, outward motion, eased transition, or fanning of selected work-task satellites. Nodes can overlap at small sizes/high counts.
- Pinch zoom and drag handlers exist, and +/- buttons exist; there is no dot-grid, active-root chip, selected-node focus glow, persistent map header identity/status, or fit/reset behavior.
- The inspector shows a short summary/chips/work-task text/context/rationale/sources in one column. It does not implement immediately visible 2-line Tier 1 followed by independently scrollable Tier 2, official rich preview card, source favicon/freshness, or data-adaptive decision cards.
- A blocked-node action only calls `onBack` to Active. It does not preserve/pass `barrier_id`/tab ID or scroll/focus to the exact blocker. There is no route to Keychain for a browser-blocked node.
- `Completed` opens map without setting read-only audit state; map still exposes normal interactions/actions and has no `Re-evaluate` / `Proceed Next` semantics matching the sample.
- The sample screen shows metric-specific result details and source content. The current generic backend summaries cannot populate those details from actual structured result data.

## P1 — Mock screen 5, Keychain Login & Spotlight, has gaps in security and UI behavior

**Reference:** `ui_mock/reveal_tab_keychain_login_spotlight/screen.png` and `code.html`; PRD §4 Flow 4; `androidApp/src/com/onecaramel/laizysink/browser/HiddenWebViewPool.kt`, `AndroidHitlController.kt`, `security/ZeroTrustKeychain.kt`, `assets/spotlight.js`, `MainActivity.kt`.

- The WebView is retained at alpha zero and the spotlight script scrolls/overlays a selector; these are useful pieces. But the server cannot produce a request, so the feature is unreachable from the current graph.
- Mock calls for address/domain header and `Your Turn • 1/3`, visible full page viewport with a correctly placed spotlight, and anchored biometric bottom sheet. Current native overlay has only a generic top banner and return button; shared Keychain is a text/button screen behind the visible webview overlay.
- The screenshot exposes a masked account/domain and an action labeled `Autofill & Sign In with Biometrics`, plus manual fallback. Current UI shows no saved username/card and action text differs; `maskedUsername()` exists but the shared `Keychain` composable does not consume it. MainActivity displays it in the top banner only.
- `ZeroTrustKeychain.save()` exists but no UI calls it. The no-match path, manual credentials save opt-in, encrypted save confirmation, and credential management/removal are absent.
- Keystore key generation uses AES-GCM but does not set per-use biometric authentication on the key and `BiometricPrompt` is not bound to a `CryptoObject`. The prompt gates the current call path, but the stored encryption key itself is not configured as biometric-protected as implied by the product's "hardware-backed biometric keychain" claim.
- Credential injection converts `CharArray` to an immutable `String` for JavaScript (`HiddenWebViewPool.kt:120-130`) and does not clear that String copy. The secret remains local to Android, but the implementation does not meet a strict transient-memory zeroization claim.
- Injection assumes one selector resolves to a form/input with a discoverable username and password field. It does not handle selectors that identify a container/button, iframe, shadow DOM, nonstandard login fields, passkeys, MFA, CAPTCHA, or a site that requires explicit user submit. Failed field matching only returns `null`.
- `spotlight.js` measures the target once, builds a polygon cutout and tooltip. It does not recalculate target bounds after the smooth scroll, viewport resize, orientation change, keyboard appearance, or page layout shift; it silently returns when selector is absent. The reference requires the highlighted target to remain precisely aligned.
- The overlay is in-page and `pointerEvents:none`, but target remains clickable only where cutout exposes it; verify overlay stacking/clip path in real WebView and maintain focus/accessibility/keyboard behavior. No visible error or retry is presented when selector lookup fails.
- `matchesDomain()` checks a URL against the event-supplied domain but there is no server trust/authentication or per-task authorization around a URL request. The client allows both HTTP and HTTPS (`isSafeWebUrl`); the expected login/credential flow should not expose saved credentials to an untrusted cleartext origin.
- Any subsequent navigation is treated as successful manual authentication; a failed login or challenge can re-hide too early. No explicit success signal, session confirmation, or further-input detection exists.
- No complete 2FA/CAPTCHA flow or manual pause/resume state exists. A browser tap can be hidden, but the backend work item and sibling execution state are not notified.

**Required completion evidence:** the real request is surfaced; browser origin is validated; selector failures are recoverable; spotlight tracks actual target through layout changes; biometric key access is actually protected by a biometric-gated cryptographic operation; no-match save/manual path exists; task resumes only after an explicit successful/authenticated state; raw credentials never enter network events/logs/LLM.

## P1 — Mock screen 6, Completed Task Page, is missing the audit and handoff payload

**Reference:** `ui_mock/helsinki_rent_increase_completed_task/screen.png` and `code.html`; PRD §4 Flow 5; `shared/src/App.kt:575-596`.

- The reference is a structured legal/financial deliverable with a four-stage completion stepper (`Contract`, `Finlex`, `Index`, `Draft`), verified finding badge, statute reference, breach verdict, explanatory synthesis, monthly delta impact cards, citations, draft/outbox metadata, attached PDF match, `Open Drafted Reply Email`, and `Inspect Execution Map`.
- Current Completed is a task prompt, status badge, per-node generic summary, and source cards plus `Inspect execution map`/`Start another task`. No legal verdict, financial impact, extracted contract terms, stage history, draft, attachment, match result, or email handoff can be represented by current `TaskGraph`.
- There is no completed-task title/summary/merged decision payload distinct from the branch summaries; no runner-up alternatives; no source timestamp/snapshot metadata; no source preview sheet.
- No `Open Reservation Tab` / `Open Drafted Reply Email` CTA exists. `openTask` navigates a completed status into this page, but nothing can stage or complete the actual user-facing action.
- The route to Map exists, but it is not read-only audit mode. No route or execution result preserves whether the task can be acted upon, has a pending approval, or is purely informational.
- Active memory/freshness banner and edit/override/re-run are absent. A stale inherited node never triggers a refresh before completion.
- The specific legal mock includes official Finnish Finlex and Statistics Finland data and transparent calculation (`€1,200 → €1,344` vs `€1,225.20`, savings `€118.80/mo`). Current Exa pipeline does no legal verification or arithmetic validation and must not present generic source count as a verified statutory verdict.

## P1 — Important end-user/product features from prompt, PRD, and reply are absent

### Voice and prompt ingestion

- Android manifest has `RECORD_AUDIO`, backend offers `/api/transcribe`, but there is no audio capture/request implementation or fallback in the client.
- Prompt submit does not include file attachments or user memory selection. No local document ingestion pipeline exists for the legal contract/PDF scenario.
- No streaming voice-to-DAG, silence detection, interruption/cancel behavior, transcript correction, permission-denied recovery, or Gemini transcription fallback is exposed.

### Browser-as-agent behavior

- Search-only branches do not browse websites through background WebViews or prepare an action. The prompt's "push as far as possible without user involvement" and "everything that can run in background already should" are not met by displaying Exa sources alone.
- No multi-step website navigation, DOM/content extraction, page interaction, CAPTCHA detection, login detection, form staging, reservation/email drafting, user approval, or post-approval completion exists.
- Desktop target is configured, but its platform actions are no-ops for HITL/auth/external URL by default and there is no desktop browser equivalent. The supplied reply's Windows/scrcpy option is only a development/demo suggestion, not a product desktop handoff implementation.

### Event and task operations

- No progress/cancel/retry task actions, network retry indicator, per-source loading state, branch timeout display, or recovered-from-offline state.
- No read-only local archive/offline browsing for completed graphs/drafts, despite PRD §6.
- No user authentication/account isolation on the FastAPI service. `tasks` and `subscribers` are process-global dictionaries keyed only by unguessable-ish task IDs; APIs/WebSockets do not authorize task ownership. This is material if the service leaves localhost.

### Optional tracks/integrations mentioned in the reply

- The response suggests Gemini 2.5 Flash, Tiger Data, Entire, ElevenLabs, and possibly additional Junction challenge tracks. Current code has Gemini planning/transcription and optional Tiger Data persistence; ElevenLabs is explicitly reserved/unimplemented per `AGENTS.md` and README. Entire is not present in runtime code. Treat these as optional roadmap items unless they become an explicit accepted requirement; do not represent them as integrated or as a condition for the core Exa-track build.
- The Junction challenge screenshot's actual listed judging criteria are five equally weighted 20% categories. The response's multi-track stacking strategy and AGENT's altered combined 40% weighting should not distract from a working live Exa, source-transparent, useful demo.

## P1 — Visual parity and design-system defects across all screens

**Evidence:** `shared/src/App.kt:62-75,146-213,622-690`; `ui_mock/obsidian_kinetic/DESIGN.md:139-215`; `AGENTS.md` §5; all six screen PNGs.

- A shared app shell always renders the same top title/tab pill and bottom row of six destinations. None of the six screenshot screens uses that navigation layout; they have distinct headers and navigation affordances. The always-visible screen-name shortcuts also expose implementation screen names and bypass the specified navigation matrix.
- UI controls/content are generic Material3 `Text`, `Button`, `TextField`, `RadioButton` components. Screens lack the mock's iconography, hierarchy, branded pulse, graph visual, progress bar, glowing indigo controls, source favicon rows, anchored bottom composer/drawer, and status-specific card treatments.
- Canonical colors are declared in `App.kt`, but `Border` is largely used for graph lines rather than 1dp borders around elevated cards. The actual mock HTML palette also differs (`#0D141D`, `#C0C1FF`, green `#4EDEA3`, and several Material surface values) from AGENT/PRD canonical tokens (`#0D0F14`, `#6366F1`, `#10B981`). This is a source-of-truth conflict: approve one palette and make screenshot/HTML/design text consistent before calling parity complete.
- There are no Inter/JetBrains Mono font resources or explicit Compose `FontFamily` usages. Default system typography is used. URL, `vault://`, status badges, and metric chips are not set in JetBrains Mono as required.
- Target mock viewport is Android 412×915 dp, but current screen geometry is not built to those proportions; Home and task pages use a general scroll column; map canvas fixed 280dp; shared app has fixed padding and persistent navigation. Keyboard, system bars, screen sizes, landscape, and larger font accessibility have not been addressed against the reference.
- The mock has touch-friendly controls; current tiny bottom navigation labels, source URL text, and status text are not verified against the 48dp touch target requirement in DESIGN.md.
- Mock HTML micro-interactions (radio selection, button response, animated node/layout, focus) are not implemented beyond Material defaults. The map's selected node changes without animated layout; no home pulse or bottom sheet transition exists.
- Empty/loading/error/disconnected states are not designed to the mock. Several are plain `EmptyState`/text messages and the app offers no actionable retry.

## P2 — Configuration, build, and product documentation follow-up

- `.env.example` is absent in this checkout although `AGENTS.md` and README refer to it. Existing status shows it deleted; verify whether that deletion is intentional before treating onboarding as complete. Without it, backend credentials/settings onboarding is not self-documenting.
- README tells the operator to run Uvicorn with reload but not a device-reachable bind address, firewall/network prerequisites, Android URL setup, or a safe production TLS path.
- `AGENTS.md` says active Kotlin targets are Android and Desktop JVM, while `project.yaml` says JVM/Android, but repository also includes `webApp/`, `iosApp/`, WASM/iOS platform sources/tests. The active-module boundary should be explicit and no unsupported target should be represented as active or required.
- `AGENTS.md` describes Kotlin build commands and Temurin JDK 25; this review did not execute them. Build/contract parity remains unverified, especially the mismatch between EventType declarations and the documented canonical WebSocket event names.
- The repository does not currently expose a single source of truth linking each mock state/interaction to the implementation acceptance checks. Completion should be tracked against this handoff plus the specific PRD/mock criteria rather than only successful compilation.

## Source conflicts and interpretation notes

1. **Challenge weights:** the attached challenge page says Use of Exa 20%, Usefulness 20%, Clarity 20%, Originality 20%, Working Implementation 20%. `AGENTS.md` states Use of Exa 20%, Clarity 20%, Working Implementation 20%, and Usefulness & Originality 40%. Treat the screenshot as the observed event rubric and resolve the internal doc discrepancy.
2. **Visual palette:** AGENTS/PRD call for `#0D0F14`, `#161922`, `#252A37`, `#6366F1`, `#10B981`, `#F59E0B`; mock HTML/screen exports use a darker blue canvas/surfaces and brighter lavender/green values. This prevents literal token parity without an explicit precedence decision.
3. **Project targets:** user/AGENTS say Android and Desktop JVM are active; prompt/reply allows a PC port if time permits. Treat Android as primary and Desktop as secondary active target; iOS/Web scaffolds are not evidence of finished product support.
4. **Provider integration claims:** real Exa search code exists and missing-key handling does not use seeded provider output. However, no live provider calls were authorized/performed in this review, and no claim of a verified live query should be made based on this static inspection.
5. **Optional sponsor integrations:** Tiger Data is implemented as an optional PostgreSQL-compatible memory store; ElevenLabs remains explicitly reserved and unimplemented. The strategic reply's optional ideas are not equivalent to a shipped product requirement.

## Acceptance checklist for whoever implements the handoff

Use this as the re-review checklist; these are observable outcomes, not implementation tasks already completed.

### Device/network and protocol

- [ ] A physical Android device can connect to the configured backend without emulator-only addressing.
- [ ] Backend listens at the configured reachable interface; connection failures and recovery are visible and actionable.
- [ ] Client/backend/schema use the same event names, casing, payloads, enum values, and task IDs.
- [ ] Reconnect/relaunch restores a full task snapshot; task state can be loaded for existing IDs.
- [ ] Two or more tasks can independently receive updates and accept barrier/HITL responses without cross-task event leakage.
- [ ] Commands have observable acceptance/failure behavior; disconnects do not silently lose prompt or barrier answers.

### Live task execution, sources, and HITL

- [ ] Arbitrary typed and spoken user queries execute through real Exa evidence, with strict eight-second per-branch timeout and visible branch-level failure/recovery.
- [ ] Search findings are compared using task-specific criteria; recommendation and alternatives are evidence-backed with reasons.
- [ ] Every displayed factual claim can be traced to an actual source URL and exact Exa highlight; freshness and source status are accurate.
- [ ] A real blocked task can produce a barrier based on missing required fields, use helpful task-adaptive options, and resume all dependent branches.
- [ ] A real task can stage a URL and emit the documented HITL event; at most one tab is foregrounded and the correct domain/target is shown.
- [ ] Authentication/manual completion is returned to the server and resumes the right branch only on a valid completion signal.
- [ ] Saved credentials remain local; biometric authorization gates access to key material; no-match/manual-save flow and failure recovery exist.
- [ ] Final booking/email/irreversible action remains staged until the user explicitly approves it.

### Memory and completed-task outcomes

- [ ] Follow-up tasks reuse relevant event-scoped facts across graph roots and show what was inherited.
- [ ] TTL behavior distinguishes USED, STALE_QUERIED, and ENTIRELY_STALE; stale facts are refreshed before reliance; expired facts are not silently reused.
- [ ] User can manage, override, refresh, and expire memory and see the effect on the next task.
- [ ] Completed results retain merge rationale, alternatives, source snapshots/metadata, memory freshness, and available staged handoff.
- [ ] The legal case supports user document input, official source verification, calculation breakdown, draft result, attachment provenance, and draft-opening CTA without inventing evidence.

### Screens and design

- [ ] Each of the six screens is visually checked side-by-side against its `ui_mock/*/screen.png` at the specified phone viewport.
- [ ] Each screen's named interactions in its `code.html` and PRD/AGENTS navigation matrix work with live store data (no hardcoded demo facts masquerading as results).
- [ ] Home has an anchored omnibar with voice/attachment/send; tasks hub shows event memory and three recency/status buckets; Active shows stepper/barrier/Exa; Map has radial progressive disclosure plus a two-tier inspector; Keychain has a true reveal/spotlight/biometric sheet; Completed has a sourced decision and human handoff.
- [ ] Colors have one accepted source of truth; Inter/JetBrains Mono roles, typography scale, borders, 48dp targets, safe areas, and responsive behavior are checked.
- [ ] Loading, empty, error, offline, permission-denied, no-key, timeout, partial-result, selector-missing, biometric-cancel, and task-completed states are designed and actionable.

## Review boundary

The code contains promising foundations but does not currently satisfy the real-phone, protocol, HITL, memory-management, full DAG/merge, or six-screen visual/behavior specifications. This handoff records the gaps for implementation and later verification; it does not claim any of them have been fixed or tested.
