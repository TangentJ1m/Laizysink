# Product Requirements Document (PRD): Laizysink

**Document Version:** 1.0.0  
**Status:** Approved / In Active Prototyping  
**Author:** AI Design & Systems Team  
**Design System Reference:** Obsidian Kinetic (`#0D0F14` / `#161922` / `#6366F1`)  
**Target Viewport:** Android Native Mobile (412 × 915 dp)

---

## 1. Executive Summary & Vision

### 1.1 Problem Statement
Modern personal AI assistants suffer from the "black box problem" and the "credential friction gap":
1. **Opaque Multi-Step Execution:** When an assistant executes complex multi-step workflows (e.g. comparing flights, verifying tenancy laws, reserving restaurants), users are given flat text summaries without visibility into execution graphs (DAGs), citation sources, or validation paths.
2. **Credential Insecurity & Handoff Breakdown:** Traditional browser bots either demand plain-text passwords stored in cloud environments or fail completely whenever a portal requires 2FA, biometric keychain access, or CAPTCHA resolution.
3. **Context Window Fragmentation:** Errand context evaporates between sessions, forcing users to repeatedly restate parameters, constraints, and preferences.

### 1.2 Product Vision
**Laizysink** is an autonomous AI browser assistant designed for high-agency personal and legal errand execution. It couples autonomous background execution (via live Exa neural web queries and headless browser sandboxes) with radical visibility and localized cryptographic security. Laizysink introduces **Human Handoff via Spatial Spotlight** and **Visual DAG Execution Maps**, ensuring users always maintain executive authority without enduring administrative tedium.

---

## 2. Core Personas & Use Cases

### 2.1 Target User Personas
* **The Autonomous Delegator (Primary):** Power users, founders, and knowledge workers who manage complex personal logistics (travel itineraries, dinner reservations, multi-vendor comparisons) and need high speed with total confidence.
* **The Bureaucratic Navigator (Secondary):** Tenants, expatriates, and freelancers who encounter legal or financial disputes (rent hikes, utility billing errors, contract clauses) requiring verifiable statutory research and drafted responses.

### 2.2 Key Use Cases Evaluated in V1
1. **Multi-Leg Logistics & Trip Planning ("Japan Trip"):** Multi-node dependency resolution across flights (ANA/JAL), accommodations, and local transit with 24-hour fare lock constraints.
2. **Statutory & Legal Analysis ("Helsinki Rent Increase Check"):** Ingestion of tenancy agreements, cross-referencing against Finnish Finlex statutes and official CPI indexes, and automated generation of formal dispute correspondence.
3. **Guided Keychain Authentication ("JAL Mileage Login"):** Automated headless browser navigation to an airline checkout page, auto-scrolling to the sign-in form, spotlight isolation, and localized biometric autofill via Android Keystore.

---

## 3. Product Architecture & Core Pillars

### Pillar 1: Verified Neural Web Synthesis (Exa-Powered)
* Every fact, fare rule, price metric, or legal statute extracted by Laizysink is cryptographically cited with live domain metadata, last-verified timestamps, and snippet extractions.
* Eliminates hallucination by enforcing a statutory link verification step before flagging a task as "Done".

### Pillar 2: Localized Hardware Keystore (Zero-Knowledge Cloud)
* User passwords, biometrics, and session tokens are strictly sequestered within the local Android Keystore (`vault://[domain]`).
* Cloud AI sandboxes never ingest raw credentials. When an action requires authenticated credentials, execution yields control to a native **"Your Turn"** modal.

### Pillar 3: Dual-Mode Transparency (Simple Summary vs. DAG Execution Map)
* Users can toggle seamlessly between a simple executive summary (plain-English synthesis, financial impact cards) and an interactive 2D spatial DAG showing active execution nodes, dependencies, and tethered sub-tasks.

---

## 4. Detailed Screen Specifications & User Flows

### Flow 1: Home & Universal Agent Prompting (`SCREEN_18`)
* **Purpose:** Primary command center and omnibar entry point.
* **Key Components:**
  * Status-driven greeting with temporal context chip.
  * Active tasks quick-carousel highlighting live background execution.
  * Omnibar with dual modality: voice input (electric indigo glow) and structured prompt dispatch.
  * "Review all" navigation anchor linking directly to the Tasks Hub.

### Flow 2: All Tasks & Temporal Context Hub (`SCREEN_4`)
* **Purpose:** Central registry of all active, blocked, running, and historical errands.
* **Sections:**
  1. **Active Temporal Memory Strip:** Prominently displays the active working context window (e.g., *"Japan Trip (Nov 18–23) • 4 shared facts active"*) with management controls.
  2. **Needs Attention (Blocked / User Turn):** Highlights tasks requiring human input (e.g., accommodation selection, JAL biometric login) with amber urgency indicators.
  3. **Working in Background (Running):** Displays ongoing multi-site scraping or menu scanning with real-time percentage indicators.
  4. **Finished (Archive & Audit):** Retains verified findings with timestamped Exa citation counts and delta savings metrics.

