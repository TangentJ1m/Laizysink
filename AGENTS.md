# AGENTS.md — Laizysink (Swarm Build & Architecture Specification)

## 1. Project Identity & Core Philosophy
**Laizysink** is an AI-native, prompt-first mobile browser ("The Internet Butler") built for **Junction 2026 (Exa Track — "The Internet Butler / Your Personal Assistant")**.

* **Core Paradigm ("Post-Tab Browsing"):** The home screen is a prompt and voice interface. Browser tabs (`WebView` instances) run silently in the background (`hidden` state) while an asynchronous backend Directed Acyclic Graph (DAG) agent researches, compares, and stages actions across the live web using **Exa** and **Gemini**. A browser tab only surfaces to the user when human intervention (authentication, CAPTCHA, or final payment/checkout approval) is strictly required.
* **Primary Target:** Kotlin Multiplatform + Compose Multiplatform for Android and Desktop JVM, built with Kotlin Toolchain YAML descriptors (no Gradle or Maven build files), targeting **Eclipse Temurin JDK 25**. Pair the client with an asynchronous **Python FastAPI** orchestration backend. Android-only integrations use Android `WebView`, Android Keystore, and `BiometricPrompt`.
* **Hackathon Judging Priorities (Must Be Visible in Product):**
  1. **Use of Exa (20%):** Ground all research in live web data via Exa (`search_and_contents` with `highlights`), proving answers come from live URLs rather than static LLM training weights.
  2. **Clarity in Showing Sources (20%):** Every task and graph node must expose clickable source citations, domain favicons, exact highlighted quotes, and rejected alternatives with reasons.
  3. **Working Implementation on Live Queries (20%):** The system must handle arbitrary, unpredictable live queries typed or spoken by judges in real time (with strict per-branch timeouts so one slow site never blocks the graph).
  4. **Usefulness & Originality (40%):** Progressive-disclosure spatial execution map, temporal sliding-window memory across tasks, and zero-trust biometric keychain autofill with DOM spotlight guidance.

---

## 2. Multi-Agent Swarm Orchestration Protocol

When initialized in a blank repository, the lead should scaffold the canonical contract before parallel implementation. In JetBrains Air, use isolated Git Worktree tasks; never assume a `/swarm` command exists. Keep ownership aligned with the real Kotlin Toolchain module paths:

| Agent | Ownership | Responsibilities |
| :--- | :--- | :--- |
| Lead / Contract Architect | `/AGENTS.md`, `/contracts/**`, `/README.md`, `/project.yaml` | Owns event schema and contract parity; integrates stacks and verifies builds. |
| Backend DAG & Exa Engineer | `/backend/main.py`, `/backend/models.py`, `/backend/planner.py`, `/backend/dag_engine.py`, `/backend/exa_client.py` | FastAPI, Gemini planning, parallel Exa branches, WebSocket events. |
| Memory & Voice Engineer | `/backend/memory_store.py`, `/backend/voice.py` | Temporal context, freshness, Gemini transcription. |
| Compose UI Engineer | `/shared/src/App.kt`, `/shared/src/com/onecaramel/laizysink/state/**`, `/shared/src/com/onecaramel/laizysink/network/**` | Compose screens, shared state, Ktor transport. |
| Android Platform Engineer | `/androidApp/src/com/onecaramel/laizysink/browser/**`, `/androidApp/src/com/onecaramel/laizysink/security/**`, `/androidApp/assets/**` | Hidden WebViews, spotlight script, device keychain and biometric integration. |

### Swarm Execution Waves

