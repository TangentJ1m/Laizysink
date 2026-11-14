---
name: Obsidian Kinetic
colors:
  surface: '#0d141d'
  surface-dim: '#0d141d'
  surface-bright: '#333a44'
  surface-container-lowest: '#080f17'
  surface-container-low: '#151c25'
  surface-container: '#192029'
  surface-container-high: '#232a34'
  surface-container-highest: '#2e353f'
  on-surface: '#dce3f0'
  on-surface-variant: '#c7c4d7'
  inverse-surface: '#dce3f0'
  inverse-on-surface: '#2a313b'
  outline: '#908fa0'
  outline-variant: '#464554'
  surface-tint: '#c0c1ff'
  primary: '#c0c1ff'
  on-primary: '#1000a9'
  primary-container: '#8083ff'
  on-primary-container: '#0d0096'
  inverse-primary: '#494bd6'
  secondary: '#4edea3'
  on-secondary: '#003824'
  secondary-container: '#00a572'
  on-secondary-container: '#00311f'
  tertiary: '#ffb95f'
  on-tertiary: '#472a00'
  tertiary-container: '#ca8100'
  on-tertiary-container: '#3e2400'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#e1e0ff'
  primary-fixed-dim: '#c0c1ff'
  on-primary-fixed: '#07006c'
  on-primary-fixed-variant: '#2f2ebe'
  secondary-fixed: '#6ffbbe'
  secondary-fixed-dim: '#4edea3'
  on-secondary-fixed: '#002113'
  on-secondary-fixed-variant: '#005236'
  tertiary-fixed: '#ffddb8'
  tertiary-fixed-dim: '#ffb95f'
  on-tertiary-fixed: '#2a1700'
  on-tertiary-fixed-variant: '#653e00'
  background: '#0d141d'
  on-background: '#dce3f0'
  surface-variant: '#2e353f'
typography:
  headline-xl:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.015em
  headline-md:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: 0.01em
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0.01em
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-lg:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '600'
    lineHeight: 20px
    letterSpacing: 0.02em
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.04em
  label-sm:
    fontFamily: Inter
    fontSize: 10px
    fontWeight: '600'
    lineHeight: 14px
    letterSpacing: 0.06em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-sm: 0.75rem
  margin: 1rem
  margin-tablet: 1.5rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
---

## Brand & Style

This design system establishes an intelligent, nocturnal, and razor-sharp environment tailored for an AI-native personal assistant browser on Android. Built for digital minimalists, power users, and deep-focus researchers, the visual narrative rejects high-friction decorative clutter in favor of an immersive deep-space canvas where AI synthesis feels immediate and atmospheric.

The aesthetic fuses **Minimalism** with subtle **Cybernetic Glow / Dark Tactility**:
- An uncompromising pitch-obsidian ground that melts into OLED smartphone bezels.
- Elevated charcoal structural layers providing physical separation without stark white light contamination.
- Electric indigo luminescence deployed sparingly as a telemetry signal, highlighting proactive AI recommendations, active web threads, and synthesis states.
- Fluid, ergonomic touch affordances strictly engineered for single-handed mobile navigation (48dp+ thumb-friendly bottom-focused architecture).

## Colors

The palette is engineered for prolonged evening web consumption and power-efficient OLED displays. Visual hierarchy relies on calculated contrasts between near-black slate surfaces and radiant spectral accents.

### Core Color Roles
- **Base Canvas (`#0D0F14`):** True obsidian baseline ground; isolates focus and reduces eye fatigue.
- **Surface Elevation (`#161922`):** Structural card containers, floating app bars, and input shells.
- **Structural Outlines (`#252A37`):** Hairline borders delivering geometric definition without drawing attention away from content.
- **Primary Indigo (`#6366F1`):** Electric indigo signaling active AI processing, key actions, link states, and interactive focal points.
- **Semantic Accents:**
  - **Success Emerald (`#10B981`):** Confirmed downloads, secure browser connections, saved offline captures.
  - **Warning Amber (`#F59E0B`):** Untrusted domain warnings, battery/data saver notices, storage thresholds.
- **Typography & Iconography:**
  - **Primary Text (`#F3F4F6`):** Crisp off-white ensuring 14:1+ contrast against obsidian surfaces.
  - **Muted Text (`#9CA3AF`):** Secondary metadata, domain protocols, and inactive tabs.

## Typography

Typography relies entirely on **Inter** to deliver computational precision, optical balance, and frictionless legibility across high-density mobile screens.

- **Headlines:** Displayed with tighter tracking (`-0.01em` to `-0.02em`) and semi-bold/bold weights to anchor synthesis overviews and browser dashboard views.
- **Reading Hierarchy:** Web reader modes leverage `body-lg` (16px / 24px line height) to prevent line crowding during extended AI summary consumption.
- **Labels & Micro-copy:** Pill indicators, search queries, and status beacons employ medium-to-semi-bold weights with loose tracking to sustain crisp definition even at `10px`.