### Flow 3: Spatial Execution Map & Node Inspector (`SCREEN_2` & `SCREEN_10`)
* **Purpose:** Visual Directed Acyclic Graph (DAG) demystifying multi-threaded agent execution.
* **Upper Viewport (58%):**
  * Interactive canvas with dot-grid pattern (`#0D0F14` with `#1C202B` grid).
  * Radial progressive disclosure: Sibling nodes (*Local Transit*, *Accommodation*, *Final Booking*) remain collapsed along the perimeters.
  * Active node (*Find Flights*) centered in an expanded focus state, ringed with glowing electric indigo accents (`#6366F1`) and tethered satellite chips (*ANA Directs*, *Fare Comparison*, *Keychain Vault*).
* **Lower Viewport (42%):**
  * Layered bottom drawer featuring executive plain-English decision logic (*"Picked ANA Direct Return for £640..."*).
  * Key constraint badges: Price per person, direct travel time, 24-hour fare hold availability.
  * Expandable verified citation cards citing official airline fare rules.

### Flow 4: Guided External WebView & Keychain Handoff (`SCREEN_8` & `SCREEN_14`)
* **Purpose:** Frictionless credential handoff on third-party airline portals.
* **Interaction Paradigm:**
  * Headless agent opens external target URL (`booking.jal.co.jp/login`).
  * Dimmed backdrop overlay masks noisy page chrome, applying an ambient glowing spotlight directly onto the target authentication form.
  * Context pill: *"✨ Auto-scrolled to login • Sign in to apply 42,000 miles"*.
  * Bottom sheet slides up displaying Android Keystore biometric prompt (`KT` • `kenji.tanaka@email.com`) with zero-cloud-exposure guarantees.

### Flow 5: Statutory Errand & Financial Verdict (`SCREEN_6`)
* **Purpose:** Resolution of personal legal disputes with verifiable proof and actionable drafts.
* **Key Components:**
  * 4-Stage Horizontal Progress Stepper (*Contract Parsed* → *Finnish Law Search* → *Index Verified* → *Draft Ready*).
  * Verdict Summary Card with breach indicator (*Proposed +12% exceeds statutory cap of +2.1%*).
  * Monthly Delta Impact Grid demonstrating direct monetary savings (€118.80 / month).
  * Direct statute citations linking to Finlex and Statistics Finland CPI indexes.
  * Primary action CTA: *"Open Drafted Reply Email"*, accompanied by full PDF attachment verification.

---

## 5. Visual Design & Token Hierarchy (Obsidian Kinetic)

| Token Name | Hex Value | Purpose |
| :--- | :--- | :--- |
| **Surface Canvas** | `#0D0F14` | Ultra-deep obsidian background |
| **Surface Elevated** | `#161922` | Cards, bottom sheets, and inspector panels |
| **Surface Border** | `#252A37` | Subtle 1px structural framing |
| **Accent Primary** | `#6366F1` | Electric indigo for active nodes, primary CTAs, and focus glows |
| **Status Emerald** | `#10B981` | Completed tasks, verified Exa badges, and legal cap matches |
| **Status Amber** | `#F59E0B` | Paused execution, "Your Turn" required, and biometric prompts |
| **Typography** | Inter & JetBrains Mono | Inter for crisp UI hierarchy; JetBrains Mono for URLs, hashes, and vaults |

---

## 6. Non-Functional Requirements & Security Guarantees

1. **Zero-Knowledge Credential Boundary:** Assistant cloud LLMs must never receive raw passwords or auth tokens. Handshakes must trigger client-side Keystore decryption.
2. **Deterministic Citations:** Citations must point to raw snapshot URLs cached within the local task graph to prevent link rot or dynamic page drift.
3. **Offline Resilience:** Previously resolved execution DAGs and drafted emails must remain browsable in low-connectivity or offline flight scenarios.
4. **Sub-200ms Spatial Interaction:** Pannable graph canvas interactions must maintain 60–120 fps hardware acceleration on standard Android chipsets.

---

## 7. Roadmap & Next Phases

* **V1.1:** Multi-agent collaboration view (delegate parallel errands to sub-agents on split screen).
* **V1.2:** Native OAuth passkey synchronization with Google Password Manager.
* **V2.0:** Desktop companion app featuring wide-canvas multi-window spatial DAG orchestration.