1. **Wave 0 ? Foundation:** Lock `/contracts/schema.json`, Pydantic models (`/backend/models.py`), and Kotlin serialization models (`/shared/src/com/onecaramel/laizysink/state/Models.kt`). Keep `project.yaml` and module descriptors declarative.
2. **Wave 1 ? Parallel Work:** Use isolated Air Git Worktrees with the ownership above. If worktrees are unavailable, work sequentially in this checkout; do not imply shared-folder edits are isolated.
3. **Wave 2 ? Integration:** Wire Android/Desktop Ktor clients to FastAPI `/ws/tasks/{task_id}` and verify prompt, parallel Exa branches, data barriers, citations, and HITL handoff.
4. **Wave 3 ? Hardening:** Enforce 8-second branch timeouts, graceful missing-key handling, and transcription recovery. Never use seeded or fabricated data as live provider output.

### Worker Git Workflow

Worker briefs are in `WORKER_1.md` through `WORKER_5.md`. Each worker must work in a separate Git worktree and branch with the exclusive path ownership listed in its brief. Check `git status --short --branch` at start and before commits. Commit each coherent, reviewable slice with a focused message; do not accumulate all work into one macro-commit or amend another worker's commit. Integrate only reviewed commits. Do not claim worktrees are isolated if workers share one checkout.

### Real Provider and Credential Policy

Use real provider APIs for provider verification, never mock responses, seeded examples, or fabricated citations. Never run verification or provider calls unless the user/lead has requested them. Keep credentials in ignored `backend/.env` or the process environment; never commit or log secrets. Exa citations must originate from Exa. A Gemini planning fallback may create search queries, but it must never stand in for Exa evidence. `ELEVENLABS_*` and `TIGERDATA_*` settings are reserved until their integrations are explicitly implemented.

---

## 3. Repository Directory Structure

```text
laizysink/
??? AGENTS.md
??? README.md
??? project.yaml                 # Active modules: androidApp, desktopApp, shared
??? kotlin / kotlin.bat          # Kotlin Toolchain wrappers
??? libs.versions.toml
??? contracts/schema.json        # Canonical REST and WebSocket schema
??? shared/                      # KMP library, active Android + JVM targets
?   ??? module.yaml
?   ??? src/App.kt               # Current six-screen Compose prototype
?   ??? src/Platform.kt
?   ??? src/com/onecaramel/laizysink/state/Models.kt
?   ??? src/com/onecaramel/laizysink/network/LaizysinkSocketClient.kt
?   ??? src@android/Platform.android.kt
?   ??? src@jvm/Platform.jvm.kt
??? androidApp/                  # Active Android application
?   ??? module.yaml
?   ??? src/AndroidManifest.xml
?   ??? src/MainActivity.kt
?   ??? src/com/onecaramel/laizysink/browser/HiddenWebViewPool.kt
?   ??? src/com/onecaramel/laizysink/security/ZeroTrustKeychain.kt
?   ??? assets/spotlight.js
?   ??? res/
?   ??? proguard-rules.pro
??? desktopApp/                  # Active Desktop JVM app
?   ??? module.yaml
?   ??? src/main.kt
??? backend/
?   ??? requirements.txt
?   ??? config.py                  # Local backend/.env loading; no secret values
?   ??? .env.example               # Names-only provider configuration template
?   ??? main.py
?   ??? models.py
?   ??? planner.py
?   ??? dag_engine.py
?   ??? exa_client.py
?   ??? memory_store.py
?   ??? voice.py
?   ??? test_api.py
?   ??? test_workflows.py
??? iosApp/                      # Existing scaffold, not listed in active project.yaml
??? webApp/                      # Existing scaffold, not listed in active project.yaml
```

The Kotlin build is Kotlin Toolchain only: configure products and dependencies in `project.yaml`, `module.yaml`, and `libs.versions.toml`. Do not add Gradle build scripts/wrappers or Maven POMs. `shared/src/` is common KMP code; Android platform code belongs under `androidApp/src/` and Android assets under `androidApp/assets/`. Use package `com.onecaramel.laizysink`.

The active KMP targets are Android and Desktop JVM. `shared/module.yaml` and `desktopApp/module.yaml` pin Eclipse Temurin JDK 25. Build from the repository root with `kotlin.bat build` on Windows or `./kotlin build` on macOS/Linux. Run `kotlin.bat test` / `./kotlin test`; module runs use `kotlin.bat run -m androidApp` or `-m desktopApp`.