## Layout & Spacing

The layout is built for native mobile ergonomics, optimizing for one-handed reachability along the bottom half of the display.

- **Grid Architecture:** 4-column fluid mobile grid transitioning to 8 columns on foldable unfolding or tablets.
- **Screen Margins:** Standard 16px (`1rem`) horizontal canvas padding, scaling to 24px (`1.5rem`) on larger viewports.
- **Vertical Ergonomics:** High-frequency actions (Omnibar address input, AI quick-actions, tab switcher) are anchored within a 48–72dp sticky bottom cluster.
- **Touch Bounds:** All interactive touch elements require a minimum hit target of `48px x 48px`, regardless of visual footprint, to meet strict Android mobile usability benchmarks.

## Elevation & Depth

Rather than conventional drop shadows—which disappear into pure dark backgrounds—depth is rendered through **tonal stacking**, **ghost outlines**, and **specular electric glows**.

- **Level 0 (Ground Canvas):** `#0D0F14` flat background.
- **Level 1 (Sub-surface Modules & Cards):** `#161922` combined with a 1px border of `#252A37`.
- **Level 2 (Floating Omnibar & Bottom Sheets):** `#1E222E` with 1px `#2E3547` border, accompanied by an ultra-soft ambient shadow: `0px 12px 32px rgba(0, 0, 0, 0.6)`.
- **Active AI Beacon Elevation:** Interactive elements driven by the AI engine feature an energized radial backlight: `0px 0px 24px rgba(99, 102, 241, 0.25)`.

## Shapes

The shape system blends modern Android curvature with tactile card geometry (`roundedness: 2`).

- **Cards & Sheets:** Baseline corners set to `12px` (`space-md`), creating clean pocket enclosures for web tab snapshots and summary modules.
- **Dialogs & Overlay Modals:** `16px` (`rounded-lg`) corner curvature to visually soften floating interventions.
- **Badges, Buttons & Pills:** Pill curvature (`9999px`) applied to all search omnibars, quick-tag pills, and primary trigger buttons.

## Components

### 1. Primary Action Buttons
- **Structure:** Pill-shaped (`rounded-full`), height 48px, minimum width 120px, horizontal padding `space-lg`.
- **Primary:** Background `#6366F1`, text `#F3F4F6` (semibold), with a soft `0 0 16px rgba(99, 102, 241, 0.35)` glow when activated.
- **Secondary / Ghost:** Transparent background, 1px border `#252A37`, text `#F3F4F6`. Pressed state resolves to `#161922`.

### 2. Omnibar & Input Fields
- **Container:** Grounded charcoal surface `#161922`, 1px border `#252A37`, height 52px, corner radius 26px (pill).
- **Focus / AI Processing:** Border smoothly transitions to `#6366F1` with an outer ambient glow of `0 0 12px rgba(99, 102, 241, 0.2)`.
- **Placeholder:** `#9CA3AF`, label-md font style.

### 3. Sleek Rounded Cards (AI Summaries & Web Cards)
- **Container:** Background `#161922`, 1px hairline border `#252A37`, rounded `12px` (level 2).
- **Content Spacing:** Internal padding `space-md` (16px).
- **Active State:** Border shifts to `rgba(99, 102, 241, 0.6)` with elevated charcoal surface `#1A1E29`.

### 4. Pill Badges & Chips
- **Container:** Height 28px, pill border-radius, padding 0 12px.
- **Default Tag:** Background `rgba(37, 42, 55, 0.5)`, text `#9CA3AF`, 1px border `#252A37`.
- **Active / AI Filter:** Background `rgba(99, 102, 241, 0.15)`, text `#6366F1`, 1px border `rgba(99, 102, 241, 0.4)`.

### 5. Checkboxes, Switches & Radio Controls
- **Checkboxes & Radios:** 20px hit-area encased in a 48px touch frame. Unchecked: 1.5px border `#252A37`, unchecked interior transparent. Checked: `#6366F1` fill with `#F3F4F6` check icon.
- **Switches:** 44px x 24px track (`#252A37`), 20px thumb (`#9CA3AF`). Active state switches track to `#6366F1` and thumb to `#F3F4F6`.

### 6. Assistant Pulse Dock (Custom Component)
- **Position:** Anchored bottom dock floating 16px above the Android system gesture bar.
- **Styling:** Frosted `#161922` surface with 80% opacity, 1px border `rgba(99, 102, 241, 0.25)`, encapsulating speech input, instant synthesis trigger, and context tab switcher.