---

## 4. Canonical Data Contracts & Shared Client Stores

Every screen in the Android client is a reactive projection of four shared `StateFlow` stores inside `AppStateStore.kt`:
1. **`TaskDagStore`:** Synced via WebSocket; holds all tasks, simplified stepper milestones, Subtask Nodes, tethered Work-Tasks, active Data Barriers, and Exa citations.
2. **`BackgroundWebViewPool`:** Tracks active hidden `WebView` instances (`tab_id`, `task_id`, `url`, `status`) and ensures at most one `WebView` enters the foreground during a Human-in-the-Loop (`HITL`) interrupt.
3. **`TemporalMemoryStore`:** Holds active event context windows (e.g., `Japan Trip: Nov 18–23`) and extracted user facts with freshness tags.
4. **`ZeroTrustKeychain`:** Hardware-backed local vault (`Android Keystore + AES-GCM encrypted SharedPreferences). Raw credentials **never** leave the device or enter LLM prompts; the backend graph stores only opaque references (`vault://jal.co.jp`).

### Core Schema Definitions (`backend/models.py` / `Models.kt`)

```json
{
  "WorkTask": {
    "id": "wt_01",
    "parent_node_id": "node_flights",
    "task_type": "EXA_SEARCH | DATA_BARRIER | KEYCHAIN_REF | HITL_BROWSER",
    "label": "Exa: ANA & JAL Directs",
    "status": "RUNNING | BLOCKED_USER | COMPLETED | STALE"
  },
  "SubtaskNode": {
    "id": "node_flights",
    "task_id": "task_japan",
    "title": "Find Flights",
    "status": "RUNNING | BLOCKED_USER | COMPLETED | WAITING",
    "freshness_tag": "USED | STALE_QUERIED | ENTIRELY_STALE",
    "parent_ids": ["node_root"],
    "plain_summary": "Picked ANA Direct Return (£640/person) for Nov 18–23, 2 guests. Best balance of price and direct flight time.",
    "highlight_chips": ["£640 / person", "Direct • 14h 10m", "24h Free Hold"],
    "inherited_context": {
      "Dates": "Nov 18–23",
      "Guests": "2",
      "Budget": "£1,500",
      "Keychain": "vault://jal.co.jp"
    },
    "decision_rationale": "Selected ANA NH212 Direct (£640/pp). Discarded British Airways (£715 — £75 over target) and 1-stop routes (+4h layover).",
    "work_tasks": ["WorkTask"],
    "sources": ["ExaCitation"]
  },
  "ExaCitation": {
    "id": "cit_01",
    "domain": "ana.co.jp",
    "url": "https://www.ana.co.jp/en/gb/",
    "title": "ANA Official Fare Rules",
    "highlighted_quote": "Direct London Heathrow to Tokyo Haneda return £640; free 24h fare hold.",
    "verification_badge": "Live Verified | Official Statute | Refreshed 5m ago",
    "selected": true,
    "rejection_reason": null
  },
  "DataBarrier": {
    "barrier_id": "bar_01",
    "task_id": "task_japan",
    "source_node_id": "node_hotel",
    "badge_text": "Quick Input Needed • Unlocks Flights & Hotel",
    "question": "Confirm your dates and budget so I can finish comparing options:",
    "options": [
      "Nov 18–23 • 2 Guests • ~£1,500 budget",
      "Nov 20–25 • 1 Guest • ~£1,200 budget",
      "Flexible in November • Mid-range"
    ],
    "allow_other_voice_text": true
  }
}
```

### WebSocket Protocol (`/ws/tasks/{task_id}`)
* **Client $\rightarrow$ Server:**
  * `CREATE_TASK`: `{ "prompt": string, "attachments": list[string], "use_active_memory": bool }`
  * `RESOLVE_BARRIER`: `{ "task_id": string, "barrier_id": string, "answer": string }`
  * `KEYCHAIN_AUTH_COMPLETE`: `{ "task_id": string, "tab_id": string, "keychain_ref": "vault://..." }`
  * `EXPIRE_MEMORY`: `{ "memory_id": string }`
* **Server $\rightarrow$ Client:**
  * `TASK_STATE_SYNC`: Full `TaskState` snapshot whenever any branch mutates.
  * `BARRIER_TRIGGERED`: Emits `DataBarrier` when parallel branches hit missing info.
  * `HITL_TAB_REQUIRED`: `{ "task_id": string, "tab_id": string, "url": string, "target_selector": string, "tooltip_text": string, "domain": string }`

---

## 5. Visual Design System Tokens (Strict Adherence)

All 6 screens must share an identical dark-mode aesthetic:
* **Canvas Background (`BgObsidian`):** `#0D0F14`
* **Elevated Card / Sheet Surface (`SurfaceCharcoal`):** `#161922`
* **Subtle Divider / Card Border (`BorderSubtle`):** `#252A37` (`1.dp`)
* **Primary Brand Accent (`AccentIndigo`):** `#6366F1`
* **Success / Verified / Done (`StatusEmerald`):** `#10B981`
* **Blocked / Needs Input / Your Turn (`StatusAmber`):** `#F59E0B`
* **Primary Text (`TextPrimary`):** `#F3F4F6`
* **Secondary Muted Text (`TextSecondary`):** `#9CA3AF`
* **Typography:** `Inter` (geometric sans-serif) for all headings, body copy, and MCQ pills; `JetBrains Mono` for URLs, `vault://` keychain pointers, status badges, and metric tags.

---

## 6. Exhaustive Screen-by-Screen Functional & Navigation Specifications

### Cross-Screen Navigation Matrix

| Source Screen | User Action / System Event | Target Screen | Payload Passed |
| :--- | :--- | :--- | :--- |
| **1. Laizysink Home** | Submit prompt (text/voice) or tap quick-action chip | **3. Active Task** | `task_id` (newly spawned DAG root) |
| **1. Laizysink Home** | Tap active/blocked task card | **3. Active Task** | `task_id` |
| **1. Laizysink Home** | Tap completed task card | **6. Completed Task Page** | `task_id` |
| **1. Laizysink Home** | Tap `Review all` or top-right `Tabs Hidden` pill | **2. All Tasks Hub** | Filter state (`ALL` or `ACTIVE_TABS`) |
| **2. All Tasks Hub** | Tap task in `Needs Attention` or `Working in Background` | **3. Active Task** | `task_id` |
| **2. All Tasks Hub** | Tap task in `Finished` | **6. Completed Task Page** | `task_id` |
| **3. Active Task** | Toggle header switch to `Map` | **4. Spatial Execution Map** | `task_id`, `focused_node_id` |
| **3. Active Task** | System emits `HITL_TAB_REQUIRED` interrupt | **5. Keychain Login & Spotlight** | `task_id`, `tab_id`, `target_selector`, `domain` |
| **4. Spatial Execution Map** | Toggle header switch to `Simple` | **3. Active Task** | `task_id` |
| **4. Spatial Execution Map** | Tap `Resolve` on a blocked node's inspector sheet | **3. Active Task** (or **5. Keychain**) | `task_id`, `barrier_id` |
| **5. Keychain Login & Spotlight** | Biometric autofill succeeds or manual step completes | **3. Active Task** (or **6. Completed**) | `task_id`, `keychain_ref` (`vault://...`) |
| **6. Completed Task Page** | Tap `View Map` / `Inspect Execution Map` | **4. Spatial Execution Map** | `task_id` (read-only audit mode) |
| **6. Completed Task Page** | Tap final handoff CTA (e.g., `Open Reservation Tab`) | **5. Keychain Login & Spotlight** | `task_id`, `staged_checkout_url` |

---

### Screen 1: Laizysink Home (`App.kt`, `Home` composable)
* **Role:** The post-tab entry point of the browser. Captures user intent with minimal friction, surfaces immediate high-priority background tasks, and keeps browser tabs invisible until human intervention is strictly necessary.
* **Data Inputs:**
  * Subscribes to `BackgroundWebViewPool` for the live hidden-tab badge (`Tabs Hidden • N Active`).
  * Queries `TaskDagStore` for the top-2 highest-priority tasks ranked by: (1) `NEEDS_ATTENTION`, (2) `WORKING`, and (3) most recently `FINISHED`.
* **Functional Mechanics:**
  * **Prompt Ingestion:** Submitting text in the bottom floating input bar dispatches `CREATE_TASK` (including attached local file URIs and active temporal memory IDs) and navigates immediately to **Screen 3 (Active Task)** while the backend decomposes the DAG.
  * **Voice-to-DAG (Gemini Transcribe):** Tapping the glowing indigo microphone button streams audio to `/api/transcribe` (or Android `SpeechRecognizer` fallback), populates the prompt bar, and auto-launches the DAG upon silence detection.
  * **Quick-Action Starter Chips:** Tapping preset chips (`Research cheap flights`, `Summarize open articles`, `Track package delivery`) scaffolds or immediately executes a task using active temporal context.
  * **Priority Card Routing:** Tapping a preview card routes `NEEDS_ATTENTION` / `WORKING` tasks to **Screen 3 (Active Task)** and `FINISHED` tasks to **Screen 6 (Completed Task Page)**.

### Screen 2: All Tasks Hub (`App.kt`, `Tasks` composable)
* **Role:** Global triage center and temporal memory controller (opened via `Review all` on Home). Prevents parallel background tasks from getting lost and lets users inspect or expire cross-task temporal context.
* **Data Inputs:**
  * Partitioned task lists from `TaskDagStore` ordered by recency: `NEEDS ATTENTION`, `WORKING IN BACKGROUND`, and `FINISHED`.
  * Active sliding-window memory summary from `TemporalMemoryStore` (e.g., `Active Context Window: Japan Trip (Nov 18–23) • 4 shared facts active`).
* **Functional Mechanics:**
  * **Priority Triage:** Tapping a `NEEDS ATTENTION` card deep-links directly to the exact blocker—either the active Data Barrier card on **Screen 3** or the paused foreground tab on **Screen 5**.
  * **Temporal Memory Management:** Tapping `Manage` on the top context banner opens a modal sheet listing inherited facts and their freshness tags (`USED`, `STALE_QUERIED`, `ENTIRELY_STALE`), allowing the user to expire a context window or trigger a manual refresh.
  * **`+ New` Action:** Returns to **Screen 1 (Laizysink Home)** with the prompt bar focused.

### Screen 3: Active Task (`App.kt`, `Active` composable)
* **Role:** Primary human-facing workspace for an in-progress goal. Hides backend graph complexity behind a clean 4-step horizontal progress stepper (`Plan` $\rightarrow$ `Researching Paths` $\rightarrow$ `Needs Input` $\rightarrow$ `Compare & Book`) and focuses attention on resolving **Dynamic Data Barriers** and inspecting live **Exa Research**.
* **Data Inputs:**
  * Aggregate milestone stepper state, active `DataBarrier` queue, live `ExaCitation` feed, and sandbox status footer (`1 tab paused awaiting input`).
* **Functional Mechanics:**
  * **Dynamic Data Barrier Resolution:** Renders 3–4 context-aware multiple-choice radio pills generated from Exa's initial search findings plus an `"Other (Type or speak details...)"` row with inline text/voice input. Submitting dispatches `RESOLVE_BARRIER`, writes the answer to `TemporalMemoryStore`, and unblocks *all* parallel branches waiting on those parameters simultaneously.
  * **Source Verification Drawer:** Tapping `View All` on the `Live Exa Research` card opens a comparison sheet separating **Selected / Leading Sources** from **Considered & Discarded Sources** (each with a one-line rejection reason).
  * **View Toggle (`Simple` $\leftrightarrow$ `Map`):** Tapping the top-right segmented toggle transitions to **Screen 4 (Spatial Execution Map)** preserving `task_id`.
  * **Automatic HITL Escalation:** If a background branch emits `HITL_TAB_REQUIRED`, automatically transitions to **Screen 5 (Keychain Login & Spotlight)**.

### Screen 4: Spatial Execution Map (`App.kt`, `ExecutionMap` composable)
* **Role:** Exposes Laizysink's dual-layer execution graph in an interactive, clutter-free 2D canvas. Separates independent **Subtask Nodes** (e.g., `Find Flights`, `Accommodation`, `Local Transit`, `Final Booking`) from granular **Work-Tasks** (individual Exa searches, barriers, keychain lookups) using **progressive disclosure**.
* **Data Inputs:**
  * Directed dependency graph (`SubtaskNode` list + `parent_ids` edges) and tethered child `WorkTask` chips.
* **Functional Mechanics:**
  * **Progressive Disclosure & Repulsion Physics:**
    * At most **one** Subtask Node is expanded at any moment (defaulting to the currently blocked or most recently active node). All sibling Subtask Nodes render as compact collapsed pills showing only title, status dot, and a `+N items` counter.
    * **Tap-to-Expand Behavior:** Tapping a collapsed Subtask Node collapses the previously active node, smoothly animates the newly tapped node into an open focal pocket, applies outward repulsion offsets to neighboring nodes so nothing overlaps, and fans out the selected node's tethered **Work-Task** satellite chips around it.
    * Supports free 2D drag-to-pan and pinch-to-zoom (plus floating `+` / `-` buttons).
  * **Two-Tier Scrollable Bottom Inspector Sheet:**
    * **Tier 1 (Immediately Visible Top Summary):** Displays the node's title, status badge, a concise 2-line task-adaptive plain-English synthesis (`plain_summary`), and 3 compact highlight chips (e.g., `£640 / person`, `Direct • 14h 10m`, `24h Free Hold`).
    * **Tier 2 (Scrollable Deep-Dive):** Scrolling down inside the bottom sheet reveals: (1) *Inherited Context & Keychain Refs* (`Dates`, `Guests`, `vault://...`), (2) *Decision Rationale* (why this option won and what was discarded), and (3) *Verified Exa Sources* with favicons and highlighted quotes.
  * **Direct Blocker Shortcut:** Selecting a `BLOCKED_USER` node surfaces a primary action button (`Resolve Input` or `Open Tab`) that routes directly to **Screen 3** or **Screen 5**.

### Screen 5: Keychain Login and Spotlight (`App.kt`, `Keychain` composable)
* **Role:** The Human-in-the-Loop (HITL) browser escalation surface. Reveals a specific background `WebView` when authentication, CAPTCHA, or final transaction approval is needed, visually spotlights the exact target DOM element, and mediates zero-trust biometric autofill.
* **Technical Implementation Note for `HiddenWebViewPool`:** Keep background `WebView` instances attached to the Compose hierarchy with `alpha = 0f` (and non-interactive pointer input) rather than unmounted so Chromium actively loads pages, maintains session cookies, and executes JS DOM queries before animating to `alpha = 1f` on reveal.
* **Functional Mechanics:**
  * **Auto-Scroll & Spotlight DOM Injection (`spotlight.js`):**
    * Executes via `webView.evaluateJavascript(...)` upon reveal: locates `target_selector`, calls `element.scrollIntoView({ behavior: 'smooth', block: 'center' })`, applies a full-viewport dimmed blur (`backdrop-filter: blur(4px)`) around a sharp cutout bounding box over the target element, and renders a floating dark tooltip pill directly above it (e.g., `"Auto-scrolled to login • Sign in to apply mileage points"`).
  * **Zero-Trust Biometric Keychain Flow:**
    * **Match Found:** Displays `vault://<domain>` and masked username in the bottom sheet. Tapping `Autofill & Sign In with Biometrics` invokes Android `BiometricPrompt`, injects credentials locally into the `WebView` DOM inputs, and emits only `KEYCHAIN_AUTH_COMPLETE` (`"keychain_ref": "vault://jal.co.jp"`) to the backend.
    * **No Match:** Lets the user type directly into the spotlighted webpage inputs and offers an opt-in `Save to encrypted Keychain` toggle.
  * **Auto-Re-Hide:** Once post-login navigation or session cookie confirmation occurs, strips the spotlight overlay, returns the `WebView` to `alpha = 0f`, and navigates back to **Screen 3 (Active Task)** as the unblocked branch resumes.

### Screen 6: Completed Task Page (`App.kt`, `Completed` composable)
* **Role:** Final deliverable and audit ledger for a finished goal. Proves recommendations are grounded in live Exa web citations, demonstrates temporal memory reuse across separate tasks, and provides a safe handoff to final real-world execution.
* **Data Inputs:**
  * Merged root decision summary, inherited `TemporalMemoryStore` context banner (with freshness badge `Live Synced` / `Refreshed`), and verified `ExaCitation` cards.
* **Functional Mechanics:**
  * **Temporal Memory Reuse & Freshness Refresh:** When a follow-up prompt reuses earlier task data (e.g., *"Dinner near my Tokyo hotel"* inheriting hotel coordinates and dates from *Japan Trip*), displays the active memory banner at the top. If any inherited node exceeded its TTL ($t > \text{TTL}$), the backend marks it `STALE_QUERIED`, performs a silent Exa refresh before finalizing, and displays `Live Verified`. Tapping `Edit` allows overriding inherited constraints.
  * **Citation Verification:** Displays each source's favicon, domain, verification pill, and exact highlighted snippet from Exa's Contents API. Tapping any citation opens the live URL in an in-app preview sheet.
  * **Final Execution Handoff:** Tapping the primary bottom CTA (e.g., `Open Reservation Tab` or `Open Drafted Reply Email`) unhides the pre-staged `WebView` (routing through **Screen 5** if login or payment entry is needed) with dates, party size, and promo codes pre-filled.
  * **Audit & Alternatives:** Tapping `Show Other Options` expands runner-up options; tapping `View Map` opens **Screen 4 (Spatial Execution Map)** in read-only audit mode.

---

## 7. Verification & Acceptance Checklist (Swarm Gate)

Before completing the build, **Agent 0 (Swarm Lead)** must verify:
1. **Zero Compilation Errors:** Both `uvicorn main:app` (Python 3.11+) and `kotlin build` (Kotlin Multiplatform Toolchain; Android and Desktop JVM targets, with optional additional configured targets) on Temurin JDK 25 build cleanly with zero missing imports or unresolved references.
2. **Contract Parity:** Every JSON key emitted by `backend/models.py` deserializes cleanly into `shared/src/com/onecaramel/laizysink/state/Models.kt`.
3. **Live Exa Grounding:** With a user-provided `EXA_API_KEY`, submitting an arbitrary prompt spawns parallel branches, calls Exa's live API, and renders clickable URLs with highlighted quotes. Never substitute mock/seed data if credentials are unavailable; report that live verification is pending.
4. **Spatial Map Progressive Disclosure:** Tapping different Subtask Nodes on `App.kt (`ExecutionMap` composable)` smoothly collapses the previous node, expands the new node's satellite Work-Tasks, pushes siblings outward, and updates the 2-tier scrollable bottom inspector sheet.
5. **Zero-Trust Keychain & Spotlight:** Triggering `HITL_TAB_REQUIRED` unhides the `WebView`, injects `spotlight.js` to blur everything except the target form, and completes biometric autofill without transmitting raw passwords over WebSocket.
